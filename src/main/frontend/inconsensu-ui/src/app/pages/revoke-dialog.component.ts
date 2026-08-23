import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, ConsentCard, DictionaryItem, RevocableConsent } from '../api.service';

/** Что диалог получает при открытии: либо конкретное согласие, либо выбор из списка (UI-5). */
export interface RevokeDialogData {
  subjectId: string;
  consentId?: string;
  consentTitle?: string;
}

/**
 * UI-5: отзыв согласия.
 *
 * Перед подтверждением показывается каскад — какие согласия погаснут заодно. Отзыв необратим, поэтому
 * последствия называются до нажатия, а не после.
 */
@Component({
  selector: 'ic-revoke-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
  ],
  template: `
    <h2 mat-dialog-title>Отзыв согласия</h2>
    <mat-dialog-content class="ic-dialog">
      <p *ngIf="data.consentTitle">Отзываем: <b>{{ data.consentTitle }}</b></p>

      <mat-form-field appearance="outline" class="ic-full" *ngIf="!data.consentId">
        <mat-label>Какое согласие отзываем</mat-label>
        <mat-select [(ngModel)]="consentId" (ngModelChange)="loadCascade()" required>
          <mat-option *ngFor="let candidate of candidates()" [value]="candidate.id">
            {{ candidate.title }}
          </mat-option>
        </mat-select>
      </mat-form-field>

      <mat-checkbox [(ngModel)]="allAdvertising">
        Отозвать все рекламные согласия (требование клиента прекратить рекламу)
      </mat-checkbox>

      <mat-form-field appearance="outline" class="ic-full">
        <mat-label>Источник обращения</mat-label>
        <mat-select [(ngModel)]="revocationSource" required>
          <mat-option *ngFor="let source of sources()" [value]="source.code">{{ source.nameRu }}</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline" class="ic-full">
        <mat-label>Причина</mat-label>
        <textarea matInput rows="2" [(ngModel)]="reason" required></textarea>
      </mat-form-field>

      <mat-form-field appearance="outline" class="ic-full">
        <mat-label>Номер обращения</mat-label>
        <input matInput [(ngModel)]="caseNumber" required />
      </mat-form-field>

      <mat-form-field appearance="outline" class="ic-full">
        <mat-label>Ссылка на скан заявления (нужна для письменного заявления)</mat-label>
        <input matInput [(ngModel)]="documentRef" />
      </mat-form-field>

      <div class="ic-warn" *ngIf="cascade().length">
        <div class="ic-warn-title">Будут также отозваны:</div>
        <ul>
          <li *ngFor="let row of cascade()">{{ row.typeName }}</li>
        </ul>
      </div>
      <div class="ic-danger">Отзыв необратим и вступает в силу немедленно.</div>
      <div class="ic-danger" *ngIf="error()">{{ error() }}</div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button mat-dialog-close>Отмена</button>
      <button mat-flat-button color="warn" [disabled]="!ready() || sending()" (click)="submit()">Отозвать</button>
    </mat-dialog-actions>
  `,
})
export class RevokeDialogComponent {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialogRef<RevokeDialogComponent>);
  readonly data = inject<RevokeDialogData>(MAT_DIALOG_DATA);

  readonly candidates = signal<RevocableConsent[]>([]);
  readonly sources = signal<DictionaryItem[]>([]);
  readonly cascade = signal<ConsentCard[]>([]);
  readonly sending = signal(false);
  readonly error = signal('');

  consentId = this.data.consentId ?? '';
  allAdvertising = false;
  revocationSource = '';
  reason = '';
  caseNumber = '';
  documentRef = '';

  constructor() {
    this.api.dictionaries().subscribe((dictionaries) => this.sources.set(dictionaries.revocationSources));
    if (!this.data.consentId) {
      this.api.revocable(this.data.subjectId).subscribe((rows) => this.candidates.set(rows));
    } else {
      this.loadCascade();
    }
  }

  loadCascade(): void {
    if (this.consentId) {
      this.api.cascade(this.consentId).subscribe((rows) => this.cascade.set(rows));
    }
  }

  ready(): boolean {
    return !!this.consentId && !!this.revocationSource && !!this.reason.trim() && !!this.caseNumber.trim();
  }

  submit(): void {
    this.sending.set(true);
    this.error.set('');
    this.api
      .revoke(this.consentId, {
        reason: this.reason,
        revocationSource: this.revocationSource,
        caseNumber: this.caseNumber,
        documentRef: this.documentRef,
        allAdvertising: this.allAdvertising,
      })
      .subscribe({
        next: (result) => this.dialog.close(result.message),
        error: (failure) => {
          this.sending.set(false);
          // UI-0.9: показываем причину отказа, а не «что-то пошло не так».
          this.error.set(failure?.error?.detail ?? 'Отзыв не выполнен. Проверьте заполнение полей.');
        },
      });
  }
}

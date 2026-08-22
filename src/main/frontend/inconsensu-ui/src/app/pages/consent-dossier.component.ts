import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, ConsentDossier } from '../api.service';
import { RevokeDialogComponent } from './revoke-dialog.component';

/**
 * UI-4a: досье согласия — доказательство того, что согласие получено именно так.
 *
 * Текст формы, контрольная сумма, поля доказательств и лента событий по этому согласию. Значения,
 * содержащие ПДн, приходят с сервера уже маскированными (NFR-3).
 */
@Component({
  selector: 'ic-consent-dossier',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatTableModule,
    MatButtonModule,
    MatDialogModule,
    MatExpansionModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <ng-container *ngIf="dossier() as row">
      <div class="ic-card-head">
        <div>
          <h1 class="ic-title">{{ row.consentTypeRu }}</h1>
          <div class="ic-subtitle">
            Клиент: <a [routerLink]="['/subjects', row.subjectId]">{{ row.subjectName }}</a>
          </div>
        </div>
        <button mat-flat-button color="warn" *ngIf="!row.revokedAt" (click)="revoke(row)">Отозвать</button>
      </div>

      <div class="ic-note" *ngIf="message()">{{ message() }}</div>

      <mat-card class="ic-block">
        <mat-card-header><mat-card-title>Сведения о согласии</mat-card-title></mat-card-header>
        <mat-card-content>
          <dl class="ic-facts">
            <dt>Статус</dt>
            <dd>{{ row.statusRu }}</dd>
            <dt>Получено</dt>
            <dd>{{ row.grantedAt || '—' }}</dd>
            <dt>Действует до</dt>
            <dd>{{ row.validUntil || 'бессрочно' }}</dd>
            <dt>Источник</dt>
            <dd>{{ row.source || '—' }}</dd>
            <dt>Вид подписи</dt>
            <dd>{{ row.signatureTypeRu }}</dd>
            <ng-container *ngIf="row.revokedAt">
              <dt>Отозвано</dt>
              <dd>{{ row.revokedAt }}</dd>
              <dt>Источник отзыва</dt>
              <dd>{{ row.revocationSourceRu || '—' }}</dd>
              <dt>Причина</dt>
              <dd>{{ row.revocationReason || '—' }}</dd>
            </ng-container>
          </dl>
        </mat-card-content>
      </mat-card>

      <mat-card class="ic-block">
        <mat-card-header><mat-card-title>Доказательства</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="ic-badge" [class]="row.checksumMatches ? 'ic-badge ok' : 'ic-badge danger'">
            {{
              row.checksumMatches
                ? 'Контрольная сумма текста совпадает с сохранённой'
                : 'Контрольная сумма не совпадает: текст формы изменён после подписания'
            }}
          </div>
          <div class="ic-badge ic-gap" [class]="row.integrityOk ? 'ic-badge ok' : 'ic-badge danger'">
            {{ row.integrityMessage }}
          </div>
          <dl class="ic-facts ic-gap" *ngIf="hasEvidence(row)">
            <ng-container *ngFor="let field of row.evidence">
              <dt>{{ field.nameRu }}</dt>
              <dd>{{ field.value }}</dd>
            </ng-container>
          </dl>
          <p class="ic-empty" *ngIf="!hasEvidence(row)">Поля доказательств не заполнены.</p>
        </mat-card-content>
      </mat-card>

      <mat-card class="ic-block">
        <mat-expansion-panel>
          <mat-expansion-panel-header>
            <mat-panel-title>Текст формы</mat-panel-title>
            <mat-panel-description>
              {{ row.formTitle }}<span *ngIf="row.formVersion"> · версия {{ row.formVersion }}</span>
            </mat-panel-description>
          </mat-expansion-panel-header>
          <pre class="ic-form-text">{{ row.formText }}</pre>
          <div class="ic-subtitle">Контрольная сумма: {{ row.storedChecksum }}</div>
        </mat-expansion-panel>
      </mat-card>

      <mat-card class="ic-block">
        <mat-card-header><mat-card-title>События по согласию</mat-card-title></mat-card-header>
        <mat-card-content>
          <table mat-table [dataSource]="row.events" class="ic-table" *ngIf="row.events.length">
            <ng-container matColumnDef="occurredAt">
              <th mat-header-cell *matHeaderCellDef>Когда</th>
              <td mat-cell *matCellDef="let event">{{ event.occurredAt }}</td>
            </ng-container>
            <ng-container matColumnDef="eventType">
              <th mat-header-cell *matHeaderCellDef>Событие</th>
              <td mat-cell *matCellDef="let event">{{ event.eventTypeRu }}</td>
            </ng-container>
            <ng-container matColumnDef="description">
              <th mat-header-cell *matHeaderCellDef>Что произошло</th>
              <td mat-cell *matCellDef="let event">{{ event.description }}</td>
            </ng-container>
            <ng-container matColumnDef="actor">
              <th mat-header-cell *matHeaderCellDef>Кто</th>
              <td mat-cell *matCellDef="let event">{{ event.actorRu }}</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="columns"></tr>
            <tr mat-row *matRowDef="let event; columns: columns"></tr>
          </table>
          <p class="ic-empty" *ngIf="!row.events.length">Событий по этому согласию нет.</p>
        </mat-card-content>
      </mat-card>
    </ng-container>
  `,
})
export class ConsentDossierComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialogs = inject(MatDialog);

  readonly dossier = signal<ConsentDossier | null>(null);
  readonly loading = signal(false);
  readonly message = signal('');
  readonly columns = ['occurredAt', 'eventType', 'description', 'actor'];

  private id = '';

  constructor() {
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.dossier(this.id).subscribe((row) => {
      this.dossier.set(row);
      this.loading.set(false);
    });
  }

  hasEvidence(row: ConsentDossier): boolean {
    return (row.evidence ?? []).length > 0;
  }

  revoke(row: ConsentDossier): void {
    this.dialogs
      .open(RevokeDialogComponent, {
        data: { subjectId: row.subjectId, consentId: row.id, consentTitle: row.consentTypeRu },
        width: '560px',
      })
      .afterClosed()
      .subscribe((result?: string) => {
        if (result) {
          this.message.set(result);
          this.load();
        }
      });
  }
}

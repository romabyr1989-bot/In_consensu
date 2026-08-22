import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { ApiService, BuilderOptions, FormPage } from '../api.service';

/**
 * UI-7: список форм согласий.
 *
 * Сверху — то, что ждёт решения текущего сотрудника: иначе согласование приходится искать в общем
 * списке, а срок ответа ограничен (FR-1.4).
 */
@Component({
  selector: 'ic-catalog-forms',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Формы согласий</h1>

    <mat-card class="ic-block" *ngIf="page()?.awaitingDecision?.length">
      <mat-card-header><mat-card-title>Ждут вашего решения</mat-card-title></mat-card-header>
      <mat-card-content>
        <div class="ic-awaiting" *ngFor="let row of page()?.awaitingDecision">
          <a [routerLink]="['/catalog/forms', row.id]">{{ row.title }}</a>
          <span class="ic-muted">версия {{ row.version }} · {{ row.statusRu }}</span>
        </div>
      </mat-card-content>
    </mat-card>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline">
          <mat-label>Статус</mat-label>
          <mat-select [(ngModel)]="status" (ngModelChange)="load()">
            <mat-option value="">Все</mat-option>
            <mat-option *ngFor="let item of options()?.statuses" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Источник</mat-label>
          <mat-select [(ngModel)]="source" (ngModelChange)="load()">
            <mat-option value="">Все</mat-option>
            <mat-option *ngFor="let item of options()?.sources" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Поиск по коду или названию</mat-label>
          <input matInput [(ngModel)]="text" (keyup.enter)="load()" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="creating.set(true)">Новая форма</button>
      </mat-card-content>
    </mat-card>

    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="creating()">
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline">
          <mat-label>Код формы</mat-label>
          <input matInput [(ngModel)]="newCode" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Название</mat-label>
          <input matInput [(ngModel)]="newTitle" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="create()">Создать черновик</button>
        <button mat-button (click)="creating.set(false)">Отмена</button>
      </mat-card-content>
    </mat-card>

    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="page()?.rows ?? []" class="ic-table" *ngIf="page()?.rows?.length">
        <ng-container matColumnDef="code">
          <th mat-header-cell *matHeaderCellDef>Код</th>
          <td mat-cell *matCellDef="let row">{{ row.code }}</td>
        </ng-container>
        <ng-container matColumnDef="title">
          <th mat-header-cell *matHeaderCellDef>Название</th>
          <td mat-cell *matCellDef="let row">
            <a [routerLink]="['/catalog/forms', row.id]">{{ row.title }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="version">
          <th mat-header-cell *matHeaderCellDef>Версия</th>
          <td mat-cell *matCellDef="let row">{{ row.version }}</td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Статус</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-badge" [class]="'ic-badge ' + badge(row.status)">{{ row.statusRu }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <a mat-button *ngIf="row.editable" [routerLink]="['/catalog/forms', row.id, 'edit']">Конструктор</a>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!page()?.rows?.length && !loading()">Форм, подходящих под фильтр, нет.</p>
    </mat-card>
  `,
})
export class CatalogFormsComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly page = signal<FormPage | null>(null);
  readonly options = signal<BuilderOptions | null>(null);
  readonly loading = signal(false);
  readonly creating = signal(false);
  readonly error = signal('');

  readonly columns = ['code', 'title', 'version', 'status', 'actions'];

  status = '';
  source = '';
  text = '';
  newCode = '';
  newTitle = '';

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const filters: Record<string, string> = {};
    if (this.status) {
      filters['status'] = this.status;
    }
    if (this.source) {
      filters['source'] = this.source;
    }
    if (this.text.trim()) {
      filters['text'] = this.text.trim();
    }
    this.api.forms(filters).subscribe((page) => {
      this.page.set(page);
      this.loading.set(false);
    });
  }

  create(): void {
    this.error.set('');
    this.api.createForm(this.newCode, this.newTitle).subscribe({
      next: (row) => {
        this.creating.set(false);
        this.newCode = '';
        this.newTitle = '';
        this.router.navigate(['/catalog/forms', row.id, 'edit']);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Черновик не создан: проверьте код и название.'),
    });
  }

  badge(status: string): string {
    if (status === 'PUBLISHED') {
      return 'ok';
    }
    if (status === 'ARCHIVED' || status === 'REJECTED') {
      return 'danger';
    }
    return 'warn';
  }
}

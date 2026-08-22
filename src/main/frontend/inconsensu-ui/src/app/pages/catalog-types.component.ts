import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, BuilderOptions, TypeRow } from '../api.service';

/**
 * UI-6: справочник типов согласий.
 *
 * Правка идёт на месте: панель раскрывается под таблицей и заполняется выбранной строкой. Код при
 * правке не меняется — по нему тип связан с формами и выданными согласиями (FR-1.1).
 */
@Component({
  selector: 'ic-catalog-types',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Типы согласий</h1>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline">
          <mat-label>Категория</mat-label>
          <mat-select [(ngModel)]="category" (ngModelChange)="load()">
            <mat-option value="">Все</mat-option>
            <mat-option *ngFor="let item of options()?.categories" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Состояние</mat-label>
          <mat-select [(ngModel)]="active" (ngModelChange)="load()">
            <mat-option value="">Все</mat-option>
            <mat-option value="true">Действующие</mat-option>
            <mat-option value="false">Деактивированные</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Поиск по коду или названию</mat-label>
          <input matInput [(ngModel)]="text" (keyup.enter)="load()" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="startCreate()">Новый тип</button>
      </mat-card-content>
    </mat-card>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="editing()">
      <mat-card-header>
        <mat-card-title>{{ update ? 'Правка типа ' + draft.code : 'Новый тип согласия' }}</mat-card-title>
      </mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Код</mat-label>
          <input matInput [(ngModel)]="draft.code" [disabled]="update" />
          <mat-hint *ngIf="update">Код не меняется: по нему тип связан с формами и согласиями.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Название</mat-label>
          <input matInput [(ngModel)]="draft.nameRu" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Категория</mat-label>
          <mat-select [(ngModel)]="draft.category">
            <mat-option *ngFor="let item of options()?.categories" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Каналы связи</mat-label>
          <mat-select [(ngModel)]="draft.channels" multiple>
            <mat-option *ngFor="let item of options()?.channels" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Срок по умолчанию</mat-label>
          <input matInput [(ngModel)]="draft.defaultValidity" placeholder="P1Y" />
          <mat-hint>Период ISO-8601: P1Y — год, P6M — полгода. Пусто — бессрочно.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Зависит от типа</mat-label>
          <mat-select [(ngModel)]="draft.dependsOnCode">
            <mat-option [value]="null">Не зависит</mat-option>
            <mat-option *ngFor="let row of rows()" [value]="row.code">{{ row.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-span-2">
          <mat-label>Описание</mat-label>
          <textarea matInput rows="2" [(ngModel)]="draft.description"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Порядок в списке</mat-label>
          <input matInput type="number" [(ngModel)]="draft.sortOrder" />
        </mat-form-field>
        <div class="ic-checks">
          <mat-checkbox [(ngModel)]="draft.requiresThirdParty">Требует указания третьего лица</mat-checkbox>
          <mat-checkbox [(ngModel)]="draft.businessSignificant">Влияет на бизнес-процессы</mat-checkbox>
        </div>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="editing.set(false)">Отмена</button>
        <button mat-flat-button color="primary" (click)="save()">Сохранить</button>
      </mat-card-actions>
    </mat-card>

    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="rows()" class="ic-table" *ngIf="rows().length">
        <ng-container matColumnDef="code">
          <th mat-header-cell *matHeaderCellDef>Код</th>
          <td mat-cell *matCellDef="let row">{{ row.code }}</td>
        </ng-container>
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Название</th>
          <td mat-cell *matCellDef="let row">
            {{ row.nameRu }}
            <span class="ic-badge danger" *ngIf="!row.active">деактивирован</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="category">
          <th mat-header-cell *matHeaderCellDef>Категория</th>
          <td mat-cell *matCellDef="let row">{{ row.categoryRu }}</td>
        </ng-container>
        <ng-container matColumnDef="validity">
          <th mat-header-cell *matHeaderCellDef>Срок</th>
          <td mat-cell *matCellDef="let row">{{ row.defaultValidity || 'бессрочно' }}</td>
        </ng-container>
        <ng-container matColumnDef="consents">
          <th mat-header-cell *matHeaderCellDef>Согласия</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-count ok" [attr.aria-label]="'Действующих: ' + row.consentsActive">{{
              row.consentsActive
            }}</span>
            <span class="ic-count warn" [attr.aria-label]="'Истекающих: ' + row.consentsExpiring">{{
              row.consentsExpiring
            }}</span>
            <span class="ic-count danger" [attr.aria-label]="'Отозванных: ' + row.consentsRevoked">{{
              row.consentsRevoked
            }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <button mat-button (click)="startEdit(row)">Править</button>
            <button mat-button color="warn" *ngIf="row.active" (click)="deactivate(row)">Деактивировать</button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!rows().length && !loading()">Типов, подходящих под фильтр, нет.</p>
    </mat-card>
  `,
})
export class CatalogTypesComponent {
  private readonly api = inject(ApiService);

  readonly rows = signal<TypeRow[]>([]);
  readonly options = signal<BuilderOptions | null>(null);
  readonly loading = signal(false);
  readonly editing = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  readonly columns = ['code', 'name', 'category', 'validity', 'consents', 'actions'];

  category = '';
  active = '';
  text = '';
  update = false;
  draft = this.emptyDraft();

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const filters: Record<string, string> = {};
    if (this.category) {
      filters['category'] = this.category;
    }
    if (this.active) {
      filters['active'] = this.active;
    }
    if (this.text.trim()) {
      filters['text'] = this.text.trim();
    }
    this.api.types(filters).subscribe((rows) => {
      this.rows.set(rows);
      this.loading.set(false);
    });
  }

  startCreate(): void {
    this.draft = this.emptyDraft();
    this.update = false;
    this.editing.set(true);
  }

  startEdit(row: TypeRow): void {
    this.draft = {
      code: row.code,
      nameRu: row.nameRu,
      description: row.description ?? '',
      category: row.category,
      channels: row.channels ?? [],
      requiresThirdParty: row.requiresThirdParty,
      defaultValidity: row.defaultValidity,
      dependsOnCode: row.dependsOnCode,
      businessSignificant: row.businessSignificant,
      sortOrder: row.sortOrder,
      update: true,
    };
    this.update = true;
    this.editing.set(true);
  }

  save(): void {
    this.error.set('');
    this.api.saveType({ ...this.draft, update: this.update }).subscribe({
      next: () => {
        this.message.set(this.update ? 'Тип согласия обновлён' : 'Тип согласия создан');
        this.editing.set(false);
        this.load();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Тип не сохранён: проверьте поля.'),
    });
  }

  deactivate(row: TypeRow): void {
    this.api.deactivateType(row.code).subscribe({
      next: (result) => {
        this.message.set(result.message);
        this.load();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Не удалось деактивировать тип.'),
    });
  }

  private emptyDraft() {
    return {
      code: '',
      nameRu: '',
      description: '',
      category: '',
      channels: [] as string[],
      requiresThirdParty: false,
      defaultValidity: '' as string | null,
      dependsOnCode: null as string | null,
      businessSignificant: true,
      sortOrder: 0,
      update: false,
    };
  }
}

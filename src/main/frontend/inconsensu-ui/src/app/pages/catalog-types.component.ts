import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, BuilderOptions, TypeRow } from '../api.service';
import { ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * UI-6: справочник типов согласий.
 *
 * Правка идёт на месте: панель раскрывается под таблицей и заполняется выбранной строкой. Код при
 * правке не меняется — по нему тип связан с формами и выданными согласиями (FR-1.1).
 *
 * Сервер отдаёт справочник целиком, без страниц, поэтому страницы нарезаются здесь же по массиву:
 * показываем ровно выбранный кусок, а не весь список под видом страницы (UI-0.8).
 */
@Component({
  selector: 'ic-catalog-types',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatDialogModule,
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
        <!-- Обычная ссылка, а не запрос из кода: файл забирает браузер по той же сессии (UI-0.3). -->
        <a mat-stroked-button [href]="exportUrl">Выгрузить в CSV</a>
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
          <mat-label>Срок по умолчанию, период вида P1Y</mat-label>
          <input matInput [(ngModel)]="draft.defaultValidity" />
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
        <button mat-stroked-button (click)="editing.set(false)">Отмена</button>
        <button mat-flat-button color="primary" (click)="save()">Сохранить</button>
      </mat-card-actions>
    </mat-card>

    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="pageRows()" class="ic-table" *ngIf="rows().length">
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
            <span class="ic-count ok" [attr.aria-label]="'Действующих: ' + row.consentsActive" [attr.title]="'Действующих: ' + row.consentsActive">{{
              row.consentsActive
            }}</span>
            <span class="ic-count warn" [attr.aria-label]="'Истекающих: ' + row.consentsExpiring" [attr.title]="'Истекающих: ' + row.consentsExpiring">{{
              row.consentsExpiring
            }}</span>
            <span class="ic-count danger" [attr.aria-label]="'Отозванных: ' + row.consentsRevoked" [attr.title]="'Отозванных: ' + row.consentsRevoked">{{
              row.consentsRevoked
            }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <button mat-stroked-button (click)="startEdit(row)">Править</button>
            <button mat-stroked-button color="warn" *ngIf="row.active" (click)="deactivate(row)">Деактивировать</button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>

      <div class="ic-empty" *ngIf="!rows().length && !loading()">
        {{ emptyHint() }}
        <div class="ic-actions ic-gap" *ngIf="filtersApplied()">
          <button mat-stroked-button (click)="reset()">Показать все типы</button>
        </div>
      </div>

      <mat-paginator
        *ngIf="rows().length"
        [length]="rows().length"
        [pageSize]="size()"
        [pageIndex]="page()"
        [pageSizeOptions]="[10, 20, 50]"
        (page)="turnPage($event)"
      ></mat-paginator>
    </mat-card>
  `,
})
export class CatalogTypesComponent {
  private readonly api = inject(ApiService);
  private readonly dialogs = inject(MatDialog);

  readonly rows = signal<TypeRow[]>([]);
  readonly options = signal<BuilderOptions | null>(null);
  readonly loading = signal(false);
  readonly editing = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  readonly page = signal(0);
  readonly size = signal(10);
  /** Строки выбранной страницы: показываем ровно их, а не весь список (UI-0.8). */
  readonly pageRows = computed(() => {
    const from = this.page() * this.size();
    return this.rows().slice(from, from + this.size());
  });

  readonly columns = ['code', 'name', 'category', 'validity', 'consents', 'actions'];
  readonly exportUrl = '/ui/api/catalog/export?part=TYPES';

  category = '';
  active = '';
  text = '';
  update = false;
  draft = this.emptyDraft();

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.load();
  }

  /** @param resetPage при смене отбора список другой, и оставаться на прежней странице бессмысленно */
  load(resetPage = true): void {
    this.loading.set(true);
    if (resetPage) {
      this.page.set(0);
    }
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
      const lastPage = Math.max(0, Math.ceil(rows.length / this.size()) - 1);
      if (this.page() > lastPage) {
        this.page.set(lastPage);
      }
      this.loading.set(false);
    });
  }

  turnPage(event: PageEvent): void {
    this.page.set(event.pageIndex);
    this.size.set(event.pageSize);
  }

  filtersApplied(): boolean {
    return !!(this.category || this.active || this.text.trim());
  }

  /** Пустая таблица объясняет, что делать дальше, а не просто сообщает о пустоте (UI-0.6). */
  emptyHint(): string {
    return this.filtersApplied()
      ? 'Под выбранный отбор не подошёл ни один тип. Снимите фильтры или измените запрос.'
      : 'Справочник пока пуст. Заведите первый тип кнопкой «Новый тип»: по типам собираются формы и выдаются согласия.';
  }

  reset(): void {
    this.category = '';
    this.active = '';
    this.text = '';
    this.load();
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
    this.dialogs
      .open(ConfirmDialogComponent, {
        data: {
          title: `Деактивировать тип «${row.nameRu}»?`,
          consequences: this.consequences(row),
          confirmLabel: 'Деактивировать',
          danger: true,
        },
      })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (!confirmed) {
          return;
        }
        this.error.set('');
        this.api.deactivateType(row.code).subscribe({
          next: (result) => {
            this.message.set(result.message);
            // Страница остаётся прежней: строка не исчезает, у неё лишь появляется отметка.
            this.load(false);
          },
          error: (failure) => this.error.set(failure?.error?.detail ?? 'Не удалось деактивировать тип.'),
        });
      });
  }

  /** Последствия названы числом: без него сотрудник соглашается не глядя (UI-0.6). */
  private consequences(row: TypeRow): string {
    const issued =
      row.consentsActive > 0
        ? `Сейчас по типу «${row.nameRu}» действует ${row.consentsActive} ` +
          `${this.plural(row.consentsActive, 'согласие', 'согласия', 'согласий')}. ` +
          'Выданные согласия останутся в силе до своего срока — деактивация их не гасит.'
        : `Действующих согласий по типу «${row.nameRu}» сейчас нет.`;
    const expiring =
      row.consentsExpiring > 0
        ? ` Скоро истекающих среди них — ${row.consentsExpiring}.`
        : '';
    return (
      `${issued}${expiring} Новые согласия по этому типу выдавать перестанут: он пропадёт из конструктора ` +
      'форм и из выбора при выдаче. Включить тип обратно интерфейс не умеет.'
    );
  }

  /** Русское число словами: 1 согласие, 2 согласия, 5 согласий. */
  private plural(count: number, one: string, few: string, many: string): string {
    const tens = count % 100;
    const units = count % 10;
    if (tens >= 11 && tens <= 14) {
      return many;
    }
    if (units === 1) {
      return one;
    }
    if (units >= 2 && units <= 4) {
      return few;
    }
    return many;
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

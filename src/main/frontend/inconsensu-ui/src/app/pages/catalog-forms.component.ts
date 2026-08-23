import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
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
 *
 * Отбор и страницы считает сервер: форм с пунктами и версиями бывает много, и нарезать их в браузере
 * значило бы возить весь каталог на каждый чих. Тип согласия и третье лицо ищутся по пунктам форм,
 * поэтому этих фильтров нет в самой таблице — они уходят запросом (UI-0.8).
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
    MatPaginatorModule,
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
        <mat-form-field appearance="outline">
          <mat-label>Тип согласия</mat-label>
          <mat-select [(ngModel)]="typeCode" (ngModelChange)="load()">
            <mat-option value="">Любой</mat-option>
            <mat-option *ngFor="let item of options()?.types" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Третье лицо</mat-label>
          <mat-select [(ngModel)]="thirdPartyId" (ngModelChange)="load()">
            <mat-option value="">Любое</mat-option>
            <mat-option *ngFor="let item of options()?.thirdParties" [value]="item.id">{{ item.name }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Поиск по коду или названию</mat-label>
          <input matInput [(ngModel)]="text" (keyup.enter)="load()" />
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="creating.set(true)">Новая форма</button>
        <button mat-button *ngIf="filtersApplied()" (click)="reset()">Сбросить отбор</button>
      </mat-card-content>

      <mat-card-content class="ic-filters">
        <span class="ic-muted">Выгрузка:</span>
        <!-- Обычные ссылки, а не запрос из кода: файл забирает браузер по той же сессии (UI-0.3). -->
        <a mat-stroked-button [href]="formsExportUrl">Формы в CSV</a>
        <a mat-stroked-button [href]="itemsExportUrl">Пункты форм в CSV</a>
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
        <ng-container matColumnDef="updated">
          <th mat-header-cell *matHeaderCellDef>Обновлено</th>
          <!-- Дату форматирует сервер: значение приходит уже в виде «22.08.2026 14:59», и конвейер date
               на такой строке падает с NG02100, обрушивая весь экран. -->
          <td mat-cell *matCellDef="let row">{{ row.updatedAt || '—' }}</td>
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

      <div class="ic-empty" *ngIf="!page()?.rows?.length && !loading()">
        {{ emptyHint() }}
        <div class="ic-actions ic-gap" *ngIf="filtersApplied()">
          <button mat-stroked-button (click)="reset()">Показать все формы</button>
        </div>
      </div>

      <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
      <mat-paginator
        *ngIf="page()?.rows?.length"
        [length]="page()?.total ?? 0"
        [pageSize]="pageSize"
        [pageIndex]="pageIndex"
        [pageSizeOptions]="[20, 50, 100]"
        (page)="turnPage($event)"
      ></mat-paginator>
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

  readonly columns = ['code', 'title', 'version', 'status', 'updated', 'actions'];
  readonly formsExportUrl = '/ui/api/catalog/export?part=FORMS';
  readonly itemsExportUrl = '/ui/api/catalog/export?part=ITEMS';

  status = '';
  source = '';
  typeCode = '';
  thirdPartyId = '';
  text = '';
  pageIndex = 0;
  pageSize = 20;
  newCode = '';
  newTitle = '';

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.load();
  }

  /** @param resetPage при смене отбора список другой, и оставаться на прежней странице бессмысленно */
  load(resetPage = true): void {
    this.loading.set(true);
    if (resetPage) {
      this.pageIndex = 0;
    }
    const filters: Record<string, string> = {};
    if (this.status) {
      filters['status'] = this.status;
    }
    if (this.source) {
      filters['source'] = this.source;
    }
    if (this.typeCode) {
      filters['typeCode'] = this.typeCode;
    }
    if (this.thirdPartyId) {
      filters['thirdPartyId'] = this.thirdPartyId;
    }
    if (this.text.trim()) {
      filters['text'] = this.text.trim();
    }
    filters['page'] = String(this.pageIndex);
    filters['size'] = String(this.pageSize);
    this.api.forms(filters).subscribe({
      next: (found) => {
        // Форм стало меньше, чем было страниц: возвращаемся на последнюю, а не показываем пустоту.
        const lastPage = Math.max(0, Math.ceil(found.total / this.pageSize) - 1);
        if (this.pageIndex > lastPage) {
          this.pageIndex = lastPage;
          this.load(false);
          return;
        }
        this.page.set(found);
        this.loading.set(false);
        // Список пришёл — прежняя жалоба на сервер больше не про этот экран.
        this.error.set('');
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Список форм не загрузился. Обновите страницу или повторите отбор.');
      },
    });
  }

  turnPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.load(false);
  }

  filtersApplied(): boolean {
    return !!(this.status || this.source || this.typeCode || this.thirdPartyId || this.text.trim());
  }

  /** Пустая таблица объясняет, что делать дальше, а не просто сообщает о пустоте (UI-0.6). */
  emptyHint(): string {
    return this.filtersApplied()
      ? 'Под выбранный отбор не подошла ни одна форма. Снимите фильтры или измените запрос.'
      : 'Форм пока нет. Заведите первую кнопкой «Новая форма»: черновик откроется в конструкторе, ' +
          'а после согласования по нему начнут выдавать согласия.';
  }

  reset(): void {
    this.status = '';
    this.source = '';
    this.typeCode = '';
    this.thirdPartyId = '';
    this.text = '';
    this.error.set('');
    this.load();
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

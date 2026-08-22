import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, SubjectFilters, SubjectRow } from '../api.service';

/**
 * UI-3: поиск клиента и список по отбору.
 *
 * Тип запроса определяется сервером: «+» или цифры — телефон, «@» — почта, буквы — ФИО, иначе внешний
 * идентификатор. Запрос уходит POST-ом, потому что телефону, почте и ФИО нельзя попадать в адрес
 * (UI-0.10). Отбор — наоборот, GET-ом: в нём только коды справочников, и ссылку можно сохранить.
 */
@Component({
  selector: 'ic-subjects',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatExpansionModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Клиенты</h1>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Телефон, email, ФИО или ID клиента</mat-label>
          <input matInput [(ngModel)]="query" (keyup.enter)="search()" />
          <mat-hint>
            Телефон — начиная с «+» или цифр; email — с «&#64;»; ФИО — не менее трёх букв; иначе ищем
            по внешнему идентификатору
          </mat-hint>
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="search()">Найти</button>
        <button mat-button (click)="reset()">Сбросить</button>
        <button mat-stroked-button (click)="creating.set(!creating())">Завести клиента</button>
      </mat-card-content>

      <!-- Готовые отборы рядом с поиском: это один и тот же список, а не разные экраны. -->
      <mat-card-content class="ic-filters">
        <span class="ic-muted">Показать:</span>
        <button mat-stroked-button [class.ic-active-chip]="status === 'ACTIVE'" (click)="quick('status', 'ACTIVE')">
          с действующими согласиями
        </button>
        <button mat-stroked-button [class.ic-active-chip]="status === 'EXPIRING'" (click)="quick('status', 'EXPIRING')">
          у кого заканчиваются
        </button>
        <button mat-stroked-button [class.ic-active-chip]="revokedOnly === 'true'" (click)="quick('revokedOnly', 'true')">
          с отозванными
        </button>
      </mat-card-content>

      <mat-expansion-panel class="ic-block">
        <mat-expansion-panel-header>
          <mat-panel-title>Расширенный отбор</mat-panel-title>
          <mat-panel-description>{{ filtersApplied() ? 'применён' : 'не задан' }}</mat-panel-description>
        </mat-expansion-panel-header>
        <div class="ic-form-grid">
          <mat-form-field appearance="outline">
            <mat-label>Состояние согласия</mat-label>
            <mat-select [(ngModel)]="status" (ngModelChange)="applyFilters()">
              <mat-option value="">Любое</mat-option>
              <mat-option *ngFor="let item of filters()?.statuses" [value]="item.code">{{ item.nameRu }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Тип согласия</mat-label>
            <mat-select [(ngModel)]="consentTypeId" (ngModelChange)="applyFilters()">
              <mat-option value="">Любой</mat-option>
              <mat-option *ngFor="let item of filters()?.consentTypes" [value]="item.code">{{ item.nameRu }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Третье лицо</mat-label>
            <mat-select [(ngModel)]="thirdPartyId" (ngModelChange)="applyFilters()">
              <mat-option value="">Любое</mat-option>
              <mat-option *ngFor="let item of filters()?.thirdParties" [value]="item.code">{{ item.nameRu }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Источник согласия</mat-label>
            <mat-select [(ngModel)]="source" (ngModelChange)="applyFilters()">
              <mat-option value="">Любой</mat-option>
              <mat-option *ngFor="let item of filters()?.sources" [value]="item.code">{{ item.nameRu }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Заканчивается до</mat-label>
            <input matInput type="date" [(ngModel)]="expiringBefore" (change)="applyFilters()" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Только отозванные</mat-label>
            <mat-select [(ngModel)]="revokedOnly" (ngModelChange)="applyFilters()">
              <mat-option value="">Нет</mat-option>
              <mat-option value="true">Да</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
      </mat-expansion-panel>
    </mat-card>

    <mat-card class="ic-block" *ngIf="creating()">
      <mat-card-header>
        <mat-card-title>Клиент</mat-card-title>
        <mat-card-subtitle>
          Тот же внешний идентификатор правит запись, а не создаёт вторую
        </mat-card-subtitle>
      </mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Внешний идентификатор</mat-label>
          <input matInput [(ngModel)]="draft.externalId" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Фамилия</mat-label>
          <input matInput [(ngModel)]="draft.lastName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Имя</mat-label>
          <input matInput [(ngModel)]="draft.firstName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Отчество</mat-label>
          <input matInput [(ngModel)]="draft.middleName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Дата рождения</mat-label>
          <input matInput type="date" [(ngModel)]="draft.birthDate" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Телефон</mat-label>
          <input matInput [(ngModel)]="draft.phone" placeholder="+7 999 000-00-00" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Электронная почта</mat-label>
          <input matInput [(ngModel)]="draft.email" />
        </mat-form-field>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="creating.set(false)">Отмена</button>
        <button mat-flat-button color="primary" (click)="saveSubject()">Сохранить</button>
      </mat-card-actions>
    </mat-card>

    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>


    <mat-card class="ic-block">
      <table
        mat-table
        matSort
        [dataSource]="rows()"
        class="ic-table"
        (matSortChange)="sortBy($event)"
        *ngIf="rows().length"
      >
        <ng-container matColumnDef="fullName">
          <th mat-header-cell mat-sort-header *matHeaderCellDef>Клиент</th>
          <td mat-cell *matCellDef="let row">
            <a [routerLink]="['/subjects', row.id]">{{ row.fullName }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="externalId">
          <th mat-header-cell mat-sort-header *matHeaderCellDef>Идентификатор</th>
          <td mat-cell *matCellDef="let row">{{ row.externalId }}</td>
        </ng-container>
        <ng-container matColumnDef="phone">
          <th mat-header-cell *matHeaderCellDef>Телефон</th>
          <td mat-cell *matCellDef="let row">{{ row.phone || '—' }}</td>
        </ng-container>
        <ng-container matColumnDef="email">
          <th mat-header-cell *matHeaderCellDef>Email</th>
          <td mat-cell *matCellDef="let row">{{ row.email || '—' }}</td>
        </ng-container>
        <ng-container matColumnDef="consents">
          <th mat-header-cell *matHeaderCellDef>Согласия</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-count ok" [attr.aria-label]="'Действующих согласий: ' + row.active">{{ row.active }}</span>
            <span class="ic-count warn" [attr.aria-label]="'Истекающих согласий: ' + row.expiring">{{
              row.expiring
            }}</span>
            <span class="ic-count danger" [attr.aria-label]="'Отозванных согласий: ' + row.revoked">{{
              row.revoked
            }}</span>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!loading() && !rows().length">
        {{ hint() || 'Ничего не найдено. Проверьте формат номера телефона или введите не менее трёх букв фамилии.' }}
      </p>
      <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
      <mat-paginator
        *ngIf="!searchMode()"
        [length]="total()"
        [pageSize]="size"
        [pageIndex]="page"
        [pageSizeOptions]="[20, 50, 100]"
        (page)="turnPage($event)"
      ></mat-paginator>
      <p class="ic-muted" *ngIf="searchMode() && total() >= 50">
        Показаны первые {{ rows().length }} совпадений. Уточните запрос.
      </p>
    </mat-card>
  `,
})
export class SubjectsComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly rows = signal<SubjectRow[]>([]);
  readonly filters = signal<SubjectFilters | null>(null);
  readonly total = signal(0);
  readonly hint = signal('');
  readonly loading = signal(false);
  /** Режим поиска: результат запроса нельзя листать — он и так ограничен совпадениями. */
  readonly searchMode = signal(false);
  readonly creating = signal(false);
  readonly error = signal('');

  readonly columns = ['fullName', 'externalId', 'phone', 'email', 'consents'];

  query = '';
  status = '';
  consentTypeId = '';
  thirdPartyId = '';
  source = '';
  expiringBefore = '';
  revokedOnly = '';
  sort = '';
  direction = '';
  page = 0;
  size = 20;

  draft = this.emptyDraft();

  constructor() {
    this.api.subjectFilters().subscribe((filters) => this.filters.set(filters));
    // Плитка главной открывает список сразу с отбором: параметры приходят в адресе (UI-2).
    this.route.queryParamMap.subscribe((params) => {
      this.status = params.get('status') ?? '';
      this.consentTypeId = params.get('consentTypeId') ?? '';
      this.thirdPartyId = params.get('thirdPartyId') ?? '';
      this.source = params.get('source') ?? '';
      this.expiringBefore = params.get('expiringBefore') ?? '';
      this.revokedOnly = params.get('revokedOnly') ?? '';
      // Экран открывается сразу со списком: у сотрудника «Клиенты» — это список, а поиск сужает его.
      if (!this.filtersApplied()) {
        this.status = 'ACTIVE';
      }
      this.load();
    });
  }

  filtersApplied(): boolean {
    return !!(this.status || this.consentTypeId || this.thirdPartyId || this.source || this.expiringBefore || this.revokedOnly);
  }

  /** Отбор держится в адресе: так ссылку на список можно сохранить и переслать (UI-0.8). */
  applyFilters(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        status: this.status || null,
        consentTypeId: this.consentTypeId || null,
        thirdPartyId: this.thirdPartyId || null,
        source: this.source || null,
        expiringBefore: this.expiringBefore || null,
        revokedOnly: this.revokedOnly || null,
      },
    });
  }

  load(): void {
    const params: Record<string, string> = {};
    if (this.status) {
      params['status'] = this.status;
    }
    if (this.consentTypeId) {
      params['consentTypeId'] = this.consentTypeId;
    }
    if (this.thirdPartyId) {
      params['thirdPartyId'] = this.thirdPartyId;
    }
    if (this.source) {
      params['source'] = this.source;
    }
    if (this.expiringBefore) {
      params['expiringBefore'] = this.expiringBefore;
    }
    if (this.revokedOnly) {
      params['revokedOnly'] = this.revokedOnly;
    }
    if (this.sort) {
      params['sort'] = this.sort;
      params['direction'] = this.direction;
    }
    params['page'] = String(this.page);
    params['size'] = String(this.size);
    this.loading.set(true);
    this.searchMode.set(false);
    this.api.listSubjects(params).subscribe((page) => {
      this.rows.set(page.rows);
      this.total.set(page.total);
      this.hint.set(page.hint ?? '');
      this.loading.set(false);
    });
  }

  search(): void {
    if (!this.query.trim()) {
      return;
    }
    this.loading.set(true);
    this.searchMode.set(true);
    this.hint.set('');
    this.api.searchSubjects(this.query).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.total.set(rows.length);
        this.loading.set(false);
      },
      error: (failure) => {
        this.loading.set(false);
        this.rows.set([]);
        // UI-3: подсказка о формате запроса — часть экрана, а не отказ сервера.
        this.hint.set(failure?.error?.detail ?? 'Запрос не распознан.');
      },
    });
  }

  sortBy(sort: Sort): void {
    this.sort = sort.direction ? sort.active : '';
    this.direction = sort.direction;
    this.load();
  }

  saveSubject(): void {
    this.error.set('');
    this.api.saveSubject(this.draft).subscribe({
      next: (saved) => {
        this.creating.set(false);
        this.draft = this.emptyDraft();
        this.router.navigate(['/subjects', saved.id]);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Клиент не сохранён: проверьте поля.'),
    });
  }

  /** Готовый отбор с пустого экрана: тот же адрес, что и у плитки главной, — ссылку можно сохранить. */
  quick(name: string, value: string): void {
    this.status = name === 'status' ? value : '';
    this.revokedOnly = name === 'revokedOnly' ? value : '';
    this.consentTypeId = '';
    this.thirdPartyId = '';
    this.source = '';
    this.expiringBefore = '';
    this.applyFilters();
  }

  /** Сброс возвращает не пустой экран, а список по отбору: поиск и список — одно и то же место. */
  reset(): void {
    this.query = '';
    this.hint.set('');
    this.page = 0;
    this.load();
  }

  turnPage(event: PageEvent): void {
    this.page = event.pageIndex;
    this.size = event.pageSize;
    this.load();
  }

  private emptyDraft() {
    return { externalId: '', lastName: '', firstName: '', middleName: '', birthDate: '', phone: '', email: '' };
  }
}

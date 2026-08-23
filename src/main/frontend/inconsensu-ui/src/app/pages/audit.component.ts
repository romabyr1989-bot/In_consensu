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
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { AccessRow, ApiService, AuditEventRow, DictionaryItem, VerificationRow } from '../api.service';

/** Справочники экрана: типы объектов сервер собирает по самому журналу, типы событий — из перечисления. */
interface AuditOptions {
  aggregateTypes: DictionaryItem[];
  eventTypes: DictionaryItem[];
}

/** Идентификатор клиента: 36 знаков с дефисами. Кривое значение сервер встретил бы отказом 400. */
const SUBJECT_ID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

const SUBJECT_ID_HELP =
  'Идентификатор клиента выглядит иначе: это длинный номер из 36 знаков, например ' +
  '0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0. Его видно в адресе карточки клиента. Отбор не применён.';

/**
 * UI-15: журнал аудита.
 *
 * Три вкладки одного раздела: события системы, доступ к персональным данным и проверки целостности
 * цепочки. Проверка запускается в фоне — список сразу показывает запись «выполняется» (FR-10.3).
 *
 * Отбор по клиенту идёт идентификатором, а не фамилией: сам идентификатор — не персональные данные, и
 * его можно держать в отборе и в адресе, в отличие от ФИО и контактов (UI-0.10).
 *
 * Содержимое события показывается карточкой над таблицей, а не всплывающим окном: оно бывает длинным,
 * и рядом с ним удобно листать соседние строки. Так же устроен просмотр письма в журнале отправок.
 */
@Component({
  selector: 'ic-audit',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Аудит</h1>

    <mat-progress-bar mode="indeterminate" *ngIf="loadingEvents() || loadingAccess()"></mat-progress-bar>

    <mat-tab-group class="ic-block">
      <mat-tab label="События">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline">
                <mat-label>Тип объекта</mat-label>
                <mat-select [(ngModel)]="aggregateType" (ngModelChange)="applyEventFilters()">
                  <mat-option value="">Любой</mat-option>
                  <mat-option *ngFor="let item of options()?.aggregateTypes" [value]="item.code">
                    {{ item.nameRu }}
                  </mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Тип события</mat-label>
                <mat-select [(ngModel)]="eventType" (ngModelChange)="applyEventFilters()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of options()?.eventTypes" [value]="item.code">
                    {{ item.nameRu }}
                  </mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Кто действовал</mat-label>
                <input matInput [(ngModel)]="actorId" (keyup.enter)="applyEventFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Идентификатор клиента</mat-label>
                <input matInput [(ngModel)]="subjectId" (keyup.enter)="applyEventFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="from" (change)="applyEventFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="to" (change)="applyEventFilters()" />
              </mat-form-field>
              <button mat-button *ngIf="eventsFiltered()" (click)="resetEventFilters()">Сбросить отбор</button>
            </mat-card-content>
          </mat-card>

          <div class="ic-danger ic-gap" *ngIf="eventsError()">{{ eventsError() }}</div>

          <mat-card class="ic-block" *ngIf="openedEvent() as event">
            <mat-card-header>
              <mat-card-title>{{ event.eventTypeRu }}</mat-card-title>
              <mat-card-subtitle>{{ event.occurredAt }} · {{ event.actor || 'система' }}</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <dl class="ic-facts">
                <dt>Объект</dt>
                <dd>{{ event.aggregateType }}</dd>
                <dt>Идентификатор объекта</dt>
                <dd>{{ event.aggregateId || '—' }}</dd>
              </dl>
              <pre class="ic-form-text">{{ openedPayload() }}</pre>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-button (click)="closeEvent()">Закрыть</button>
            </mat-card-actions>
          </mat-card>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="events()" class="ic-table" *ngIf="events().length">
              <ng-container matColumnDef="occurredAt">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let row">{{ row.occurredAt }}</td>
              </ng-container>
              <ng-container matColumnDef="aggregate">
                <th mat-header-cell *matHeaderCellDef>Объект</th>
                <td mat-cell *matCellDef="let row">{{ row.aggregateTypeRu || row.aggregateType }}</td>
              </ng-container>
              <ng-container matColumnDef="eventType">
                <th mat-header-cell *matHeaderCellDef>Событие</th>
                <td mat-cell *matCellDef="let row">{{ row.eventTypeRu }}</td>
              </ng-container>
              <ng-container matColumnDef="actor">
                <th mat-header-cell *matHeaderCellDef>Кто</th>
                <td mat-cell *matCellDef="let row">{{ row.actor || 'система' }}</td>
              </ng-container>
              <ng-container matColumnDef="payload">
                <th mat-header-cell *matHeaderCellDef>Содержимое</th>
                <td mat-cell *matCellDef="let row">
                  <button mat-button (click)="toggleEvent(row)">
                    {{ openedEvent() === row ? 'Скрыть' : 'Показать' }}
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="eventColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: eventColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!events().length && eventsFiltered()">
              Под этот отбор событий нет. Снимите часть условий или расширьте период — например,
              поставьте дату «с» на месяц раньше.
            </p>
            <p class="ic-empty" *ngIf="!events().length && !eventsFiltered()">
              Журнал пуст. Записи появятся сами, как только в системе что-то произойдёт: выдадут
              согласие, отзовут его, опубликуют форму.
            </p>
            <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
            <mat-paginator
              [length]="eventsTotal()"
              [pageSize]="eventsSize"
              [pageIndex]="eventsPage"
              [pageSizeOptions]="[20, 50, 100]"
              (page)="turnEventsPage($event)"
            ></mat-paginator>
          </mat-card>
        </div>
      </mat-tab>

      <mat-tab label="Доступ к персональным данным">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline" class="ic-grow">
                <mat-label>Обращение (адрес операции)</mat-label>
                <input matInput [(ngModel)]="endpoint" (keyup.enter)="applyAccessFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Идентификатор клиента</mat-label>
                <input matInput [(ngModel)]="accessSubjectId" (keyup.enter)="applyAccessFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="accessFrom" (change)="applyAccessFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="accessTo" (change)="applyAccessFilters()" />
              </mat-form-field>
              <button mat-button *ngIf="accessFiltered()" (click)="resetAccessFilters()">Сбросить отбор</button>
            </mat-card-content>
          </mat-card>

          <div class="ic-danger ic-gap" *ngIf="accessError()">{{ accessError() }}</div>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="access()" class="ic-table" *ngIf="access().length">
              <ng-container matColumnDef="occurredAt">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let row">{{ row.occurredAt }}</td>
              </ng-container>
              <ng-container matColumnDef="user">
                <th mat-header-cell *matHeaderCellDef>Кто смотрел</th>
                <td mat-cell *matCellDef="let row">{{ row.user }}</td>
              </ng-container>
              <ng-container matColumnDef="endpoint">
                <th mat-header-cell *matHeaderCellDef>Обращение</th>
                <td mat-cell *matCellDef="let row">{{ row.endpoint }}</td>
              </ng-container>
              <ng-container matColumnDef="subject">
                <th mat-header-cell *matHeaderCellDef>Чьи данные</th>
                <td mat-cell *matCellDef="let row">
                  <a *ngIf="row.subjectId" [routerLink]="['/subjects', row.subjectId]">{{ row.subjectId }}</a>
                  <span *ngIf="!row.subjectId">—</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="requestId">
                <th mat-header-cell *matHeaderCellDef>Номер запроса</th>
                <td mat-cell *matCellDef="let row">{{ row.requestId }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="accessColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: accessColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!access().length && accessFiltered()">
              Под этот отбор обращений нет. Снимите часть условий или расширьте период — например,
              поставьте дату «с» на месяц раньше.
            </p>
            <p class="ic-empty" *ngIf="!access().length && !accessFiltered()">
              Обращений к персональным данным ещё не было. Строка появляется, когда сотрудник
              раскрывает телефон или почту в карточке клиента.
            </p>
            <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
            <mat-paginator
              [length]="accessTotal()"
              [pageSize]="accessSize"
              [pageIndex]="accessPage"
              [pageSizeOptions]="[20, 50, 100]"
              (page)="turnAccessPage($event)"
            ></mat-paginator>
          </mat-card>
        </div>
      </mat-tab>

      <mat-tab label="Целостность">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content>
              <button mat-flat-button color="primary" (click)="verify()">Запустить проверку</button>
            </mat-card-content>
          </mat-card>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="verifications()" class="ic-table" *ngIf="verifications().length">
              <ng-container matColumnDef="startedAt">
                <th mat-header-cell *matHeaderCellDef>Начата</th>
                <td mat-cell *matCellDef="let row">{{ row.startedAt }}</td>
              </ng-container>
              <ng-container matColumnDef="startedBy">
                <th mat-header-cell *matHeaderCellDef>Кто запустил</th>
                <td mat-cell *matCellDef="let row">{{ row.startedBy }}</td>
              </ng-container>
              <ng-container matColumnDef="result">
                <th mat-header-cell *matHeaderCellDef>Результат</th>
                <td mat-cell *matCellDef="let row">
                  <span class="ic-badge" [class]="'ic-badge ' + badge(row)">{{ resultOf(row) }}</span>
                  <span class="ic-muted" *ngIf="row.error">{{ row.error }}</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="counts">
                <th mat-header-cell *matHeaderCellDef>Проверено</th>
                <td mat-cell *matCellDef="let row">
                  событий {{ row.eventsChecked }} · объектов {{ row.aggregatesChecked }} ·
                  якорей {{ row.anchorsChecked }}
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="verificationColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: verificationColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!verifications().length">
              Проверок ещё не было. Нажмите «Запустить проверку» — она пересчитает цепочку и покажет,
              цела ли она.
            </p>
          </mat-card>
        </div>
      </mat-tab>
    </mat-tab-group>
  `,
})
export class AuditComponent {
  private readonly api = inject(ApiService);

  readonly events = signal<AuditEventRow[]>([]);
  readonly eventsTotal = signal(0);
  readonly access = signal<AccessRow[]>([]);
  readonly accessTotal = signal(0);
  readonly verifications = signal<VerificationRow[]>([]);
  readonly options = signal<AuditOptions | null>(null);
  /** Раскрытая строка и её содержимое: разбираем содержимое один раз, а не на каждой перерисовке. */
  readonly openedEvent = signal<AuditEventRow | null>(null);
  readonly openedPayload = signal('');
  readonly eventsError = signal('');
  readonly accessError = signal('');
  readonly loadingEvents = signal(false);
  readonly loadingAccess = signal(false);

  readonly eventColumns = ['occurredAt', 'aggregate', 'eventType', 'actor', 'payload'];
  readonly accessColumns = ['occurredAt', 'user', 'endpoint', 'subject', 'requestId'];
  readonly verificationColumns = ['startedAt', 'startedBy', 'result', 'counts'];

  aggregateType = '';
  eventType = '';
  actorId = '';
  subjectId = '';
  from = '';
  to = '';
  eventsPage = 0;
  eventsSize = 20;

  endpoint = '';
  accessSubjectId = '';
  accessFrom = '';
  accessTo = '';
  accessPage = 0;
  accessSize = 20;

  constructor() {
    this.api.auditOptions().subscribe((options) => this.options.set(options));
    this.loadEvents();
    this.loadAccess();
    this.api.verifications().subscribe((rows) => this.verifications.set(rows));
  }

  loadEvents(): void {
    const subject = this.subjectId.trim();
    if (subject && !SUBJECT_ID_PATTERN.test(subject)) {
      this.eventsError.set(SUBJECT_ID_HELP);
      return;
    }
    this.eventsError.set('');
    const filters: Record<string, string> = {};
    if (this.aggregateType) {
      filters['aggregateType'] = this.aggregateType;
    }
    if (this.eventType) {
      filters['eventType'] = this.eventType;
    }
    if (this.actorId.trim()) {
      filters['actorId'] = this.actorId.trim();
    }
    if (subject) {
      filters['subjectId'] = subject;
    }
    if (this.from) {
      filters['from'] = this.from;
    }
    if (this.to) {
      filters['to'] = this.to;
    }
    filters['page'] = String(this.eventsPage);
    filters['size'] = String(this.eventsSize);
    this.loadingEvents.set(true);
    this.api.auditEvents(filters).subscribe({
      next: (page) => {
        this.events.set(page.rows);
        this.eventsTotal.set(page.total);
        // Раскрытая строка осталась на прежней странице — держать её содержимое рядом с новым списком незачем.
        this.closeEvent();
        this.loadingEvents.set(false);
      },
      error: () => {
        this.loadingEvents.set(false);
        this.eventsError.set('Журнал не загрузился. Обновите страницу и попробуйте снова.');
      },
    });
  }

  eventsFiltered(): boolean {
    return !!(this.aggregateType || this.eventType || this.actorId.trim() || this.subjectId.trim() || this.from || this.to);
  }

  /** Новый отбор — всегда с первой страницы: иначе список открывается пустым посреди результатов. */
  applyEventFilters(): void {
    this.eventsPage = 0;
    this.loadEvents();
  }

  resetEventFilters(): void {
    this.aggregateType = '';
    this.eventType = '';
    this.actorId = '';
    this.subjectId = '';
    this.from = '';
    this.to = '';
    this.applyEventFilters();
  }

  turnEventsPage(event: PageEvent): void {
    this.eventsPage = event.pageIndex;
    this.eventsSize = event.pageSize;
    this.loadEvents();
  }

  toggleEvent(row: AuditEventRow): void {
    if (this.openedEvent() === row) {
      this.closeEvent();
      return;
    }
    this.openedEvent.set(row);
    this.openedPayload.set(readable(row.payload));
  }

  closeEvent(): void {
    this.openedEvent.set(null);
    this.openedPayload.set('');
  }

  loadAccess(): void {
    const subject = this.accessSubjectId.trim();
    if (subject && !SUBJECT_ID_PATTERN.test(subject)) {
      this.accessError.set(SUBJECT_ID_HELP);
      return;
    }
    this.accessError.set('');
    const filters: Record<string, string> = {};
    if (this.endpoint.trim()) {
      filters['endpoint'] = this.endpoint.trim();
    }
    if (subject) {
      filters['subjectId'] = subject;
    }
    if (this.accessFrom) {
      filters['from'] = this.accessFrom;
    }
    if (this.accessTo) {
      filters['to'] = this.accessTo;
    }
    filters['page'] = String(this.accessPage);
    filters['size'] = String(this.accessSize);
    this.loadingAccess.set(true);
    this.api.accessLog(filters).subscribe({
      next: (page) => {
        this.access.set(page.rows);
        this.accessTotal.set(page.total);
        this.loadingAccess.set(false);
      },
      error: () => {
        this.loadingAccess.set(false);
        this.accessError.set('Журнал не загрузился. Обновите страницу и попробуйте снова.');
      },
    });
  }

  accessFiltered(): boolean {
    return !!(this.endpoint.trim() || this.accessSubjectId.trim() || this.accessFrom || this.accessTo);
  }

  applyAccessFilters(): void {
    this.accessPage = 0;
    this.loadAccess();
  }

  resetAccessFilters(): void {
    this.endpoint = '';
    this.accessSubjectId = '';
    this.accessFrom = '';
    this.accessTo = '';
    this.applyAccessFilters();
  }

  turnAccessPage(event: PageEvent): void {
    this.accessPage = event.pageIndex;
    this.accessSize = event.pageSize;
    this.loadAccess();
  }

  verify(): void {
    this.api.startVerification().subscribe((rows) => this.verifications.set(rows));
  }

  resultOf(row: VerificationRow): string {
    if (row.status === 'RUNNING' || row.status === 'PENDING') {
      return 'выполняется';
    }
    if (row.status === 'FAILED') {
      return 'не завершилась';
    }
    return row.integrity === 'OK' ? 'цепочка цела' : 'цепочка нарушена';
  }

  badge(row: VerificationRow): string {
    if (row.status === 'RUNNING' || row.status === 'PENDING') {
      return 'warn';
    }
    return row.status === 'COMPLETED' && row.integrity === 'OK' ? 'ok' : 'danger';
  }
}

/** Содержимое хранится строкой JSON: с отступами его читают глазами, а нечитаемое показываем как есть. */
function readable(payload: string): string {
  if (!payload || !payload.trim()) {
    return 'Содержимого нет: событие описано одним заголовком.';
  }
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

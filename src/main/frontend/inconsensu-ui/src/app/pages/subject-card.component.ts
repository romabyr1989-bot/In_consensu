import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, ConsentDossier, DictionaryItem, HistoryFeed, SubjectCard } from '../api.service';
import { RevokeDialogComponent } from './revoke-dialog.component';

/** Что диалог получает при открытии: согласие и его название для подзаголовка. */
export interface ConsentTextData {
  consentId: string;
  consentTitle: string;
}

/**
 * UI-4: текст, по которому дано согласие.
 *
 * Открывается прямо из строки согласия: на звонке нужен сам текст, а не переход в досье и обратно.
 * Рядом с текстом стоят версия формы и контрольная сумма — по ним сверяют, ту ли редакцию подписал
 * клиент.
 */
@Component({
  selector: 'ic-consent-text-dialog',
  standalone: true,
  imports: [CommonModule, RouterLink, MatDialogModule, MatButtonModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>Текст согласия</h2>
    <mat-dialog-content class="ic-dialog">
      <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>
      <p *ngIf="data.consentTitle"><b>{{ data.consentTitle }}</b></p>

      <ng-container *ngIf="dossier() as row">
        <div class="ic-subtitle">
          {{ row.formTitle || 'Форма не указана' }}
          <span *ngIf="row.formVersion"> · версия {{ row.formVersion }}</span>
        </div>
        <pre class="ic-form-text ic-gap" *ngIf="row.formText">{{ row.formText }}</pre>
        <p class="ic-empty" *ngIf="!row.formText">
          Текст не сохранён: согласие получено до перехода на печатные формы. Сверить редакцию можно
          по контрольной сумме, подробности — в досье согласия.
        </p>
        <div class="ic-checksum">Контрольная сумма: {{ row.storedChecksum || 'не сохранена' }}</div>
      </ng-container>

      <p class="ic-danger" *ngIf="error()">{{ error() }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <a mat-stroked-button [routerLink]="['/consents', data.consentId]" mat-dialog-close>Открыть досье</a>
      <button mat-flat-button color="primary" mat-dialog-close>Закрыть</button>
    </mat-dialog-actions>
  `,
})
export class ConsentTextDialogComponent {
  private readonly api = inject(ApiService);
  readonly data = inject<ConsentTextData>(MAT_DIALOG_DATA);

  readonly dossier = signal<ConsentDossier | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor() {
    this.api.dossier(this.data.consentId).subscribe({
      next: (row) => {
        this.dossier.set(row);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        // UI-0.9: называем, что именно не вышло, а не «что-то пошло не так».
        this.error.set('Текст согласия получить не удалось. Он остаётся доступен в досье согласия.');
      },
    });
  }
}

/**
 * UI-4: карточка клиента.
 *
 * Четыре вкладки макета: согласия, каналы связи, передачи третьим лицам и лента событий. Контакты
 * показываются замаскированными, раскрытие — отдельное действие с записью в журнал доступа (UI-0.10).
 * Карточку целиком собирает в PDF сервер, из строки согласия открывается текст, по которому оно дано.
 */
@Component({
  selector: 'ic-subject-card',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <ng-container *ngIf="card() as subject">
      <div class="ic-card-head">
        <div>
          <h1 class="ic-title">{{ subject.fullName }}</h1>
          <div class="ic-subtitle">
            Идентификатор: {{ subject.externalId || '—' }}
            <span *ngIf="subject.birthDate"> · дата рождения: {{ subject.birthDate }}</span>
          </div>
        </div>
        <div class="ic-actions">
          <!-- Имя файла задаёт сервер: ФИО не попадает ни в адрес, ни в имя файла (UI-0.10). -->
          <a mat-stroked-button [href]="pdfUrl()">Скачать карточку в PDF</a>
          <button mat-flat-button color="warn" *ngIf="subject.mayRevoke" (click)="openRevoke()">
            Отозвать согласие
          </button>
        </div>
      </div>

      <div class="ic-note" *ngIf="message()">{{ message() }}</div>

      <mat-card class="ic-block">
        <mat-card-content>
          <p class="ic-summary">{{ subject.summary }}</p>
          <div class="ic-contacts">
            <div class="ic-contact" *ngFor="let contact of subject.contacts">
              <span class="ic-contact-type">{{ contact.typeRu }}</span>
              <span>{{ contact.value }}</span>
              <button
                mat-stroked-button
                *ngIf="contact.masked && subject.mayReveal"
                (click)="reveal(contact.type)"
              >
                Показать
              </button>
            </div>
            <div class="ic-empty" *ngIf="!subject.contacts.length">Контакты не указаны.</div>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-tab-group class="ic-block" [(selectedIndex)]="tab">
        <mat-tab label="Согласия">
          <div class="ic-tab-body">
            <mat-slide-toggle [(ngModel)]="superseded" (ngModelChange)="load()">
              Показывать заменённые
            </mat-slide-toggle>
            <table mat-table [dataSource]="subject.consents" class="ic-table" *ngIf="subject.consents.length">
              <ng-container matColumnDef="type">
                <th mat-header-cell *matHeaderCellDef>Согласие</th>
                <td mat-cell *matCellDef="let row">{{ row.typeName }}</td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef>Статус</th>
                <td mat-cell *matCellDef="let row">
                  <span class="ic-badge" [class]="'ic-badge ' + badge(row.status)">{{ row.statusText }}</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="granted">
                <th mat-header-cell *matHeaderCellDef>Получено</th>
                <td mat-cell *matCellDef="let row">{{ row.grantedAt || '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="until">
                <th mat-header-cell *matHeaderCellDef>Действует до</th>
                <td mat-cell *matCellDef="let row">{{ row.validUntil || 'бессрочно' }}</td>
              </ng-container>
              <ng-container matColumnDef="source">
                <th mat-header-cell *matHeaderCellDef>Источник</th>
                <td mat-cell *matCellDef="let row">{{ row.source || '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let row">
                  <button mat-stroked-button (click)="openText(row.id, row.typeName)">Посмотреть текст</button>
                  <button
                    mat-stroked-button
                    color="warn"
                    *ngIf="row.revocable && subject.mayRevoke"
                    (click)="openRevoke(row.id, row.typeName)"
                  >
                    Отозвать
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="consentColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: consentColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!subject.consents.length">
              Согласий нет. Они появляются здесь после регистрации из внешней системы или загрузки
              файла на экране «Импорт». Если согласия были и заменены новыми, включите «Показывать
              заменённые».
            </p>
          </div>
        </mat-tab>

        <mat-tab label="Каналы связи">
          <div class="ic-tab-body ic-tiles">
            <mat-card class="ic-tile" *ngFor="let channel of subject.channels">
              <div class="ic-tile-head">
                <span class="ic-tile-name">{{ channel.nameRu }}</span>
                <span class="ic-badge" [class]="channel.allowed ? 'ic-badge ok' : 'ic-badge danger'">
                  {{ channel.allowed ? 'разрешён' : 'запрещён' }}
                </span>
              </div>
              <div class="ic-tile-note" *ngIf="channel.allowed && channel.validUntil">
                до {{ channel.validUntil }}
              </div>
              <div class="ic-tile-note" *ngIf="!channel.allowed">{{ channel.reason }}</div>
            </mat-card>
          </div>
        </mat-tab>

        <mat-tab label="Передачи третьим лицам">
          <div class="ic-tab-body">
            <table mat-table [dataSource]="subject.transfers" class="ic-table" *ngIf="subject.transfers.length">
              <ng-container matColumnDef="party">
                <th mat-header-cell *matHeaderCellDef>Третье лицо</th>
                <td mat-cell *matCellDef="let row">{{ row.thirdPartyName }}</td>
              </ng-container>
              <ng-container matColumnDef="role">
                <th mat-header-cell *matHeaderCellDef>Роль</th>
                <td mat-cell *matCellDef="let row">{{ row.role }}</td>
              </ng-container>
              <ng-container matColumnDef="categories">
                <th mat-header-cell *matHeaderCellDef>Категории данных</th>
                <td mat-cell *matCellDef="let row">{{ row.categories }}</td>
              </ng-container>
              <ng-container matColumnDef="until">
                <th mat-header-cell *matHeaderCellDef>Действует до</th>
                <td mat-cell *matCellDef="let row">
                  {{ row.validUntil || '—' }}
                  <span class="ic-badge danger" *ngIf="row.contractExpired">договор истёк</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="basis">
                <th mat-header-cell *matHeaderCellDef>Основание</th>
                <td mat-cell *matCellDef="let row">
                  <a *ngIf="row.basisConsentId" [routerLink]="['/consents', row.basisConsentId]">согласие</a>
                  <span *ngIf="!row.basisConsentId">—</span>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="transferColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: transferColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!subject.transfers.length">Передач третьим лицам нет.</p>
          </div>
        </mat-tab>

        <mat-tab label="История">
          <div class="ic-tab-body">
            <div class="ic-filters">
              <mat-form-field appearance="outline">
                <mat-label>Тип события</mat-label>
                <mat-select [(ngModel)]="eventType" (ngModelChange)="loadHistory()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let type of eventTypes()" [value]="type.code">{{ type.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="from" (change)="loadHistory()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="to" (change)="loadHistory()" />
              </mat-form-field>
              <button mat-stroked-button (click)="verify()">Проверить целостность</button>
            </div>
            <div class="ic-note" *ngIf="integrity()">{{ integrity() }}</div>
            <div class="ic-note" *ngIf="feed()?.truncated">
              Показаны первые {{ feed()?.entries?.length }} событий из {{ feed()?.total }}. Сузьте период.
            </div>
            <table mat-table [dataSource]="feed()?.entries ?? []" class="ic-table" *ngIf="feed()?.entries?.length">
              <ng-container matColumnDef="occurredAt">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let row">{{ row.occurredAt }}</td>
              </ng-container>
              <ng-container matColumnDef="eventType">
                <th mat-header-cell *matHeaderCellDef>Событие</th>
                <td mat-cell *matCellDef="let row">{{ row.eventTypeRu }}</td>
              </ng-container>
              <ng-container matColumnDef="description">
                <th mat-header-cell *matHeaderCellDef>Что произошло</th>
                <td mat-cell *matCellDef="let row">{{ row.description }}</td>
              </ng-container>
              <ng-container matColumnDef="actor">
                <th mat-header-cell *matHeaderCellDef>Кто</th>
                <td mat-cell *matCellDef="let row">{{ row.actorRu }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="historyColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: historyColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!feed()?.entries?.length && !loading()">
              Событий за выбранный период нет.
            </p>
          </div>
        </mat-tab>
      </mat-tab-group>
    </ng-container>
  `,
})
export class SubjectCardComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialogs = inject(MatDialog);

  readonly card = signal<SubjectCard | null>(null);
  readonly feed = signal<HistoryFeed | null>(null);
  readonly eventTypes = signal<DictionaryItem[]>([]);
  readonly loading = signal(false);
  readonly message = signal('');
  readonly integrity = signal('');
  /** Файл забирается по той же сессии, что и остальной экран: отдельного токена в браузере нет. */
  readonly pdfUrl = signal('');

  readonly consentColumns = ['type', 'status', 'granted', 'until', 'source', 'actions'];
  readonly transferColumns = ['party', 'role', 'categories', 'until', 'basis'];
  readonly historyColumns = ['occurredAt', 'eventType', 'description', 'actor'];

  tab = 0;
  superseded = false;
  eventType = '';
  from = '';
  to = '';

  private id = '';

  constructor() {
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.pdfUrl.set(`/ui/api/subjects/${this.id}/card.pdf`);
      this.load();
      this.loadHistory();
    });
    this.api.dictionaries().subscribe((dictionaries) => this.eventTypes.set(dictionaries.auditEventTypes));
  }

  load(): void {
    this.loading.set(true);
    this.api.subjectCard(this.id, this.superseded).subscribe((card) => {
      this.card.set(card);
      this.loading.set(false);
    });
  }

  loadHistory(): void {
    this.api.history(this.id, this.eventType, this.from, this.to).subscribe((feed) => this.feed.set(feed));
  }

  /** Раскрытие подставляется в ту же строку: контакт остаётся на месте, меняется только значение. */
  reveal(type: string): void {
    this.api.reveal(this.id, type).subscribe((contact) => {
      const current = this.card();
      if (!current) {
        return;
      }
      this.card.set({
        ...current,
        contacts: current.contacts.map((row) => (row.type === type ? contact : row)),
      });
    });
  }

  verify(): void {
    this.api.verifyHistory(this.id).subscribe((report) => this.integrity.set(report.message));
  }

  /** Текст показываем диалогом, а не переходом: карточка клиента остаётся перед глазами. */
  openText(consentId: string, consentTitle: string): void {
    this.dialogs.open(ConsentTextDialogComponent, {
      data: { consentId, consentTitle },
      width: '720px',
    });
  }

  openRevoke(consentId?: string, consentTitle?: string): void {
    this.dialogs
      .open(RevokeDialogComponent, { data: { subjectId: this.id, consentId, consentTitle }, width: '560px' })
      .afterClosed()
      .subscribe((result?: string) => {
        if (result) {
          this.message.set(result);
          this.load();
          this.loadHistory();
        }
      });
  }

  badge(status: string): string {
    if (status === 'ACTIVE') {
      return 'ok';
    }
    if (status === 'EXPIRING') {
      return 'warn';
    }
    return 'danger';
  }
}

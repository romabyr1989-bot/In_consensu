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
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService, Journal, MessageView, NotificationOptions, RuleRow } from '../api.service';

/**
 * UI-13: правила уведомлений и журнал отправок.
 *
 * Две вкладки одного раздела: правило описывает, кого и когда предупредить, журнал показывает, что из
 * этого вышло. Повторная отправка предлагается только для неудавшихся — повторять доставленное незачем.
 */
@Component({
  selector: 'ic-notifications',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatExpansionModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Уведомления</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-tab-group class="ic-block">
      <mat-tab label="Правила">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-header>
              <mat-card-title>{{ draft.ruleId ? 'Правка правила' : 'Новое правило' }}</mat-card-title>
            </mat-card-header>
            <mat-card-content class="ic-form-grid">
              <mat-form-field appearance="outline">
                <mat-label>Название</mat-label>
                <input matInput [(ngModel)]="draft.name" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Событие</mat-label>
                <mat-select [(ngModel)]="draft.triggerType">
                  <mat-option *ngFor="let item of options()?.triggers" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>За сколько дней предупреждать</mat-label>
                <input matInput [(ngModel)]="draft.daysBefore" placeholder="30, 15, 7" />
                <mat-hint>Через запятую. Пусто — для событий, у которых нет срока.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Каналы</mat-label>
                <mat-select [(ngModel)]="draft.channels" multiple>
                  <mat-option *ngFor="let item of options()?.channels" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Получатели — адреса через запятую</mat-label>
                <input matInput [ngModel]="draft.recipientEmails.join(', ')" (ngModelChange)="setEmails($event)" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Получатели — роли</mat-label>
                <mat-select [(ngModel)]="draft.recipientRoles" multiple>
                  <mat-option *ngFor="let role of roles" [value]="role.code">{{ role.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-button *ngIf="draft.ruleId" (click)="resetDraft()">Отмена</button>
              <button mat-flat-button color="primary" (click)="saveRule()">Сохранить правило</button>
            </mat-card-actions>
          </mat-card>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="rules()" class="ic-table" *ngIf="rules().length">
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>Правило</th>
                <td mat-cell *matCellDef="let row">
                  {{ row.name }}
                  <span class="ic-badge danger" *ngIf="!row.active">выключено</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="trigger">
                <th mat-header-cell *matHeaderCellDef>Событие</th>
                <td mat-cell *matCellDef="let row">
                  {{ row.triggerRu }}
                  <span class="ic-muted" *ngIf="row.thresholds">за {{ row.thresholds }} дн.</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="filters">
                <th mat-header-cell *matHeaderCellDef>Отбор</th>
                <td mat-cell *matCellDef="let row">{{ row.filtersRu || 'без отбора' }}</td>
              </ng-container>
              <ng-container matColumnDef="recipients">
                <th mat-header-cell *matHeaderCellDef>Кому</th>
                <td mat-cell *matCellDef="let row">{{ row.recipients }}</td>
              </ng-container>
              <ng-container matColumnDef="channels">
                <th mat-header-cell *matHeaderCellDef>Каналы</th>
                <td mat-cell *matCellDef="let row">{{ row.channelsRu.join(', ') }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let row">
                  <button mat-button (click)="editRule(row)">Править</button>
                  <button mat-button color="warn" *ngIf="row.active" (click)="deactivate(row)">Выключить</button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="ruleColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: ruleColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!rules().length">Правил пока нет.</p>
          </mat-card>

          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline" class="ic-grow">
                <mat-label>Проверить почтовый канал: адрес</mat-label>
                <input matInput [(ngModel)]="testAddress" />
              </mat-form-field>
              <button mat-stroked-button [disabled]="!testAddress.trim()" (click)="sendTest()">
                Отправить тестовое письмо
              </button>
            </mat-card-content>
          </mat-card>
        </div>
      </mat-tab>

      <mat-tab label="Журнал отправок">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline">
                <mat-label>Статус</mat-label>
                <mat-select [(ngModel)]="status" (ngModelChange)="loadJournal()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of options()?.statuses" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Канал</mat-label>
                <mat-select [(ngModel)]="channel" (ngModelChange)="loadJournal()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of options()?.channels" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="from" (change)="loadJournal()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="to" (change)="loadJournal()" />
              </mat-form-field>
            </mat-card-content>
          </mat-card>

          <mat-card class="ic-block" *ngIf="opened() as opened">
            <mat-card-header>
              <mat-card-title>{{ opened.subjectLine }}</mat-card-title>
              <mat-card-subtitle>
                {{ opened.recipient }} · {{ opened.channelRu }} · {{ opened.statusRu }}
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <pre class="ic-form-text">{{ opened.body }}</pre>
              <div class="ic-danger" *ngIf="opened.error">{{ opened.error }}</div>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-button (click)="this.opened.set(null)">Закрыть</button>
            </mat-card-actions>
          </mat-card>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="journal()?.rows ?? []" class="ic-table" *ngIf="journal()?.rows?.length">
              <ng-container matColumnDef="createdAt">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let row">{{ row.createdAt }}</td>
              </ng-container>
              <ng-container matColumnDef="recipient">
                <th mat-header-cell *matHeaderCellDef>Кому</th>
                <td mat-cell *matCellDef="let row">{{ row.recipient }}</td>
              </ng-container>
              <ng-container matColumnDef="subject">
                <th mat-header-cell *matHeaderCellDef>Тема</th>
                <td mat-cell *matCellDef="let row">
                  <a href="javascript:void(0)" (click)="openMessage(row.id)">{{ row.subjectLine }}</a>
                </td>
              </ng-container>
              <ng-container matColumnDef="rule">
                <th mat-header-cell *matHeaderCellDef>Правило</th>
                <td mat-cell *matCellDef="let row">{{ row.ruleName || '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef>Статус</th>
                <td mat-cell *matCellDef="let row">
                  <span class="ic-badge" [class]="row.canRetry ? 'ic-badge danger' : 'ic-badge ok'">
                    {{ row.statusRu }}
                  </span>
                  <span class="ic-muted" *ngIf="row.error">{{ row.error }}</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let row">
                  <button mat-button *ngIf="row.canRetry" (click)="retry(row.id)">Повторить</button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="journalColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: journalColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!journal()?.rows?.length">Отправок, подходящих под фильтр, нет.</p>
          </mat-card>
        </div>
      </mat-tab>
    </mat-tab-group>
  `,
})
export class NotificationsComponent {
  private readonly api = inject(ApiService);

  readonly rules = signal<RuleRow[]>([]);
  readonly journal = signal<Journal | null>(null);
  readonly options = signal<NotificationOptions | null>(null);
  readonly opened = signal<MessageView | null>(null);
  readonly message = signal('');
  readonly error = signal('');

  readonly ruleColumns = ['name', 'trigger', 'filters', 'recipients', 'channels', 'actions'];
  readonly journalColumns = ['createdAt', 'recipient', 'subject', 'rule', 'status', 'actions'];

  /** Роли — те же, что в матрице прав §16.2; получателем правила бывает роль, а не только адрес. */
  readonly roles = [
    { code: 'DPO', nameRu: 'Специалист по защите данных' },
    { code: 'LAWYER', nameRu: 'Юрист' },
    { code: 'MANAGER', nameRu: 'Менеджер' },
    { code: 'AUDITOR', nameRu: 'Аудитор' },
    { code: 'ADMIN', nameRu: 'Администратор' },
  ];

  draft = this.emptyDraft();
  testAddress = '';
  status = '';
  channel = '';
  from = '';
  to = '';

  constructor() {
    this.api.notificationOptions().subscribe((options) => this.options.set(options));
    this.api.rules().subscribe((rules) => this.rules.set(rules));
    this.loadJournal();
  }

  loadJournal(): void {
    const filters: Record<string, string> = {};
    if (this.status) {
      filters['status'] = this.status;
    }
    if (this.channel) {
      filters['channel'] = this.channel;
    }
    if (this.from) {
      filters['from'] = this.from;
    }
    if (this.to) {
      filters['to'] = this.to;
    }
    this.api.journal(filters).subscribe((journal) => this.journal.set(journal));
  }

  saveRule(): void {
    this.error.set('');
    this.api.saveRule(this.draft).subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.message.set(this.draft.ruleId ? 'Правило изменено' : 'Правило создано');
        this.resetDraft();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Правило не сохранено: проверьте поля.'),
    });
  }

  editRule(row: RuleRow): void {
    this.draft = {
      ruleId: row.id,
      name: row.name,
      triggerType: this.draft.triggerType,
      daysBefore: row.thresholds ?? '',
      recipientEmails: row.recipients ? row.recipients.split(/,\s*/).filter((value) => value.includes('@')) : [],
      recipientRoles: [],
      channels: [],
      consentTypeId: null,
      thirdPartyId: null,
    };
  }

  deactivate(row: RuleRow): void {
    this.api.deactivateRule(row.id).subscribe((rules) => {
      this.rules.set(rules);
      this.message.set('Правило выключено');
    });
  }

  openMessage(id: string): void {
    this.api.message(id).subscribe((view) => this.opened.set(view));
  }

  retry(id: string): void {
    this.api.retryNotification(id).subscribe((result) => {
      this.message.set(result.message);
      this.loadJournal();
    });
  }

  sendTest(): void {
    this.api.testEmail(this.testAddress).subscribe((result) => {
      this.message.set(result.message ?? '');
      this.error.set(result.error ?? '');
    });
  }

  setEmails(value: string): void {
    this.draft.recipientEmails = value
      .split(',')
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
  }

  resetDraft(): void {
    this.draft = this.emptyDraft();
  }

  private emptyDraft() {
    return {
      ruleId: null as string | null,
      name: '',
      triggerType: '',
      daysBefore: '',
      recipientEmails: [] as string[],
      recipientRoles: [] as string[],
      channels: [] as string[],
      consentTypeId: null as string | null,
      thirdPartyId: null as string | null,
    };
  }
}

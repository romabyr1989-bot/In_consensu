import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiService, Journal, MessageView, NotificationOptions, RuleRow } from '../api.service';
import { ConfirmData, ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * UI-13: правила уведомлений и журнал отправок.
 *
 * Две вкладки одного раздела: правило описывает, кого и когда предупредить, журнал показывает, что из
 * этого вышло. Повторная отправка предлагается только для неудавшихся — повторять доставленное незачем.
 *
 * Форма правки заполняется правилом с сервера, а не подписями из таблицы: по строке «Ответственный за
 * ПДн, dpo@example.ru» не восстановить ни роли, ни каналы, ни отбор, и сохранение стёрло бы их молча.
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
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatDialogModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Уведомления</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-tab-group class="ic-block">
      <mat-tab label="Правила">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-header>
              <mat-card-title>{{ draft.ruleId ? 'Правка правила' : 'Новое правило' }}</mat-card-title>
              <mat-card-subtitle *ngIf="draft.ruleId">
                Правило открыто целиком: меняйте что нужно, остальное сохранится как было
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content class="ic-form-grid">
              <p class="ic-note ic-span-2" *ngIf="editingDisabled()">
                Это правило сейчас выключено. Если сохранить его, оно снова начнёт слать уведомления.
              </p>
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
                <input matInput [(ngModel)]="draft.daysBefore" />
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
                  <mat-option *ngFor="let role of options()?.roles" [value]="role.code">{{ role.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Отбор: тип согласия</mat-label>
                <mat-select [(ngModel)]="draft.consentTypeId">
                  <mat-option [value]="null">Любой тип</mat-option>
                  <mat-option *ngFor="let item of options()?.consentTypes" [value]="item.code">
                    {{ item.nameRu }}
                  </mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Отбор: третье лицо</mat-label>
                <mat-select [(ngModel)]="draft.thirdPartyId">
                  <mat-option [value]="null">Любое</mat-option>
                  <mat-option *ngFor="let item of options()?.thirdParties" [value]="item.code">
                    {{ item.nameRu }}
                  </mat-option>
                </mat-select>
              </mat-form-field>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-stroked-button *ngIf="draft.ruleId" (click)="resetDraft()">Отмена</button>
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
                  <button mat-stroked-button (click)="editRule(row)">Править</button>
                  <button mat-stroked-button color="warn" *ngIf="row.active" (click)="deactivate(row)">Выключить</button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="ruleColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: ruleColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!rules().length">
              Правил пока нет, и напоминания никому не уходят. Заполните форму выше: название, событие,
              за сколько дней предупреждать и кому — и правило заработает.
            </p>
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
                <mat-select [(ngModel)]="status" (ngModelChange)="applyJournalFilters()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of options()?.statuses" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Канал</mat-label>
                <mat-select [(ngModel)]="channel" (ngModelChange)="applyJournalFilters()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of options()?.channels" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Правило</mat-label>
                <mat-select [(ngModel)]="ruleId" (ngModelChange)="applyJournalFilters()">
                  <mat-option value="">Все правила</mat-option>
                  <mat-option *ngFor="let item of options()?.rules" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="from" (change)="applyJournalFilters()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="to" (change)="applyJournalFilters()" />
              </mat-form-field>
              <button mat-stroked-button *ngIf="journalFiltered()" (click)="resetJournalFilters()">Сбросить отбор</button>
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
              <button mat-stroked-button (click)="this.opened.set(null)">Закрыть</button>
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
                  <button mat-stroked-button *ngIf="row.canRetry" (click)="retry(row.id)">Повторить</button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="journalColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: journalColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!journal()?.rows?.length && journalFiltered()">
              Под этот отбор отправок нет. Снимите фильтры или расширьте период — например, поставьте
              дату «с» на месяц раньше.
            </p>
            <p class="ic-empty" *ngIf="!journal()?.rows?.length && !journalFiltered()">
              Отправок ещё не было. Они появятся, когда сработает правило; чтобы проверить почту прямо
              сейчас, отправьте тестовое письмо на вкладке «Правила».
            </p>
            <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
            <mat-paginator
              [length]="journal()?.total ?? 0"
              [pageSize]="size"
              [pageIndex]="page"
              [pageSizeOptions]="[10, 20, 50]"
              (page)="turnPage($event)"
            ></mat-paginator>
          </mat-card>
        </div>
      </mat-tab>
    </mat-tab-group>
  `,
})
export class NotificationsComponent {
  private readonly api = inject(ApiService);
  private readonly dialogs = inject(MatDialog);

  readonly rules = signal<RuleRow[]>([]);
  readonly journal = signal<Journal | null>(null);
  readonly options = signal<NotificationOptions | null>(null);
  readonly opened = signal<MessageView | null>(null);
  readonly message = signal('');
  readonly error = signal('');
  readonly loading = signal(false);
  /** Правят выключенное правило: сохранение вернёт его в работу, и об этом надо предупредить заранее. */
  readonly editingDisabled = signal(false);

  readonly ruleColumns = ['name', 'trigger', 'filters', 'recipients', 'channels', 'actions'];
  readonly journalColumns = ['createdAt', 'recipient', 'subject', 'rule', 'status', 'actions'];

  draft = this.emptyDraft();
  testAddress = '';
  status = '';
  channel = '';
  ruleId = '';
  from = '';
  to = '';
  page = 0;
  size = 10;

  constructor() {
    this.loadOptions();
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
    if (this.ruleId) {
      filters['ruleId'] = this.ruleId;
    }
    if (this.from) {
      filters['from'] = this.from;
    }
    if (this.to) {
      filters['to'] = this.to;
    }
    filters['page'] = String(this.page);
    filters['size'] = String(this.size);
    this.loading.set(true);
    this.api.journal(filters).subscribe({
      next: (journal) => {
        this.journal.set(journal);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Журнал не загрузился. Обновите страницу и попробуйте снова.');
      },
    });
  }

  journalFiltered(): boolean {
    return !!(this.status || this.channel || this.ruleId || this.from || this.to);
  }

  /** Новый отбор — всегда с первой страницы: иначе список открывается пустым посреди результатов. */
  applyJournalFilters(): void {
    this.page = 0;
    this.loadJournal();
  }

  resetJournalFilters(): void {
    this.status = '';
    this.channel = '';
    this.ruleId = '';
    this.from = '';
    this.to = '';
    this.applyJournalFilters();
  }

  turnPage(event: PageEvent): void {
    this.page = event.pageIndex;
    this.size = event.pageSize;
    this.loadJournal();
  }

  saveRule(): void {
    this.error.set('');
    const editing = !!this.draft.ruleId;
    this.api.saveRule(this.draft).subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.message.set(editing ? 'Правило изменено' : 'Правило создано');
        this.resetDraft();
        // Новое правило должно появиться в отборе журнала — справочники перечитываем целиком.
        this.loadOptions();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Правило не сохранено: проверьте поля.'),
    });
  }

  /**
   * Форму заполняет правило с сервера, а не строка таблицы: в подписях нет ни каналов, ни ролей, ни
   * отбора, и собранная из них правка стирала бы их при сохранении.
   */
  editRule(row: RuleRow): void {
    this.error.set('');
    this.message.set('');
    this.loading.set(true);
    this.api.rule(row.id).subscribe({
      next: (details) => {
        this.loading.set(false);
        this.editingDisabled.set(!details.active);
        this.draft = {
          ruleId: details.id,
          name: details.name,
          triggerType: details.triggerType ?? '',
          daysBefore: details.daysBefore ?? '',
          recipientEmails: details.recipientEmails ?? [],
          recipientRoles: details.recipientRoles ?? [],
          channels: details.channels ?? [],
          consentTypeId: details.consentTypeId,
          thirdPartyId: details.thirdPartyId,
        };
      },
      error: (failure) => {
        this.loading.set(false);
        this.error.set(failure?.error?.detail ?? 'Правило не открылось. Обновите страницу и попробуйте снова.');
      },
    });
  }

  deactivate(row: RuleRow): void {
    const data: ConfirmData = {
      title: `Выключить правило «${row.name}»?`,
      consequences: this.consequences(row),
      confirmLabel: 'Выключить',
      danger: true,
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (!confirmed) {
          return;
        }
        this.error.set('');
        this.api.deactivateRule(row.id).subscribe({
          next: (rules) => {
            this.rules.set(rules);
            this.message.set(`Правило «${row.name}» выключено`);
          },
          error: (failure) => this.error.set(failure?.error?.detail ?? 'Правило не удалось выключить.'),
        });
      });
  }

  openMessage(id: string): void {
    this.api.message(id).subscribe((view) => this.opened.set(view));
  }

  retry(id: string): void {
    this.api.retryNotification(id).subscribe({
      next: (result) => {
        this.message.set(result.message);
        this.loadJournal();
      },
      error: (failure) =>
        this.error.set(failure?.error?.detail ?? 'Не удалось отправить повторно. Попробуйте ещё раз.'),
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
    this.editingDisabled.set(false);
  }

  private loadOptions(): void {
    this.api.notificationOptions().subscribe((options) => this.options.set(options));
  }

  /** Последствия названы поимённо: без них сотрудник соглашается не глядя (UI-0.6). */
  private consequences(row: RuleRow): string {
    const recipients = row.recipients ? row.recipients.trim() : '';
    const audience = recipients ? `получателям: ${recipients}` : 'никому: получатели у правила не заданы';
    const channels = row.channelsRu.length ? ` по каналам ${row.channelsRu.join(', ')}` : '';
    const scope = row.filtersRu ? ` Отбор «${row.filtersRu}» больше ни на что не влияет.` : '';
    return (
      `Напоминания по событию «${row.triggerRu}» больше не создаются: письма${channels} перестанут ` +
      `приходить ${audience}.${scope} Уже отправленное останется в журнале. Правило можно вернуть ` +
      `в работу: откройте его кнопкой «Править» и сохраните.`
    );
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

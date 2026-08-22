import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, DeliveryRow, SubscriptionRow } from '../api.service';
import { ConfirmData, ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * UI-14: подписки на события и журнал доставок.
 *
 * Секрет подписки показывается один раз — при создании и при замене: хранить его экрану негде, он нужен
 * только чтобы настроить потребителя. Подписку выключают, а не удаляют: журнал доставок остаётся
 * доказательством того, что события уходили.
 *
 * Типы событий приходят с сервера, а не зашиты в экран: их коды — часть контракта с потребителем, и
 * список, набранный здесь вручную, рано или поздно разойдётся с тем, что сервер умеет отправлять.
 *
 * Правку сервер принимает только вместе с включением: выключенная подписка после сохранения снова
 * начинает получать события, поэтому экран переспрашивает, прежде чем её отправить.
 */
@Component({
  selector: 'ic-webhooks',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatDialogModule,
  ],
  template: `
    <h1 class="ic-title">Webhooks</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="secret()">
      <mat-card-header>
        <mat-card-title>Секрет подписки «{{ secretFor() }}»</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <p class="ic-warn">
          Он показывается один раз. Скопируйте его сейчас и перенесите в настройки потребителя: второй
          раз этот секрет не покажут, останется только заменить его на новый.
        </p>
        <pre class="ic-form-text">{{ secret() }}</pre>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="hideSecret()">Скрыть</button>
      </mat-card-actions>
    </mat-card>

    <mat-card class="ic-block">
      <mat-card-header>
        <mat-card-title>{{ draft.subscriptionId ? 'Правка подписки' : 'Новая подписка' }}</mat-card-title>
        <mat-card-subtitle class="ic-warn" *ngIf="editingInactive()">
          Подписка выключена. Сохранить правку, оставив её выключенной, нельзя — экран переспросит перед
          тем, как включить её обратно.
        </mat-card-subtitle>
      </mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Название</mat-label>
          <input matInput [(ngModel)]="draft.name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Адрес получателя</mat-label>
          <input matInput [(ngModel)]="draft.url" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-span-2">
          <mat-label>События</mat-label>
          <mat-select [(ngModel)]="draft.eventTypes" multiple>
            <mat-option *ngFor="let code of eventTypes()" [value]="code">
              {{ code }}<span class="ic-muted" *ngIf="eventLabel(code)">— {{ eventLabel(code) }}</span>
            </mat-option>
          </mat-select>
        </mat-form-field>
      </mat-card-content>
      <mat-card-content *ngIf="!eventTypes().length">
        <p class="ic-empty">
          Список типов событий не загрузился, выбирать пока не из чего. Обновите страницу — без него
          подписка получит все события подряд.
        </p>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button *ngIf="draft.subscriptionId" (click)="resetDraft()">Отмена</button>
        <button mat-flat-button color="primary" (click)="save()">
          {{ editingInactive() ? 'Сохранить и включить' : 'Сохранить' }}
        </button>
      </mat-card-actions>
    </mat-card>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="rows()" class="ic-table" *ngIf="rows().length">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Подписка</th>
          <td mat-cell *matCellDef="let row">{{ row.name }}</td>
        </ng-container>
        <ng-container matColumnDef="url">
          <th mat-header-cell *matHeaderCellDef>Адрес</th>
          <td mat-cell *matCellDef="let row">{{ row.url }}</td>
        </ng-container>
        <ng-container matColumnDef="events">
          <th mat-header-cell *matHeaderCellDef>События</th>
          <td mat-cell *matCellDef="let row">
            <div *ngFor="let code of row.eventTypes">
              {{ code }}<span class="ic-muted" *ngIf="eventLabel(code)">— {{ eventLabel(code) }}</span>
            </div>
            <span class="ic-muted" *ngIf="!row.eventTypes.length">все события</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="state">
          <th mat-header-cell *matHeaderCellDef>Состояние</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-badge" [class]="row.active ? 'ic-badge ok' : 'ic-badge danger'">
              {{ row.active ? 'работает' : 'выключена' }}
            </span>
            <div class="ic-muted" *ngIf="!row.active">события не доставляются</div>
          </td>
        </ng-container>
        <ng-container matColumnDef="last">
          <th mat-header-cell *matHeaderCellDef>Последняя доставка</th>
          <td mat-cell *matCellDef="let row">
            <span *ngIf="row.lastDeliveryAt">
              {{ row.lastDeliveryAt }} ·
              <span class="ic-badge" [class]="row.lastDeliverySuccessful ? 'ic-badge ok' : 'ic-badge danger'">
                {{ row.lastDeliveryResult }}
              </span>
            </span>
            <span class="ic-muted" *ngIf="!row.lastDeliveryAt">доставок не было</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <div class="ic-actions">
              <button mat-button (click)="edit(row)">Править</button>
              <button mat-button (click)="test(row)">Проверить</button>
              <button mat-button (click)="openDeliveries(row)">Доставки</button>
              <button mat-button (click)="rotateSecret(row)">Заменить секрет</button>
              <button mat-button color="warn" *ngIf="row.active" (click)="deactivate(row)">Выключить</button>
            </div>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!rows().length">
        Подписок пока нет. Заведите первую в форме выше: название, адрес получателя по https и события,
        которые ему нужны. Секрет для проверки подписи покажем сразу после создания — один раз.
      </p>
    </mat-card>

    <mat-card class="ic-block" *ngIf="openedName()">
      <mat-card-header>
        <mat-card-title>Доставки: {{ openedName() }}</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <table mat-table [dataSource]="deliveries()" class="ic-table" *ngIf="deliveries().length">
          <ng-container matColumnDef="deliveredAt">
            <th mat-header-cell *matHeaderCellDef>Когда</th>
            <td mat-cell *matCellDef="let row">{{ row.deliveredAt }}</td>
          </ng-container>
          <ng-container matColumnDef="attempt">
            <th mat-header-cell *matHeaderCellDef>Попытка</th>
            <td mat-cell *matCellDef="let row">{{ row.attempt }}</td>
          </ng-container>
          <ng-container matColumnDef="result">
            <th mat-header-cell *matHeaderCellDef>Результат</th>
            <td mat-cell *matCellDef="let row">
              <span class="ic-badge" [class]="row.successful ? 'ic-badge ok' : 'ic-badge danger'">
                {{ row.responseCode || 'нет ответа' }}
              </span>
              <span class="ic-muted" *ngIf="row.error">{{ row.error }}</span>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-button *ngIf="!row.successful" (click)="retry(row)">Повторить</button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="deliveryColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: deliveryColumns"></tr>
        </table>
        <p class="ic-muted" *ngIf="deliveriesTotal() > deliveries().length">
          Показаны последние {{ deliveries().length }} доставок из {{ deliveriesTotal() }}.
        </p>
        <p class="ic-empty" *ngIf="!deliveries().length">
          Доставок по этой подписке нет. Нажмите «Проверить» — тестовое событие уйдёт на адрес подписки
          и появится здесь вместе с ответом получателя.
        </p>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="closeDeliveries()">Закрыть</button>
      </mat-card-actions>
    </mat-card>
  `,
})
export class WebhooksComponent {
  private readonly api = inject(ApiService);
  private readonly dialogs = inject(MatDialog);

  readonly rows = signal<SubscriptionRow[]>([]);
  readonly deliveries = signal<DeliveryRow[]>([]);
  readonly deliveriesTotal = signal(0);
  readonly openedName = signal('');
  readonly secret = signal('');
  /** Название подписки, чей секрет сейчас на экране: секретов бывает несколько за один заход. */
  readonly secretFor = signal('');
  readonly message = signal('');
  readonly error = signal('');
  /** Правится выключенная подписка: сохранение включит её обратно, и об этом нужно спросить. */
  readonly editingInactive = signal(false);
  readonly eventTypes = signal<string[]>([]);

  readonly columns = ['name', 'url', 'events', 'state', 'last', 'actions'];
  readonly deliveryColumns = ['deliveredAt', 'attempt', 'result', 'actions'];

  /** Подписи к кодам событий §10: код показываем всегда, по нему потребитель настраивает обработчик. */
  private readonly eventLabels: Record<string, string> = {
    'consent.granted': 'клиент дал согласие',
    'consent.revoked': 'клиент отозвал согласие',
    'consent.superseded': 'согласие заменено новым',
    'consent.expiring': 'срок согласия подходит к концу',
    'consent.expired': 'срок согласия истёк',
    'form.published': 'опубликована новая версия формы',
    'third_party.contract_expiring': 'у третьего лица заканчивается договор',
    'import.finished': 'импорт данных завершён',
  };

  draft = this.emptyDraft();
  private openedId = '';

  constructor() {
    this.load();
    this.api.webhookEventTypes().subscribe({
      next: (types) => this.eventTypes.set(types),
      error: () =>
        this.error.set('Список типов событий не загрузился. Обновите страницу: без него подписку не собрать.'),
    });
  }

  load(): void {
    this.api.webhooks().subscribe((rows) => this.rows.set(rows));
  }

  eventLabel(code: string): string {
    return this.eventLabels[code] ?? '';
  }

  save(): void {
    this.error.set('');
    if (!this.editingInactive()) {
      this.submit();
      return;
    }
    const data: ConfirmData = {
      title: `Сохранить правку и включить подписку «${this.draft.name}»?`,
      consequences:
        `Подписка «${this.draft.name}» сейчас выключена, и сохранить её правку, оставив выключенной, ` +
        `нельзя: вместе с правкой она включится и события снова пойдут на адрес ${this.draft.url}. ` +
        'Если включать её пока рано — отмените, правка не сохранится, и подписка останется выключенной.',
      confirmLabel: 'Сохранить и включить',
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          this.submit();
        }
      });
  }

  edit(row: SubscriptionRow): void {
    this.draft = {
      subscriptionId: row.id,
      name: row.name,
      url: row.url,
      eventTypes: [...row.eventTypes],
    };
    this.editingInactive.set(!row.active);
    this.message.set('');
    this.error.set('');
  }

  deactivate(row: SubscriptionRow): void {
    const data: ConfirmData = {
      title: `Выключить подписку «${row.name}»?`,
      consequences:
        `События перестанут доставляться: ${this.eventsPhrase(row)} больше не уйдут на адрес ${row.url}. ` +
        'Всё, что случится, пока подписка выключена, потребитель по ней не получит — задним числом такие ' +
        'события не досылаются. Прошлые доставки останутся в журнале: подписку выключают, а не удаляют. ' +
        'Включить обратно можно правкой: откройте подписку, сохраните — и доставка возобновится.',
      confirmLabel: 'Выключить',
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (!confirmed) {
          return;
        }
        this.error.set('');
        this.api.deactivateWebhook(row.id).subscribe({
          next: (rows) => {
            this.rows.set(rows);
            this.message.set(`Подписка «${row.name}» выключена: события на неё больше не уходят.`);
            if (this.draft.subscriptionId === row.id) {
              this.editingInactive.set(true);
            }
          },
          error: (failure) => this.error.set(failure?.error?.detail ?? 'Подписку не удалось выключить.'),
        });
      });
  }

  rotateSecret(row: SubscriptionRow): void {
    const data: ConfirmData = {
      title: `Заменить секрет подписки «${row.name}»?`,
      consequences:
        'Прежняя подпись перестанет приниматься: потребителя нужно перенастроить на новый секрет, иначе ' +
        `он не сойдётся на подписи и начнёт отклонять события, хотя мы продолжим слать их на ${row.url}. ` +
        'Новый секрет покажем один раз, сразу после замены: не сохраните — придётся менять снова.',
      confirmLabel: 'Заменить секрет',
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
        this.api.rotateSecret(row.id).subscribe({
          next: (saved) => {
            this.rows.set(saved.rows);
            this.secret.set(saved.secret ?? '');
            this.secretFor.set(row.name);
            this.message.set(`Секрет подписки «${row.name}» заменён. Перенесите новый в настройки потребителя.`);
          },
          error: (failure) => this.error.set(failure?.error?.detail ?? 'Секрет заменить не удалось.'),
        });
      });
  }

  test(row: SubscriptionRow): void {
    this.api.testWebhook(row.id).subscribe((result) => {
      this.message.set(result.message ?? '');
      this.error.set(result.error ?? '');
      this.load();
    });
  }

  openDeliveries(row: SubscriptionRow): void {
    this.openedId = row.id;
    this.openedName.set(row.name);
    this.loadDeliveries();
  }

  closeDeliveries(): void {
    this.openedId = '';
    this.openedName.set('');
    this.deliveries.set([]);
    this.deliveriesTotal.set(0);
  }

  retry(row: DeliveryRow): void {
    this.api.retryDelivery(this.openedId, row.eventId).subscribe({
      next: (result) => {
        this.message.set(result.message);
        this.loadDeliveries();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Повторить доставку не вышло.'),
    });
  }

  resetDraft(): void {
    this.draft = this.emptyDraft();
    this.editingInactive.set(false);
  }

  hideSecret(): void {
    this.secret.set('');
    this.secretFor.set('');
  }

  /** Отправка отделена от кнопки: выключенная подписка доходит сюда только после подтверждения. */
  private submit(): void {
    const update = !!this.draft.subscriptionId;
    const switchedOn = this.editingInactive();
    const name = this.draft.name;
    this.api.saveWebhook(this.draft).subscribe({
      next: (saved) => {
        this.rows.set(saved.rows);
        this.message.set(this.savedMessage(update, switchedOn, name));
        this.secret.set(saved.secret ?? '');
        this.secretFor.set(saved.secret ? name : '');
        this.resetDraft();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Подписка не сохранена: проверьте адрес.'),
    });
  }

  private savedMessage(update: boolean, switchedOn: boolean, name: string): string {
    if (!update) {
      return `Подписка «${name}» создана. Секрет показан в карточке выше — перенесите его в настройки потребителя.`;
    }
    return switchedOn
      ? `Подписка «${name}» изменена и включена: события снова доставляются.`
      : `Подписка «${name}» изменена.`;
  }

  /** Что именно погаснет — списком кодов: без него сотрудник соглашается не глядя (UI-0.6). */
  private eventsPhrase(row: SubscriptionRow): string {
    return row.eventTypes.length ? `события ${row.eventTypes.join(', ')}` : 'все события';
  }

  private loadDeliveries(): void {
    this.api.deliveries(this.openedId).subscribe((page) => {
      this.deliveries.set(page.rows);
      this.deliveriesTotal.set(page.total);
    });
  }

  private emptyDraft() {
    return { subscriptionId: null as string | null, name: '', url: '', eventTypes: [] as string[] };
  }
}

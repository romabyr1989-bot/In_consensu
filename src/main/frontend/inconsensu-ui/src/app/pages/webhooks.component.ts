import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, DeliveryRow, SubscriptionRow } from '../api.service';

/**
 * UI-14: подписки на события и журнал доставок.
 *
 * Секрет подписки показывается один раз, при создании: потребителя настраивают сразу, а дальше секрет
 * можно только заменить. Повторная доставка предлагается для неудавшихся попыток.
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
  ],
  template: `
    <h1 class="ic-title">Webhooks</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="secret()">
      <mat-card-header><mat-card-title>Секрет подписки</mat-card-title></mat-card-header>
      <mat-card-content>
        <p class="ic-warn">Он показывается один раз. Скопируйте его сейчас — потом секрет можно только заменить.</p>
        <pre class="ic-form-text">{{ secret() }}</pre>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="secret.set('')">Скрыть</button>
      </mat-card-actions>
    </mat-card>

    <mat-card class="ic-block">
      <mat-card-header>
        <mat-card-title>{{ draft.subscriptionId ? 'Правка подписки' : 'Новая подписка' }}</mat-card-title>
      </mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Название</mat-label>
          <input matInput [(ngModel)]="draft.name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Адрес получателя</mat-label>
          <input matInput [(ngModel)]="draft.url" placeholder="https://…" />
          <mat-hint>Только https и только внешний адрес: обращения внутрь сети запрещены.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ic-span-2">
          <mat-label>События</mat-label>
          <mat-select [(ngModel)]="draft.eventTypes" multiple>
            <mat-option *ngFor="let event of events" [value]="event.code">{{ event.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button *ngIf="draft.subscriptionId" (click)="resetDraft()">Отмена</button>
        <button mat-flat-button color="primary" (click)="save()">Сохранить</button>
      </mat-card-actions>
    </mat-card>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="rows()" class="ic-table" *ngIf="rows().length">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Подписка</th>
          <td mat-cell *matCellDef="let row">
            {{ row.name }}
            <span class="ic-badge danger" *ngIf="!row.active">выключена</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="url">
          <th mat-header-cell *matHeaderCellDef>Адрес</th>
          <td mat-cell *matCellDef="let row">{{ row.url }}</td>
        </ng-container>
        <ng-container matColumnDef="events">
          <th mat-header-cell *matHeaderCellDef>События</th>
          <td mat-cell *matCellDef="let row">{{ row.eventTypes.join(', ') }}</td>
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
            <button mat-button (click)="edit(row)">Править</button>
            <button mat-button (click)="test(row)">Проверить</button>
            <button mat-button (click)="openDeliveries(row)">Доставки</button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!rows().length">Подписок пока нет.</p>
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
        <p class="ic-empty" *ngIf="!deliveries().length">Доставок по этой подписке нет.</p>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-button (click)="openedName.set('')">Закрыть</button>
      </mat-card-actions>
    </mat-card>
  `,
})
export class WebhooksComponent {
  private readonly api = inject(ApiService);

  readonly rows = signal<SubscriptionRow[]>([]);
  readonly deliveries = signal<DeliveryRow[]>([]);
  readonly openedName = signal('');
  readonly secret = signal('');
  readonly message = signal('');
  readonly error = signal('');

  readonly columns = ['name', 'url', 'events', 'last', 'actions'];
  readonly deliveryColumns = ['deliveredAt', 'attempt', 'result', 'actions'];

  /** Типы событий §10: подписка выбирает, что именно ей присылать. */
  readonly events = [
    { code: 'consent.granted', nameRu: 'consent.granted — согласие получено' },
    { code: 'consent.revoked', nameRu: 'consent.revoked — согласие отозвано' },
    { code: 'consent.expired', nameRu: 'consent.expired — срок истёк' },
    { code: 'consent.expiring', nameRu: 'consent.expiring — скоро истекает' },
  ];

  draft = this.emptyDraft();
  private openedId = '';

  constructor() {
    this.load();
  }

  load(): void {
    this.api.webhooks().subscribe((rows) => this.rows.set(rows));
  }

  save(): void {
    this.error.set('');
    this.api.saveWebhook(this.draft).subscribe({
      next: (saved) => {
        this.rows.set(saved.rows);
        this.message.set(this.draft.subscriptionId ? 'Подписка изменена' : 'Подписка создана');
        this.secret.set(saved.secret ?? '');
        this.resetDraft();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Подписка не сохранена: проверьте адрес.'),
    });
  }

  edit(row: SubscriptionRow): void {
    this.draft = {
      subscriptionId: row.id,
      name: row.name,
      url: row.url,
      eventTypes: [...row.eventTypes],
    };
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
    this.api.deliveries(row.id).subscribe((page) => this.deliveries.set(page.rows));
  }

  retry(row: DeliveryRow): void {
    this.api.retryDelivery(this.openedId, row.eventId).subscribe((result) => {
      this.message.set(result.message);
      this.api.deliveries(this.openedId).subscribe((page) => this.deliveries.set(page.rows));
    });
  }

  resetDraft(): void {
    this.draft = this.emptyDraft();
  }

  private emptyDraft() {
    return { subscriptionId: null as string | null, name: '', url: '', eventTypes: [] as string[] };
  }
}

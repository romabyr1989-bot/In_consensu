import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiService, CurrentUser, Dashboard } from '../api.service';

/**
 * UI-2: главная — плитки-счётчики со ссылками на отфильтрованные списки и блоки последних событий.
 *
 * Блоки «Проблемы доставки webhook» и «Ошибки импорта» показываются, только когда разбирать
 * действительно есть что: пустыми они каждый день сообщали бы, что всё хорошо, и главная перестала
 * бы читаться как сводка дел.
 *
 * Фирменный цвет оператора уходит в полосу на плитках, а не в сами числа: цветом чисел размечены
 * состояния — «истекает» и «отозвано», и брендирование не должно с ними спорить (UI-0.7, UI-0.12).
 */
@Component({
  selector: 'ic-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, MatCardModule, MatButtonModule, MatTableModule, MatProgressBarModule],
  template: `
    <h1 class="ic-title">Главная</h1>
    <mat-progress-bar mode="indeterminate" *ngIf="!data() && !error()"></mat-progress-bar>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <div class="ic-tiles" *ngIf="data() as d">
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/subjects']"
        [queryParams]="{ status: 'ACTIVE' }"
      >
        <div class="ic-tile-value">{{ d.activeConsents }}</div>
        <div class="ic-tile-label">Действующих согласий</div>
      </mat-card>
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/subjects']"
        [queryParams]="{ status: 'EXPIRING' }"
      >
        <div class="ic-tile-value ic-warn">{{ d.expiringConsents }}</div>
        <div class="ic-tile-label">Истекают за 30 дней</div>
      </mat-card>
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/subjects']"
        [queryParams]="{ revokedOnly: 'true' }"
      >
        <div class="ic-tile-value ic-danger">{{ d.revokedConsents }}</div>
        <div class="ic-tile-label">Отозвано за 30 дней</div>
      </mat-card>
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/catalog/forms']"
        [queryParams]="{ status: 'ON_REVIEW' }"
      >
        <div class="ic-tile-value">{{ d.awaitingApproval }}</div>
        <div class="ic-tile-label">Форм на согласовании</div>
      </mat-card>
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/third-parties']"
        [queryParams]="{ contract: 'EXPIRING' }"
      >
        <div class="ic-tile-value">{{ d.expiringContracts }}</div>
        <div class="ic-tile-label">Договоров истекает за 30 дней</div>
      </mat-card>
      <mat-card
        class="ic-tile"
        [style.border-left]="accent()"
        [routerLink]="['/catalog/forms']"
        [queryParams]="{ status: 'PUBLISHED' }"
      >
        <div class="ic-tile-value">{{ d.publishedForms }}</div>
        <div class="ic-tile-label">Опубликованных форм</div>
      </mat-card>
      <!-- Проблемы стоят теми же плитками: отдельная карточка ради одного числа занимала полэкрана. -->
      <mat-card class="ic-tile ic-tile-alarm" *ngIf="showDeliveryProblems()" routerLink="/webhooks">
        <div class="ic-tile-value ic-danger">{{ failedDeliveries() }}</div>
        <div class="ic-tile-label">
          {{ plural(failedDeliveries(), 'событие', 'события', 'событий') }} не доставлено
          <span class="ic-badge danger">попытки исчерпаны</span>
        </div>
      </mat-card>
      <mat-card class="ic-tile ic-tile-alarm" *ngIf="showImportProblems()" routerLink="/import">
        <div class="ic-tile-value ic-warn">{{ failedImports() }}</div>
        <div class="ic-tile-label">
          {{ plural(failedImports(), 'загрузка', 'загрузки', 'загрузок') }} с ошибками
          <span class="ic-badge warn">нужен разбор</span>
        </div>
      </mat-card>
    </div>

    <mat-card class="ic-block" *ngIf="data() as d">
      <mat-card-header><mat-card-title>Последние уведомления</mat-card-title></mat-card-header>
      <mat-card-content>
        <table mat-table [dataSource]="d.recentNotifications" class="ic-table" *ngIf="d.recentNotifications.length">
          <ng-container matColumnDef="recipient">
            <th mat-header-cell *matHeaderCellDef>Получатель</th>
            <td mat-cell *matCellDef="let row">{{ row.recipient }}</td>
          </ng-container>
          <ng-container matColumnDef="subject">
            <th mat-header-cell *matHeaderCellDef>Тема</th>
            <td mat-cell *matCellDef="let row">{{ row.subject }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Статус</th>
            <td mat-cell *matCellDef="let row">{{ statusRu(row.status) }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="notificationColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: notificationColumns"></tr>
        </table>
        <p class="ic-empty" *ngIf="!d.recentNotifications.length">
          Уведомлений пока нет — они появятся после ближайшего прогона задачи.
        </p>
      </mat-card-content>
    </mat-card>
  `,
})
export class DashboardComponent {
  private readonly api = inject(ApiService);
  readonly data = signal<Dashboard | null>(null);
  readonly error = signal('');
  readonly brand = signal<CurrentUser | null>(null);
  readonly notificationColumns = ['recipient', 'subject', 'status'];

  // Сервер отдаёт не больше десяти записей в каждом наборе: число показывает, что разбор нужен,
  // а полный список — в самом разделе.
  readonly failedDeliveries = computed(() => this.data()?.failedDeliveries ?? 0);
  readonly failedImports = computed(() => this.data()?.failedImports ?? 0);

  // Блок видит тот, кому открыт раздел из меню: звать разбираться туда, куда не пускают, бессмысленно.
  readonly showDeliveryProblems = computed(() => this.failedDeliveries() > 0 && this.hasRole('ADMIN'));
  readonly showImportProblems = computed(() => this.failedImports() > 0 && this.hasRole('DPO', 'ADMIN'));

  /** Значение уходит прямо в стиль, поэтому берём только правильный HEX из настроек оператора. */
  readonly accent = computed(() => {
    const color = this.brand()?.color ?? '';
    return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(color) ? `4px solid ${color}` : null;
  });

  constructor() {
    this.api.dashboard().subscribe({
      next: (data) => this.data.set(data),
      // UI-0.9: называем, что именно не вышло, и куда идти дальше.
      error: () =>
        this.error.set(
          'Сводка главной сейчас не загрузилась. Обновите страницу; разделы слева работают и без неё.',
        ),
    });
    // Без фирменного цвета плитки просто останутся обычными, поэтому отказ ничего не рушит.
    this.api.me().subscribe({ next: (user) => this.brand.set(user), error: () => this.brand.set(null) });
  }

  private hasRole(...roles: string[]): boolean {
    const mine = this.brand()?.roles ?? [];
    return roles.some((role) => mine.includes(role));
  }

  /** UI-0.4: статусы показываются по-русски. */
  statusRu(status: string): string {
    switch (status) {
      case 'SENT':
        return 'отправлено';
      case 'FAILED':
        return 'ошибка';
      case 'PENDING':
        return 'в очереди';
      default:
        return status;
    }
  }

  /** Русское число рядом с существительным: «1 загрузка», «2 загрузки», «5 загрузок». */
  plural(count: number, one: string, few: string, many: string): string {
    const hundreds = count % 100;
    const units = count % 10;
    if (hundreds >= 11 && hundreds <= 14) {
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
}

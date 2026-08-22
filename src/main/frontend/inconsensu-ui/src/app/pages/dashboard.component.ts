import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiService, Dashboard } from '../api.service';

/** UI-2: главная — плитки-счётчики со ссылками на отфильтрованные списки и блоки последних событий. */
@Component({
  selector: 'ic-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, MatCardModule, MatIconModule, MatTableModule, MatProgressBarModule],
  template: `
    <h1 class="ic-title">Главная</h1>
    <mat-progress-bar mode="indeterminate" *ngIf="!data()"></mat-progress-bar>

    <div class="ic-tiles" *ngIf="data() as d">
      <mat-card class="ic-tile" routerLink="/third-parties">
        <div class="ic-tile-value">{{ d.activeConsents }}</div>
        <div class="ic-tile-label">Действующих согласий</div>
      </mat-card>
      <mat-card class="ic-tile">
        <div class="ic-tile-value ic-warn">{{ d.expiringConsents }}</div>
        <div class="ic-tile-label">Истекают за 30 дней</div>
      </mat-card>
      <mat-card class="ic-tile">
        <div class="ic-tile-value ic-danger">{{ d.revokedConsents }}</div>
        <div class="ic-tile-label">Отозвано за 30 дней</div>
      </mat-card>
      <mat-card class="ic-tile">
        <div class="ic-tile-value">{{ d.awaitingApproval }}</div>
        <div class="ic-tile-label">Форм на согласовании</div>
      </mat-card>
      <mat-card class="ic-tile" routerLink="/third-parties">
        <div class="ic-tile-value">{{ d.expiringContracts }}</div>
        <div class="ic-tile-label">Договоров истекает за 30 дней</div>
      </mat-card>
      <mat-card class="ic-tile">
        <div class="ic-tile-value">{{ d.publishedForms }}</div>
        <div class="ic-tile-label">Опубликованных форм</div>
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
  readonly notificationColumns = ['recipient', 'subject', 'status'];

  constructor() {
    this.api.dashboard().subscribe((data) => this.data.set(data));
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
}

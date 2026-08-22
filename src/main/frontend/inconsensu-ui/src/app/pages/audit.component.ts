import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { AccessRow, ApiService, AuditEventRow, Dictionaries, VerificationRow } from '../api.service';

/**
 * UI-15: журнал аудита.
 *
 * Три вкладки одного раздела: события системы, доступ к персональным данным и проверки целостности
 * цепочки. Проверка запускается в фоне — список сразу показывает запись «выполняется» (FR-10.3).
 */
@Component({
  selector: 'ic-audit',
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
  ],
  template: `
    <h1 class="ic-title">Аудит</h1>

    <mat-tab-group class="ic-block">
      <mat-tab label="События">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline">
                <mat-label>Тип события</mat-label>
                <mat-select [(ngModel)]="eventType" (ngModelChange)="loadEvents()">
                  <mat-option value="">Все</mat-option>
                  <mat-option *ngFor="let item of dictionaries()?.auditEventTypes" [value]="item.code">
                    {{ item.nameRu }}
                  </mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Кто действовал</mat-label>
                <input matInput [(ngModel)]="actorId" (keyup.enter)="loadEvents()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="from" (change)="loadEvents()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="to" (change)="loadEvents()" />
              </mat-form-field>
            </mat-card-content>
          </mat-card>

          <mat-card class="ic-block">
            <table mat-table [dataSource]="events()" class="ic-table" *ngIf="events().length">
              <ng-container matColumnDef="occurredAt">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let row">{{ row.occurredAt }}</td>
              </ng-container>
              <ng-container matColumnDef="aggregate">
                <th mat-header-cell *matHeaderCellDef>Объект</th>
                <td mat-cell *matCellDef="let row">{{ row.aggregateType }}</td>
              </ng-container>
              <ng-container matColumnDef="eventType">
                <th mat-header-cell *matHeaderCellDef>Событие</th>
                <td mat-cell *matCellDef="let row">{{ row.eventTypeRu }}</td>
              </ng-container>
              <ng-container matColumnDef="actor">
                <th mat-header-cell *matHeaderCellDef>Кто</th>
                <td mat-cell *matCellDef="let row">{{ row.actor }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="eventColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: eventColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!events().length">Событий за выбранный период нет.</p>
            <p class="ic-muted" *ngIf="eventsTotal() > events().length">
              Показаны первые {{ events().length }} из {{ eventsTotal() }}. Сузьте период или фильтр.
            </p>
          </mat-card>
        </div>
      </mat-tab>

      <mat-tab label="Доступ к персональным данным">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content class="ic-filters">
              <mat-form-field appearance="outline" class="ic-grow">
                <mat-label>Обращение (адрес операции)</mat-label>
                <input matInput [(ngModel)]="endpoint" (keyup.enter)="loadAccess()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>С даты</mat-label>
                <input matInput type="date" [(ngModel)]="accessFrom" (change)="loadAccess()" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>По дату</mat-label>
                <input matInput type="date" [(ngModel)]="accessTo" (change)="loadAccess()" />
              </mat-form-field>
            </mat-card-content>
          </mat-card>

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
              <ng-container matColumnDef="requestId">
                <th mat-header-cell *matHeaderCellDef>Номер запроса</th>
                <td mat-cell *matCellDef="let row">{{ row.requestId }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="accessColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: accessColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!access().length">Обращений за выбранный период нет.</p>
          </mat-card>
        </div>
      </mat-tab>

      <mat-tab label="Целостность">
        <div class="ic-tab-body">
          <mat-card class="ic-block">
            <mat-card-content>
              <p>
                Проверка пересчитывает цепочку хешей журнала целиком. Она идёт в фоне: запись появится
                в списке сразу, а результат — по завершении.
              </p>
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
            <p class="ic-empty" *ngIf="!verifications().length">Проверок ещё не было.</p>
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
  readonly verifications = signal<VerificationRow[]>([]);
  readonly dictionaries = signal<Dictionaries | null>(null);

  readonly eventColumns = ['occurredAt', 'aggregate', 'eventType', 'actor'];
  readonly accessColumns = ['occurredAt', 'user', 'endpoint', 'requestId'];
  readonly verificationColumns = ['startedAt', 'startedBy', 'result', 'counts'];

  eventType = '';
  actorId = '';
  from = '';
  to = '';
  endpoint = '';
  accessFrom = '';
  accessTo = '';

  constructor() {
    this.api.dictionaries().subscribe((dictionaries) => this.dictionaries.set(dictionaries));
    this.loadEvents();
    this.loadAccess();
    this.api.verifications().subscribe((rows) => this.verifications.set(rows));
  }

  loadEvents(): void {
    const filters: Record<string, string> = {};
    if (this.eventType) {
      filters['eventType'] = this.eventType;
    }
    if (this.actorId.trim()) {
      filters['actorId'] = this.actorId.trim();
    }
    if (this.from) {
      filters['from'] = this.from;
    }
    if (this.to) {
      filters['to'] = this.to;
    }
    this.api.auditEvents(filters).subscribe((page) => {
      this.events.set(page.rows);
      this.eventsTotal.set(page.total);
    });
  }

  loadAccess(): void {
    const filters: Record<string, string> = {};
    if (this.endpoint.trim()) {
      filters['endpoint'] = this.endpoint.trim();
    }
    if (this.accessFrom) {
      filters['from'] = this.accessFrom;
    }
    if (this.accessTo) {
      filters['to'] = this.accessTo;
    }
    this.api.accessLog(filters).subscribe((page) => this.access.set(page.rows));
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

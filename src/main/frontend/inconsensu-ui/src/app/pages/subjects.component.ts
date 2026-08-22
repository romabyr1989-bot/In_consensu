import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiService, SubjectRow } from '../api.service';

/**
 * UI-3: поиск клиента.
 *
 * Тип запроса определяется сервером: «+» или цифры — телефон, «@» — почта, буквы — ФИО, иначе внешний
 * идентификатор. Запрос уходит POST-ом, потому что телефону, почте и ФИО нельзя попадать в адрес (UI-0.10).
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
    MatFormFieldModule,
    MatInputModule,
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
      </mat-card-content>
    </mat-card>

    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-card class="ic-block" *ngIf="searched()">
      <table mat-table [dataSource]="rows()" class="ic-table" *ngIf="rows().length">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Клиент</th>
          <td mat-cell *matCellDef="let row">
            <a [routerLink]="['/subjects', row.id]">{{ row.fullName }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="externalId">
          <th mat-header-cell *matHeaderCellDef>Идентификатор</th>
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
            <span class="ic-count warn" [attr.aria-label]="'Истекающих согласий: ' + row.expiring">{{ row.expiring }}</span>
            <span class="ic-count danger" [attr.aria-label]="'Отозванных согласий: ' + row.revoked">{{ row.revoked }}</span>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!loading() && !rows().length">
        Ничего не найдено. Проверьте формат номера телефона или введите не менее трёх букв фамилии.
      </p>
    </mat-card>
  `,
})
export class SubjectsComponent {
  private readonly api = inject(ApiService);
  readonly rows = signal<SubjectRow[]>([]);
  readonly loading = signal(false);
  readonly searched = signal(false);
  query = '';
  readonly columns = ['name', 'externalId', 'phone', 'email', 'consents'];

  search(): void {
    if (!this.query.trim()) {
      return;
    }
    this.loading.set(true);
    this.searched.set(true);
    this.api.searchSubjects(this.query).subscribe((rows) => {
      this.rows.set(rows);
      this.loading.set(false);
    });
  }

  reset(): void {
    this.query = '';
    this.rows.set([]);
    this.searched.set(false);
  }
}

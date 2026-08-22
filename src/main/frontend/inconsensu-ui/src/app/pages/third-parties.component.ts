import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiService, PartyRow } from '../api.service';

/** UI-11: справочник третьих лиц — реквизиты, договор с бейджем срока, категории и счётчики согласий. */
@Component({
  selector: 'ic-third-parties',
  standalone: true,
  imports: [
    RouterLink,
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatChipsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Третьи лица</h1>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline">
          <mat-label>Договор</mat-label>
          <mat-select [(ngModel)]="contract" (selectionChange)="load()">
            <mat-option value="">все</mat-option>
            <mat-option value="EXPIRING">истекает или истёк</mat-option>
          </mat-select>
        </mat-form-field>
        <button mat-button (click)="reset()">Сбросить фильтры</button>
      </mat-card-content>
    </mat-card>

    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="rows()" class="ic-table">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Наименование</th>
          <td mat-cell *matCellDef="let row">
            <a [routerLink]="['/third-parties', row.id]">{{ row.name }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="inn">
          <th mat-header-cell *matHeaderCellDef>ИНН</th>
          <td mat-cell *matCellDef="let row">{{ row.inn }}</td>
        </ng-container>
        <ng-container matColumnDef="role">
          <th mat-header-cell *matHeaderCellDef>Роль</th>
          <td mat-cell *matCellDef="let row">{{ row.roleRu }}</td>
        </ng-container>
        <ng-container matColumnDef="contract">
          <th mat-header-cell *matHeaderCellDef>Договор</th>
          <td mat-cell *matCellDef="let row">
            {{ row.contractNumber }}
            <span class="ic-muted" *ngIf="row.contractUntil">до {{ row.contractUntil }}</span>
            <mat-chip *ngIf="row.contractBadge" [class]="'ic-chip ' + row.contractBadgeKind">
              {{ row.contractBadge }}
            </mat-chip>
          </td>
        </ng-container>
        <ng-container matColumnDef="categories">
          <th mat-header-cell *matHeaderCellDef>Категории ПДн</th>
          <td mat-cell *matCellDef="let row">{{ row.categoriesRu }}</td>
        </ng-container>
        <ng-container matColumnDef="consents">
          <th mat-header-cell *matHeaderCellDef>Согласия</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-count ok" [attr.aria-label]="'Действующих согласий: ' + row.consentsActive">
              {{ row.consentsActive }}</span>
            <span class="ic-count warn" [attr.aria-label]="'Истекающих согласий: ' + row.consentsExpiring">
              {{ row.consentsExpiring }}</span>
            <span class="ic-count danger" [attr.aria-label]="'Отозванных согласий: ' + row.consentsRevoked">
              {{ row.consentsRevoked }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="active">
          <th mat-header-cell *matHeaderCellDef>Активно</th>
          <td mat-cell *matCellDef="let row">{{ row.active ? 'активно' : 'выключено' }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!loading() && !rows().length">
        Ничего не найдено. Снимите фильтр по договору, чтобы увидеть весь справочник.
      </p>
    </mat-card>
  `,
})
export class ThirdPartiesComponent {
  private readonly api = inject(ApiService);
  readonly rows = signal<PartyRow[]>([]);
  readonly loading = signal(true);
  contract = '';
  readonly columns = ['name', 'inn', 'role', 'contract', 'categories', 'consents', 'active'];

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.thirdParties(this.contract).subscribe((rows) => {
      this.rows.set(rows);
      this.loading.set(false);
    });
  }

  reset(): void {
    this.contract = '';
    this.load();
  }
}

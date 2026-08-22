import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, ImportPage, JobDetails } from '../api.service';

/**
 * UI-12: импорт базы клиентов.
 *
 * Пробный запуск включён по умолчанию: он проверяет файл целиком и ничего не пишет, а боевой импорт
 * запускается кнопкой по тому же файлу — повторно загружать его не нужно (FR-4.5).
 */
@Component({
  selector: 'ic-import',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatFormFieldModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Импорт базы клиентов</h1>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <input type="file" accept=".csv,text/csv" (change)="pick($event)" />
        <mat-form-field appearance="outline">
          <mat-label>Источник согласий</mat-label>
          <mat-select [(ngModel)]="source">
            <mat-option *ngFor="let item of page()?.sources" [value]="item.code">{{ item.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-checkbox [(ngModel)]="dryRun">Пробный запуск: только проверить файл</mat-checkbox>
        <button mat-flat-button color="primary" [disabled]="!file || uploading()" (click)="upload()">
          Загрузить
        </button>
      </mat-card-content>
      <mat-card-content>
        <p class="ic-muted">
          Файл CSV с разделителем «;» в кодировке UTF-8. Обязательные колонки: внешний идентификатор,
          фамилия, имя, телефон или email, код типа согласия, дата получения. Дата — в формате ДД.ММ.ГГГГ.
        </p>
      </mat-card-content>
    </mat-card>

    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="details() as current">
      <mat-card-header>
        <mat-card-title>{{ current.job.fileName }}</mat-card-title>
        <mat-card-subtitle>
          {{ current.job.dryRun ? 'пробный запуск' : 'боевой импорт' }} · {{ current.job.statusRu }}
        </mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <mat-progress-bar
          [mode]="current.job.status === 'RUNNING' ? 'determinate' : 'determinate'"
          [value]="current.job.percent"
        ></mat-progress-bar>
        <p>
          Всего строк: {{ current.job.total }} · принято: {{ current.job.imported }} ·
          отклонено: {{ current.job.rejected }}
        </p>
        <div class="ic-actions">
          <button
            mat-flat-button
            color="primary"
            *ngIf="current.job.dryRun && current.job.status === 'COMPLETED' && !current.job.rejected"
            (click)="runForReal(current.job.id)"
          >
            Запустить боевой импорт
          </button>
          <a mat-stroked-button *ngIf="current.job.rejected" [href]="'/ui/api/import/' + current.job.id + '/report.csv'">
            Скачать отчёт по строкам
          </a>
        </div>

        <table mat-table [dataSource]="current.report" class="ic-table ic-gap" *ngIf="current.report.length">
          <ng-container matColumnDef="line">
            <th mat-header-cell *matHeaderCellDef>Строка</th>
            <td mat-cell *matCellDef="let row">{{ row['line'] }}</td>
          </ng-container>
          <ng-container matColumnDef="field">
            <th mat-header-cell *matHeaderCellDef>Поле</th>
            <td mat-cell *matCellDef="let row">{{ row['field'] }}</td>
          </ng-container>
          <ng-container matColumnDef="reason">
            <th mat-header-cell *matHeaderCellDef>Причина</th>
            <td mat-cell *matCellDef="let row">{{ row['message'] || row['reason'] }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="reportColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: reportColumns"></tr>
        </table>
      </mat-card-content>
    </mat-card>

    <mat-card class="ic-block">
      <mat-card-header><mat-card-title>Прошлые загрузки</mat-card-title></mat-card-header>
      <table mat-table [dataSource]="page()?.rows ?? []" class="ic-table" *ngIf="page()?.rows?.length">
        <ng-container matColumnDef="fileName">
          <th mat-header-cell *matHeaderCellDef>Файл</th>
          <td mat-cell *matCellDef="let row">
            <a href="javascript:void(0)" (click)="open(row.id)">{{ row.fileName }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="mode">
          <th mat-header-cell *matHeaderCellDef>Режим</th>
          <td mat-cell *matCellDef="let row">{{ row.dryRun ? 'пробный' : 'боевой' }}</td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Статус</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-badge" [class]="'ic-badge ' + badge(row)">{{ row.statusRu }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="counts">
          <th mat-header-cell *matHeaderCellDef>Строки</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-count ok">{{ row.imported }}</span>
            <span class="ic-count danger">{{ row.rejected }}</span>
            <span class="ic-muted">из {{ row.total }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="startedBy">
          <th mat-header-cell *matHeaderCellDef>Кто загрузил</th>
          <td mat-cell *matCellDef="let row">{{ row.startedBy }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!page()?.rows?.length">Загрузок ещё не было.</p>
    </mat-card>
  `,
})
export class ImportComponent {
  private readonly api = inject(ApiService);

  readonly page = signal<ImportPage | null>(null);
  readonly details = signal<JobDetails | null>(null);
  readonly uploading = signal(false);
  readonly error = signal('');

  readonly columns = ['fileName', 'mode', 'status', 'counts', 'startedBy'];
  readonly reportColumns = ['line', 'field', 'reason'];

  file: File | null = null;
  dryRun = true;
  source = 'CLIENT_BASE_IMPORT';

  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.load();
  }

  load(): void {
    this.api.jobs().subscribe((page) => this.page.set(page));
  }

  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file = input.files?.length ? input.files[0] : null;
  }

  upload(): void {
    if (!this.file) {
      return;
    }
    this.uploading.set(true);
    this.error.set('');
    this.api.upload(this.file, this.dryRun, this.source).subscribe({
      next: (job) => {
        this.uploading.set(false);
        this.open(job.id);
        this.load();
      },
      error: (failure) => {
        this.uploading.set(false);
        this.error.set(failure?.error?.detail ?? 'Файл не принят: проверьте формат и кодировку.');
      },
    });
  }

  /** Пока задача выполняется, состояние перечитывается: иначе прогресс замирает на нуле. */
  open(id: string): void {
    this.api.job(id).subscribe((details) => {
      this.details.set(details);
      if (this.timer) {
        clearTimeout(this.timer);
        this.timer = null;
      }
      if (details.job.status === 'RUNNING' || details.job.status === 'PENDING') {
        this.timer = setTimeout(() => this.open(id), 2000);
      } else {
        this.load();
      }
    });
  }

  runForReal(id: string): void {
    this.api.runForReal(id).subscribe({
      next: (job) => this.open(job.id),
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Боевой импорт не запущен.'),
    });
  }

  badge(row: { status: string; rejected: number }): string {
    if (row.status === 'FAILED') {
      return 'danger';
    }
    if (row.status === 'COMPLETED') {
      return row.rejected ? 'warn' : 'ok';
    }
    return 'warn';
  }
}

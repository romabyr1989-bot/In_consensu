import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, ImportPage, JobDetails, JobRow } from '../api.service';
import { ConfirmDialogComponent } from './confirm-dialog.component';

/** Колонка файла импорта: состав тот же, что принимает загрузка (FR-4.5). */
interface FormatColumn {
  name: string;
  required: string;
  description: string;
}

/**
 * UI-12: импорт базы клиентов.
 *
 * Пробный запуск включён по умолчанию: он проверяет файл целиком и ничего не пишет, а боевой импорт
 * запускается кнопкой по тому же файлу — повторно загружать его не нужно (FR-4.5). Описание формата
 * лежит рядом с загрузкой, а не на отдельной странице: сотрудник смотрит его, не теряя выбранный файл.
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
    MatDialogModule,
    MatExpansionModule,
    MatProgressBarModule,
  ],
  template: `
    <h1 class="ic-title">Импорт базы клиентов</h1>

    <mat-card class="ic-block">
      <mat-card-content class="ic-filters">
        <!-- Родное поле выбора файла выглядит чужеродно среди кнопок: прячем его и открываем кнопкой. -->
        <input
          #picker
          type="file"
          class="ic-hidden-input"
          accept=".csv,.json,text/csv,application/json"
          (change)="pick($event)"
        />
        <button mat-stroked-button type="button" (click)="picker.click()">Выбрать файл</button>
        <span class="ic-muted">{{ file?.name || 'файл не выбран' }}</span>
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

      <mat-expansion-panel class="ic-block">
        <mat-expansion-panel-header>
          <mat-panel-title>Каким должен быть файл</mat-panel-title>
          <mat-panel-description>колонки, даты, коды источников и пример</mat-panel-description>
        </mat-expansion-panel-header>

        <div class="ic-actions">
          <a mat-stroked-button href="/assets/sample-import.csv" download>Скачать пример файла</a>
        </div>
        <p class="ic-gap">
          В JSON тот же состав полей: массив объектов, где ключи — имена колонок из таблицы ниже.
          Незнакомые колонки сервер пропускает.
        </p>

        <table mat-table [dataSource]="formatColumns" class="ic-table ic-gap">
          <ng-container matColumnDef="name">
            <th mat-header-cell *matHeaderCellDef>Колонка</th>
            <td mat-cell *matCellDef="let column"><span class="ic-code">{{ column.name }}</span></td>
          </ng-container>
          <ng-container matColumnDef="required">
            <th mat-header-cell *matHeaderCellDef>Нужна ли</th>
            <td mat-cell *matCellDef="let column">{{ column.required }}</td>
          </ng-container>
          <ng-container matColumnDef="description">
            <th mat-header-cell *matHeaderCellDef>Что писать</th>
            <td mat-cell *matCellDef="let column">{{ column.description }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="formatColumnNames"></tr>
          <tr mat-row *matRowDef="let column; columns: formatColumnNames"></tr>
        </table>

        <p class="ic-muted ic-gap">
          Заполните <span class="ic-code">document_ref</span> или <span class="ic-code">note</span>:
          без основания импортированное согласие нечем подтвердить (FR-4.2).
        </p>

        <h3 class="ic-section-title">Даты</h3>
        <p>
          В колонках <span class="ic-code">granted_at</span> и <span class="ic-code">valid_until</span>
          принимается любая из трёх записей:
        </p>
        <ul>
          <li><span class="ic-code">2025-03-12T09:41:00+03:00</span> — момент с указанием часового пояса;</li>
          <li><span class="ic-code">2025-03-12</span> — дата ISO;</li>
          <li><span class="ic-code">12.03.2025</span> — дата в привычном виде.</li>
        </ul>
        <p class="ic-muted">
          Дата без времени читается как начало дня в часовом поясе оператора, иначе согласие сдвинулось
          бы на сутки.
        </p>

        <h3 class="ic-section-title">Коды источников</h3>
        <p>
          Значение колонки <span class="ic-code">source</span> — его ждут в каждой строке. Выбор
          «Источник согласий» над загрузкой помечает только саму задачу и строки не подменяет.
        </p>
        <ul>
          <li *ngFor="let item of page()?.sources">
            <span class="ic-code">{{ item.code }}</span> — {{ item.nameRu }}
          </li>
        </ul>
        <p class="ic-empty" *ngIf="!page()?.sources?.length">
          Список источников не загрузился. Обновите страницу.
        </p>
      </mat-expansion-panel>
    </mat-card>

    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngIf="details() as current">
      <mat-card-header>
        <mat-card-title>{{ current.job.fileName }}</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <mat-progress-bar mode="determinate" [value]="current.job.percent"></mat-progress-bar>
        <p>
          Всего строк: {{ current.job.total }} · принято: {{ current.job.imported }} ·
          отклонено: {{ current.job.rejected }}
        </p>
        <div class="ic-actions">
          <button
            mat-flat-button
            color="primary"
            *ngIf="current.job.dryRun && current.job.status === 'COMPLETED' && !current.job.rejected"
            (click)="runForReal(current.job)"
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
          <th mat-header-cell *matHeaderCellDef>Чем закончилось</th>
          <td mat-cell *matCellDef="let row">
            <span class="ic-badge" [class]="'ic-badge ' + badge(row)">{{ outcome(row) }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="counts">
          <th mat-header-cell *matHeaderCellDef>Строки</th>
          <!-- Слова говорят, что произошло, цвет — насколько это хорошо (UI-0.7). -->
          <td mat-cell *matCellDef="let row">
            {{ row.dryRun ? 'подошло' : 'принято' }}
            <span class="ic-num ok">{{ row.imported }}</span> из {{ row.total }}<span *ngIf="row.rejected"
              >, отклонено <span class="ic-num danger">{{ row.rejected }}</span></span
            >
          </td>
        </ng-container>
        <ng-container matColumnDef="startedBy">
          <th mat-header-cell *matHeaderCellDef>Кто загрузил</th>
          <td mat-cell *matCellDef="let row">{{ row.startedBy }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!page()?.rows?.length">
        Загрузок ещё не было. Выберите файл наверху страницы и начните с пробного запуска — он покажет
        ошибки, ничего не записывая.
      </p>
    </mat-card>
  `,
})
export class ImportComponent {
  private readonly api = inject(ApiService);
  private readonly dialogs = inject(MatDialog);

  readonly page = signal<ImportPage | null>(null);
  readonly details = signal<JobDetails | null>(null);
  readonly uploading = signal(false);
  readonly error = signal('');

  readonly columns = ['fileName', 'mode', 'status', 'counts', 'startedBy'];
  readonly reportColumns = ['line', 'field', 'reason'];
  readonly formatColumnNames = ['name', 'required', 'description'];

  readonly formatColumns: FormatColumn[] = [
    {
      name: 'external_id',
      required: 'обязательна',
      description: 'Идентификатор клиента в мастер-системе: по нему клиент создаётся или обновляется',
    },
    { name: 'last_name', required: 'обязательна', description: 'Фамилия' },
    { name: 'first_name', required: 'обязательна', description: 'Имя' },
    { name: 'middle_name', required: 'по желанию', description: 'Отчество' },
    {
      name: 'phone',
      required: 'по желанию',
      description: 'Телефон в любом виде: +7 916 000-00-41, 8 (916) 000-00-41',
    },
    { name: 'email', required: 'по желанию', description: 'Адрес электронной почты' },
    {
      name: 'consent_type_code',
      required: 'обязательна',
      description: 'Код типа согласия из справочника, например PDN_PROCESSING',
    },
    { name: 'form_code', required: 'по желанию', description: 'Код формы, по которой получено согласие' },
    {
      name: 'form_version',
      required: 'по желанию',
      description: 'Номер версии формы; без него берётся опубликованная',
    },
    { name: 'granted_at', required: 'обязательна', description: 'Дата или момент выражения согласия' },
    { name: 'valid_until', required: 'по желанию', description: 'Срок действия; пусто — бессрочно или до отзыва' },
    { name: 'source', required: 'обязательна', description: 'Код источника согласия из списка ниже' },
    {
      name: 'source_ref',
      required: 'по желанию',
      description: 'Ссылка на документ: номер договора, номер обращения',
    },
    {
      name: 'third_party_inn',
      required: 'по желанию',
      description: 'ИНН третьего лица; обязателен для типов, которым третье лицо нужно',
    },
    {
      name: 'pdn_categories',
      required: 'по желанию',
      description: 'Категории ПДн через запятую, точку с запятой или «|»; пусто — берутся из пункта формы',
    },
    { name: 'document_ref', required: 'одна из двух', description: 'Ссылка на скан документа в хранилище' },
    { name: 'note', required: 'одна из двух', description: 'Текстовое основание, если скана нет' },
  ];

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

  /** Расширение проверяется до отправки: иначе о неподходящем файле сотрудник узнаёт только с сервера. */
  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const chosen = input.files?.length ? input.files[0] : null;
    if (chosen && !/\.(csv|json)$/i.test(chosen.name)) {
      this.file = null;
      input.value = '';
      this.error.set('Такой файл не подойдёт. Нужна таблица .csv или массив .json.');
      return;
    }
    this.file = chosen;
    this.error.set('');
  }

  upload(): void {
    if (!this.file) {
      return;
    }
    if (this.dryRun) {
      this.send();
      return;
    }
    this.dialogs
      .open(ConfirmDialogComponent, {
        data: {
          title: 'Загрузить файл сразу в базу?',
          consequences:
            'Галочка «Пробный запуск» снята: строки уйдут в реестр согласий, как только сервер разберёт ' +
            'файл. Клиенты будут созданы или обновлены, согласия записаны как полученные, а сколько ' +
            'строк оказалось в файле, станет видно уже после записи. Надёжнее сначала проверить файл ' +
            'пробным запуском: он покажет ошибки и ничего не изменит.',
          confirmLabel: 'Загрузить в базу',
          danger: true,
        },
      })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          this.send();
        }
      });
  }

  private send(): void {
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

  runForReal(job: JobRow): void {
    this.dialogs
      .open(ConfirmDialogComponent, {
        data: {
          title: 'Запустить боевой импорт?',
          consequences:
            `В реестр согласий уйдёт строк: ${job.total}. По файлу «${job.fileName}» клиенты будут ` +
            'созданы или обновлены, а согласия записаны как полученные. Отменить импорт одной кнопкой ' +
            'нельзя: лишние согласия придётся отзывать по одному.',
          confirmLabel: 'Запустить импорт',
          danger: true,
        },
      })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (!confirmed) {
          return;
        }
        this.error.set('');
        this.api.runForReal(job.id).subscribe({
          next: (started) => this.open(started.id),
          error: (failure) => this.error.set(failure?.error?.detail ?? 'Боевой импорт не запущен.'),
        });
      });
  }

  /** Чем закончилась загрузка — словами: «завершена» ничего не говорит о том, попали ли строки в базу. */
  outcome(row: { status: string; statusRu: string; dryRun: boolean; rejected: number; imported: number }): string {
    if (row.status === 'RUNNING' || row.status === 'PENDING') {
      return 'выполняется';
    }
    if (row.status === 'FAILED') {
      return 'не завершилась';
    }
    if (row.rejected) {
      return row.dryRun ? 'проверено, есть ошибки' : 'загружено частично';
    }
    return row.dryRun ? 'проверено, ошибок нет' : 'загружено полностью';
  }

  /** Строки словами: пары чисел в кружках сотрудник читал как код, а не как результат. */
  rowsSummary(row: { total: number; imported: number; rejected: number; dryRun: boolean }): string {
    const accepted = row.dryRun ? 'подошло' : 'принято';
    const base = `${accepted} ${row.imported} из ${row.total}`;
    return row.rejected ? `${base}, отклонено ${row.rejected}` : base;
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

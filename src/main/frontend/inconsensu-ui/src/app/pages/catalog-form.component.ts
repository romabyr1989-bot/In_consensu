import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, DiffLine, FormDetails, WorkflowEntry } from '../api.service';
import { ConfirmData, ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * Одна версия формы: просмотр, согласование и решения (UI-9, UI-10).
 *
 * Прежний интерфейс разводил это по трём экранам, и сотрудник переходил между ними ради одного и того
 * же документа. Здесь экран один, а набор действий определяется статусом и ролью: на согласовании
 * видны «Одобрить» и «Отклонить», у одобренной — «Опубликовать», у опубликованной — «Новая версия».
 *
 * Чек-лист реквизитов ч. 4 ст. 9 152-ФЗ показан и здесь, а не только в конструкторе: согласующий
 * решает по тем же реквизитам, что и автор, и без них решение принимать не по чему.
 */
@Component({
  selector: 'ic-catalog-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <ng-container *ngIf="form() as row">
      <div class="ic-card-head">
        <div>
          <h1 class="ic-title">{{ row.title }}</h1>
          <div class="ic-subtitle">
            {{ row.code }} · версия {{ row.version }} ·
            <span class="ic-badge" [class]="'ic-badge ' + badge(row.status)">{{ row.statusRu }}</span>
            <span *ngIf="row.issuedConsents"> · выдано согласий: {{ row.issuedConsents }}</span>
          </div>
        </div>
        <div class="ic-actions">
          <a mat-stroked-button *ngIf="row.editable" [routerLink]="['/catalog/forms', row.id, 'edit']">Конструктор</a>
          <button mat-flat-button color="primary" *ngIf="row.status === 'DRAFT'" (click)="act('submit')">
            На согласование
          </button>
          <button mat-flat-button color="primary" *ngIf="row.status === 'ON_REVIEW'" (click)="act('approve')">
            Одобрить
          </button>
          <button mat-stroked-button color="warn" *ngIf="row.status === 'ON_REVIEW'" (click)="rejecting.set(true)">
            Отклонить
          </button>
          <button mat-flat-button color="primary" *ngIf="row.status === 'APPROVED'" (click)="confirmPublish(row)">
            Опубликовать
          </button>
          <button mat-stroked-button *ngIf="row.status === 'PUBLISHED'" (click)="newVersion()">Новая версия</button>
          <button mat-stroked-button color="warn" *ngIf="row.status === 'PUBLISHED'" (click)="confirmArchive(row)">
            В архив
          </button>
        </div>
      </div>

      <div class="ic-note" *ngIf="message()">{{ message() }}</div>
      <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

      <mat-card class="ic-block" *ngIf="rejecting()">
        <mat-card-content class="ic-filters">
          <mat-form-field appearance="outline" class="ic-grow">
            <mat-label>Что исправить</mat-label>
            <input matInput [(ngModel)]="comment" />
          </mat-form-field>
          <button mat-flat-button color="warn" [disabled]="!comment.trim()" (click)="act('reject', comment)">
            Отклонить
          </button>
          <button mat-stroked-button (click)="rejecting.set(false)">Отмена</button>
        </mat-card-content>
      </mat-card>

      <mat-card class="ic-block">
        <mat-card-header>
          <mat-card-title>Реквизиты по закону</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="ic-requisite" *ngFor="let requisite of row.checklist">
            <mat-icon [class]="requisite.satisfied ? 'ic-ok' : 'ic-missing'">
              {{ requisite.satisfied ? 'check_circle' : 'radio_button_unchecked' }}
            </mat-icon>
            <span>{{ requisite.nameRu }}</span>
            <span class="ic-badge" [class]="requisite.satisfied ? 'ic-badge ok' : 'ic-badge warn'">
              {{ requisite.satisfied ? 'есть' : 'не хватает' }}
            </span>
          </div>
          <p class="ic-empty" *ngIf="!row.checklist.length">
            Реквизиты не проверены — откройте форму в конструкторе, там проверка идёт при каждом сохранении.
          </p>

          <div class="ic-gap" *ngIf="row.violations.length">
            <div class="ic-danger">Нарушения — с ними форму не отправить на согласование:</div>
            <div class="ic-finding" *ngFor="let finding of row.violations">
              {{ finding.itemNumber ? 'Пункт ' + finding.itemNumber + ': ' : '' }}{{ finding.messageRu }}
            </div>
          </div>
          <div class="ic-gap" *ngIf="row.warnings.length">
            <div class="ic-warn">Предупреждения — публиковать не мешают, но их стоит учесть в решении:</div>
            <div class="ic-finding" *ngFor="let finding of row.warnings">
              {{ finding.itemNumber ? 'Пункт ' + finding.itemNumber + ': ' : '' }}{{ finding.messageRu }}
            </div>
          </div>
          <div class="ic-badge ok ic-gap" *ngIf="row.valid && !row.warnings.length">
            Все обязательные реквизиты на месте.
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card class="ic-block" *ngIf="row.approvals.length">
        <mat-card-header><mat-card-title>Решения по маршруту</mat-card-title></mat-card-header>
        <mat-card-content>
          <div class="ic-approval" *ngFor="let approval of row.approvals">
            <span class="ic-badge" [class]="approval.approved ? 'ic-badge ok' : 'ic-badge warn'">
              {{ approval.roleRu }}
            </span>
            <span *ngIf="approval.approved">одобрено · {{ approval.actor }} · {{ approval.decidedAt }}</span>
            <span *ngIf="!approval.approved" class="ic-muted">решения ещё нет</span>
            <span class="ic-muted" *ngIf="approval.comment">{{ approval.comment }}</span>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card class="ic-block">
        <mat-card-header><mat-card-title>Даты и авторы</mat-card-title></mat-card-header>
        <mat-card-content>
          <dl class="ic-facts">
            <dt>Версия</dt>
            <dd>{{ row.version }}</dd>
            <dt>Статус</dt>
            <dd>
              <span class="ic-badge" [class]="'ic-badge ' + badge(row.status)">{{ row.statusRu }}</span>
            </dd>
            <dt>Выдано согласий</dt>
            <dd>{{ row.issuedConsents }}</dd>
            <ng-container *ngIf="firstEvent(row) as first">
              <dt>Первое событие</dt>
              <dd>{{ first.eventTypeRu }} · {{ first.at }} · {{ first.actor || '—' }}</dd>
            </ng-container>
            <ng-container *ngIf="lastEvent(row) as last">
              <dt>Последнее событие</dt>
              <dd>{{ last.eventTypeRu }} · {{ last.at }} · {{ last.actor || '—' }}</dd>
            </ng-container>
          </dl>
          <p class="ic-empty" *ngIf="!row.history.length">
            Событий по версии ещё нет: первое появится, когда форму отправят на согласование.
          </p>
        </mat-card-content>
      </mat-card>

      <mat-tab-group class="ic-block">
        <mat-tab label="Текст">
          <div class="ic-tab-body">
            <div class="ic-form-text" [innerHTML]="row.previewHtml"></div>
            <div class="ic-checksum" *ngIf="row.checksum">
              <span>Контрольная сумма: {{ row.checksum }}</span>
              <button mat-stroked-button (click)="copyChecksum(row.checksum)">
                <mat-icon>content_copy</mat-icon>
                Скопировать
              </button>
              <span *ngIf="copyNote()">{{ copyNote() }}</span>
            </div>
          </div>
        </mat-tab>

        <mat-tab label="Пункты">
          <div class="ic-tab-body">
            <table mat-table [dataSource]="row.items" class="ic-table" *ngIf="row.items.length">
              <ng-container matColumnDef="type">
                <th mat-header-cell *matHeaderCellDef>Тип</th>
                <td mat-cell *matCellDef="let item">
                  {{ item.typeRu }}
                  <span class="ic-badge warn" *ngIf="item.mandatory">обязательный</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="text">
                <th mat-header-cell *matHeaderCellDef>Формулировка</th>
                <td mat-cell *matCellDef="let item">{{ item.text }}</td>
              </ng-container>
              <ng-container matColumnDef="purposes">
                <th mat-header-cell *matHeaderCellDef>Цели</th>
                <td mat-cell *matCellDef="let item">{{ item.purposes }}</td>
              </ng-container>
              <ng-container matColumnDef="categories">
                <th mat-header-cell *matHeaderCellDef>Категории ПДн</th>
                <td mat-cell *matCellDef="let item">{{ item.pdnCategoriesRu }}</td>
              </ng-container>
              <ng-container matColumnDef="thirdParty">
                <th mat-header-cell *matHeaderCellDef>Третье лицо</th>
                <td mat-cell *matCellDef="let item">{{ item.thirdPartyRu }}</td>
              </ng-container>
              <ng-container matColumnDef="validity">
                <th mat-header-cell *matHeaderCellDef>Срок</th>
                <td mat-cell *matCellDef="let item">{{ item.validityRu }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="itemColumns"></tr>
              <tr mat-row *matRowDef="let item; columns: itemColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!row.items.length">Пунктов пока нет.</p>
          </div>
        </mat-tab>

        <mat-tab label="Отличия от прошлой версии">
          <div class="ic-tab-body">
            <pre class="ic-diff" *ngIf="diff().length"><span
              *ngFor="let line of diff()"
              [class]="'ic-diff-' + line.kind.toLowerCase()"
            >{{ marker(line) }}{{ line.text }}
</span></pre>
            <p class="ic-empty" *ngIf="!diff().length">Предыдущей опубликованной версии нет — сравнивать не с чем.</p>
          </div>
        </mat-tab>

        <mat-tab label="История">
          <div class="ic-tab-body">
            <table mat-table [dataSource]="row.history" class="ic-table" *ngIf="row.history.length">
              <ng-container matColumnDef="at">
                <th mat-header-cell *matHeaderCellDef>Когда</th>
                <td mat-cell *matCellDef="let entry">{{ entry.at }}</td>
              </ng-container>
              <ng-container matColumnDef="event">
                <th mat-header-cell *matHeaderCellDef>Событие</th>
                <td mat-cell *matCellDef="let entry">{{ entry.eventTypeRu }}</td>
              </ng-container>
              <ng-container matColumnDef="actor">
                <th mat-header-cell *matHeaderCellDef>Кто</th>
                <td mat-cell *matCellDef="let entry">{{ entry.actor }}</td>
              </ng-container>
              <ng-container matColumnDef="comment">
                <th mat-header-cell *matHeaderCellDef>Комментарий</th>
                <td mat-cell *matCellDef="let entry">{{ entry.comment || '—' }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="historyColumns"></tr>
              <tr mat-row *matRowDef="let entry; columns: historyColumns"></tr>
            </table>
            <p class="ic-empty" *ngIf="!row.history.length">Событий по форме нет.</p>
          </div>
        </mat-tab>

        <mat-tab label="Версии">
          <div class="ic-tab-body">
            <div class="ic-awaiting" *ngFor="let version of row.versions">
              <a [routerLink]="['/catalog/forms', version.id]">версия {{ version.version }}</a>
              <span class="ic-muted">{{ version.statusRu }}</span>
            </div>
          </div>
        </mat-tab>
      </mat-tab-group>
    </ng-container>
  `,
})
export class CatalogFormComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialogs = inject(MatDialog);

  readonly form = signal<FormDetails | null>(null);
  readonly diff = signal<DiffLine[]>([]);
  readonly loading = signal(false);
  readonly rejecting = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  readonly copyNote = signal('');

  readonly itemColumns = ['type', 'text', 'purposes', 'categories', 'thirdParty', 'validity'];
  readonly historyColumns = ['at', 'event', 'actor', 'comment'];

  comment = '';
  private id = '';

  constructor() {
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    this.copyNote.set('');
    this.api.form(this.id).subscribe((row) => {
      this.form.set(row);
      this.loading.set(false);
    });
    this.api.diff(this.id).subscribe((lines) => this.diff.set(lines));
  }

  act(action: string, comment?: string): void {
    this.error.set('');
    this.api.formAction(this.id, action, comment).subscribe({
      next: (row) => {
        this.form.set(row);
        this.rejecting.set(false);
        this.comment = '';
        this.message.set(this.messageOf(action));
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Действие не выполнено.'),
    });
  }

  /** Публикация меняет документ, по которому выдают согласия, поэтому спрашиваем до, а не сообщаем после. */
  confirmPublish(row: FormDetails): void {
    const current = row.versions.find((version) => version.status === 'PUBLISHED' && version.id !== row.id);
    const replaced = current
      ? ` Действующая сейчас версия ${current.version} уйдёт в архив, но выданные по ней согласия останутся в силе.`
      : '';
    const data: ConfirmData = {
      title: `Опубликовать версию ${row.version}?`,
      consequences:
        `Версия ${row.version} формы «${row.title}» (код ${row.code}) станет действующей: ` +
        `новые согласия будут выдаваться по её тексту.` +
        replaced,
      confirmLabel: 'Опубликовать',
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          this.act('publish');
        }
      });
  }

  confirmArchive(row: FormDetails): void {
    const data: ConfirmData = {
      title: `Отправить версию ${row.version} в архив?`,
      consequences:
        `Версия ${row.version} формы «${row.title}» (код ${row.code}) перестанет применяться: ` +
        `новые согласия по ней выдаваться не будут, а действующей версии у формы не останется, ` +
        `пока не опубликуете следующую. Уже выданные согласия (${row.issuedConsents}) остаются в силе.`,
      confirmLabel: 'В архив',
      danger: true,
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          this.act('archive');
        }
      });
  }

  newVersion(): void {
    this.api.newVersion(this.id).subscribe({
      next: (row) => this.router.navigate(['/catalog/forms', row.id, 'edit']),
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Новая версия не создана.'),
    });
  }

  /** Сумму сверяют с досье согласия, а руками её не перепечатать: 64 знака без пробелов. */
  copyChecksum(checksum: string): void {
    if (!navigator.clipboard) {
      this.copyNote.set('Браузер не дал доступ к буферу обмена — выделите сумму и скопируйте вручную.');
      return;
    }
    navigator.clipboard.writeText(checksum).then(
      () => this.copyNote.set('Скопировано'),
      () => this.copyNote.set('Скопировать не удалось — выделите сумму и скопируйте вручную.'),
    );
  }

  /** История приходит по возрастанию даты, поэтому первое событие — начало, последнее — текущее состояние. */
  firstEvent(row: FormDetails): WorkflowEntry | null {
    return row.history.length ? row.history[0] : null;
  }

  lastEvent(row: FormDetails): WorkflowEntry | null {
    return row.history.length > 1 ? row.history[row.history.length - 1] : null;
  }

  marker(line: DiffLine): string {
    if (line.kind === 'ADDED') {
      return '+ ';
    }
    return line.kind === 'REMOVED' ? '− ' : '  ';
  }

  badge(status: string): string {
    if (status === 'PUBLISHED') {
      return 'ok';
    }
    if (status === 'ARCHIVED') {
      return 'danger';
    }
    return 'warn';
  }

  private messageOf(action: string): string {
    switch (action) {
      case 'submit':
        return 'Форма отправлена на согласование';
      case 'approve':
        return 'Решение записано: одобрено';
      case 'reject':
        return 'Форма отклонена и возвращена в черновик';
      case 'publish':
        return 'Версия опубликована';
      default:
        return 'Версия переведена в архив';
    }
  }
}

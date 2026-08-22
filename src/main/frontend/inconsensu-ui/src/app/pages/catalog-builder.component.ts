import { CommonModule } from '@angular/common';
import { Component, HostListener, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService, BuilderOptions, FormDetails, ItemDraft } from '../api.service';
import { ConfirmData, ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * UI-8: конструктор формы.
 *
 * Справа — чек-лист реквизитов ч. 4 ст. 9 152-ФЗ: он показывает, чего форме не хватает, до отправки на
 * согласование, а не после отказа. Черновик отправляется целиком: частичных правок нет, иначе состав
 * пунктов пришлось бы синхронизировать по частям.
 *
 * Правки живут в памяти до нажатия «Сохранить», поэтому экран сам следит за несохранённым: показывает
 * это словом, спрашивает при уходе и держит браузер на странице при перезагрузке.
 */
@Component({
  selector: 'ic-catalog-builder',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatExpansionModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <ng-container *ngIf="form() as row">
      <div class="ic-card-head">
        <div>
          <h1 class="ic-title">Конструктор: {{ row.title }}</h1>
          <div class="ic-subtitle">{{ row.code }} · версия {{ row.version }} · {{ row.statusRu }}</div>
        </div>
        <div class="ic-actions">
          <span class="ic-badge" [class]="changed() ? 'ic-badge warn' : 'ic-badge ok'">
            {{ changed() ? 'Есть несохранённые правки' : 'Всё сохранено' }}
          </span>
          <button mat-button (click)="leave(row)">К просмотру</button>
          <button mat-stroked-button color="warn" (click)="confirmRemove(row)">Удалить черновик</button>
          <button mat-flat-button color="primary" (click)="save()">Сохранить</button>
        </div>
      </div>

      <div class="ic-note" *ngIf="message()">{{ message() }}</div>
      <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

      <div class="ic-builder">
        <div>
          <mat-card class="ic-block">
            <mat-card-content class="ic-form-grid">
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Название</mat-label>
                <input matInput [(ngModel)]="draft.title" />
              </mat-form-field>
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Источники, где показывается форма</mat-label>
                <mat-select [(ngModel)]="draft.sourceChannels" multiple>
                  <mat-option *ngFor="let item of options()?.sources" [value]="item.code">{{ item.nameRu }}</mat-option>
                </mat-select>
              </mat-form-field>
              <div class="ic-span-2 ic-placeholders">
                <span class="ic-muted">Подстановки — встают в текст туда, где стоит курсор:</span>
                <button
                  mat-stroked-button
                  *ngFor="let placeholder of placeholders"
                  [title]="'При выдаче согласия подставится ' + placeholder.nameRu"
                  (click)="insertPlaceholder(bodyField, placeholder.code)"
                >
                  {{ placeholder.code }}
                </button>
              </div>
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Текст формы</mat-label>
                <textarea matInput rows="6" #bodyField [(ngModel)]="draft.body"></textarea>
              </mat-form-field>
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Действия с персональными данными</mat-label>
                <textarea matInput rows="3" [(ngModel)]="draft.processingActions"></textarea>
              </mat-form-field>
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Порядок отзыва</mat-label>
                <textarea matInput rows="3" [(ngModel)]="draft.revocationProcedure"></textarea>
              </mat-form-field>
            </mat-card-content>
          </mat-card>

          <mat-card class="ic-block">
            <mat-card-header>
              <mat-card-title>Пункты формы</mat-card-title>
              <mat-card-subtitle>
                Клиент отмечает их по отдельности (FR-1.2), а видит в том порядке, в каком они стоят здесь.
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <mat-expansion-panel *ngFor="let item of draft.items; let index = index" class="ic-item">
                <mat-expansion-panel-header>
                  <mat-panel-title>
                    Пункт {{ index + 1 }} из {{ draft.items.length }}: {{ nameOf(item.typeCode) }}
                  </mat-panel-title>
                  <mat-panel-description>{{ item.mandatory ? 'обязательный' : 'по выбору' }}</mat-panel-description>
                </mat-expansion-panel-header>
                <div class="ic-form-grid">
                  <mat-form-field appearance="outline">
                    <mat-label>Тип согласия</mat-label>
                    <mat-select [(ngModel)]="item.typeCode">
                      <mat-option *ngFor="let type of options()?.types" [value]="type.code">{{ type.nameRu }}</mat-option>
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Срок действия</mat-label>
                    <input matInput [(ngModel)]="item.validity" placeholder="P1Y" />
                    <mat-hint>Пусто — берётся срок из типа согласия.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="ic-span-2">
                    <mat-label>Формулировка пункта</mat-label>
                    <textarea matInput rows="2" [(ngModel)]="item.text"></textarea>
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="ic-span-2">
                    <mat-label>Цели обработки, по одной в строке</mat-label>
                    <textarea matInput rows="2" [ngModel]="item.purposes.join('\\n')" (ngModelChange)="setPurposes(item, $event)"></textarea>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Категории персональных данных</mat-label>
                    <mat-select [(ngModel)]="item.categories" multiple>
                      <mat-option *ngFor="let category of options()?.pdnCategories" [value]="category.code">
                        {{ category.nameRu }}
                      </mat-option>
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Третье лицо</mat-label>
                    <mat-select [(ngModel)]="item.thirdPartyId">
                      <mat-option [value]="null">Не требуется</mat-option>
                      <mat-option *ngFor="let party of options()?.thirdParties" [value]="party.id">
                        {{ party.name }}
                      </mat-option>
                    </mat-select>
                  </mat-form-field>
                  <div class="ic-checks">
                    <mat-checkbox [(ngModel)]="item.mandatory">Обязательный пункт</mat-checkbox>
                  </div>
                  <div class="ic-actions ic-span-2 ic-gap">
                    <button mat-stroked-button [disabled]="index === 0" (click)="moveItem(index, -1)">
                      <mat-icon>arrow_upward</mat-icon>
                      Вверх
                    </button>
                    <button
                      mat-stroked-button
                      [disabled]="index === draft.items.length - 1"
                      (click)="moveItem(index, 1)"
                    >
                      <mat-icon>arrow_downward</mat-icon>
                      Вниз
                    </button>
                    <button mat-button color="warn" (click)="confirmRemoveItem(index)">Убрать пункт</button>
                  </div>
                </div>
              </mat-expansion-panel>
              <p class="ic-empty" *ngIf="!draft.items.length">
                Пунктов нет: форма без пунктов не публикуется. Нажмите «Добавить пункт» и выберите тип согласия.
              </p>
              <button mat-stroked-button class="ic-gap" (click)="addItem()">Добавить пункт</button>
            </mat-card-content>
          </mat-card>
        </div>

        <mat-card class="ic-block ic-checklist">
          <mat-card-header><mat-card-title>Реквизиты по закону</mat-card-title></mat-card-header>
          <mat-card-content>
            <div class="ic-warn" *ngIf="changed()">
              Проверка посчитана по сохранённому черновику. Сохраните, чтобы увидеть в ней свежие правки.
            </div>
            <div class="ic-requisite" *ngFor="let requisite of row.checklist">
              <mat-icon [class]="requisite.satisfied ? 'ic-ok' : 'ic-missing'">
                {{ requisite.satisfied ? 'check_circle' : 'radio_button_unchecked' }}
              </mat-icon>
              <span>{{ requisite.nameRu }}</span>
              <span class="ic-badge" [class]="requisite.satisfied ? 'ic-badge ok' : 'ic-badge warn'">
                {{ requisite.satisfied ? 'есть' : 'не хватает' }}
              </span>
            </div>

            <div class="ic-gap" *ngIf="row.violations.length">
              <div class="ic-danger">Мешают отправке:</div>
              <div class="ic-finding" *ngFor="let finding of row.violations">
                {{ finding.itemNumber ? 'Пункт ' + finding.itemNumber + ': ' : '' }}{{ finding.messageRu }}
              </div>
            </div>
            <div class="ic-gap" *ngIf="row.warnings.length">
              <div class="ic-warn">Стоит поправить:</div>
              <div class="ic-finding" *ngFor="let finding of row.warnings">
                {{ finding.itemNumber ? 'Пункт ' + finding.itemNumber + ': ' : '' }}{{ finding.messageRu }}
              </div>
            </div>
            <div class="ic-badge ok ic-gap" *ngIf="row.valid">Форму можно отправлять на согласование.</div>
          </mat-card-content>
        </mat-card>
      </div>
    </ng-container>
  `,
})
export class CatalogBuilderComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialogs = inject(MatDialog);

  readonly form = signal<FormDetails | null>(null);
  readonly options = signal<BuilderOptions | null>(null);
  readonly loading = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  /** Тот же набор, что понимает шаблон формы на сервере: лишняя подстановка осталась бы в тексте как есть. */
  readonly placeholders = [
    { code: '{{operator.name}}', nameRu: 'название оператора' },
    { code: '{{operator.address}}', nameRu: 'адрес оператора' },
    { code: '{{subject.fio}}', nameRu: 'фамилия, имя и отчество клиента' },
    { code: '{{subject.phone}}', nameRu: 'телефон клиента' },
    { code: '{{subject.email}}', nameRu: 'электронная почта клиента' },
    { code: '{{third_party.name}}', nameRu: 'название третьего лица' },
    { code: '{{third_party.address}}', nameRu: 'адрес третьего лица' },
  ];

  draft = {
    title: '',
    body: '',
    processingActions: '',
    revocationProcedure: '',
    sourceChannels: [] as string[],
    items: [] as ItemDraft[],
  };

  private id = '';
  /** Снимок черновика, каким его вернул сервер: с ним сравнивается текущий, чтобы не терять правки молча. */
  private saved = '';

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.load();
    });
  }

  /**
   * Перезагрузка и закрытие вкладки не проходят мимо несохранённого.
   *
   * Текст вопроса задаёт браузер, свой показать нельзя, поэтому рядом с кнопками ещё и надпись
   * «Есть несохранённые правки».
   */
  @HostListener('window:beforeunload', ['$event'])
  warnBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.changed()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  /** Есть ли правки, которых сервер ещё не видел. */
  changed(): boolean {
    return this.snapshot() !== this.saved;
  }

  load(): void {
    this.loading.set(true);
    this.api.form(this.id).subscribe((row) => {
      this.form.set(row);
      this.draft = {
        title: row.title,
        body: row.body ?? '',
        processingActions: row.processingActions ?? '',
        revocationProcedure: row.revocationProcedure ?? '',
        sourceChannels: [...row.sourceChannels],
        items: row.draftItems.map((item) => ({ ...item, purposes: [...item.purposes], categories: [...item.categories] })),
      };
      this.saved = this.snapshot();
      this.loading.set(false);
    });
  }

  save(): void {
    this.error.set('');
    this.api.saveDraft(this.id, this.draft).subscribe({
      next: (row) => {
        this.form.set(row);
        this.saved = this.snapshot();
        this.message.set('Черновик сохранён');
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Черновик не сохранён: проверьте поля.'),
    });
  }

  /** «К просмотру» уводит с экрана, поэтому кнопка сначала спрашивает про несохранённое. */
  leave(row: FormDetails): void {
    if (!this.changed()) {
      this.router.navigate(['/catalog/forms', row.id]);
      return;
    }
    const data: ConfirmData = {
      title: 'Уйти, не сохранив правки?',
      consequences:
        'Название, текст формы, источники и пункты вернутся к последнему сохранённому виду — всё, что вы ' +
        'набрали после сохранения, пропадёт.',
      confirmLabel: 'Уйти без сохранения',
      danger: true,
    };
    this.confirm(data, () => {
      this.router.navigate(['/catalog/forms', row.id]);
    });
  }

  confirmRemove(row: FormDetails): void {
    const data: ConfirmData = {
      title: `Удалить черновик версии ${row.version}?`,
      consequences:
        `У формы «${row.title}» (код ${row.code}) не станет версии ${row.version}: её текст и пункты ` +
        `(сейчас их ${this.draft.items.length}) пропадут. Ранее опубликованные версии и выданные по ним ` +
        `согласия это не затронет.`,
      confirmLabel: 'Удалить черновик',
      danger: true,
    };
    this.confirm(data, () => this.remove());
  }

  remove(): void {
    this.api.deleteDraft(this.id).subscribe({
      next: () => {
        // Черновика больше нет — предупреждать о его правках не о чем.
        this.saved = this.snapshot();
        this.router.navigate(['/catalog/forms']);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Черновик не удалён.'),
    });
  }

  addItem(): void {
    this.draft.items = [
      ...this.draft.items,
      { typeCode: '', text: '', purposes: [], categories: [], thirdPartyId: null, validity: '', mandatory: false },
    ];
  }

  confirmRemoveItem(index: number): void {
    const item = this.draft.items[index];
    const wording = this.shorten(item.text);
    const rest = this.draft.items.length - 1;
    const data: ConfirmData = {
      title: `Убрать пункт ${index + 1}?`,
      consequences:
        `Из черновика уйдёт пункт «${this.nameOf(item.typeCode)}»` +
        (wording ? ` с формулировкой «${wording}»` : ' (формулировка ещё не набрана)') +
        '. ' +
        (rest > 0
          ? `Пунктов станет ${rest}, и их номера сдвинутся.`
          : 'Пунктов в черновике не останется, а форму без пунктов не опубликовать.') +
        ' В самой форме это отразится после сохранения черновика.',
      confirmLabel: 'Убрать пункт',
    };
    this.confirm(data, () => this.removeItem(index));
  }

  removeItem(index: number): void {
    this.draft.items = this.draft.items.filter((_, position) => position !== index);
  }

  /** Порядок пунктов — часть формы: клиент читает их сверху вниз, поэтому его правят прямо здесь. */
  moveItem(index: number, delta: number): void {
    const target = index + delta;
    if (target < 0 || target >= this.draft.items.length) {
      return;
    }
    const items = [...this.draft.items];
    const moved = items[index];
    items[index] = items[target];
    items[target] = moved;
    this.draft.items = items;
  }

  /**
   * Подстановка встаёт на место курсора, а выделенный кусок заменяет собой.
   *
   * Курсор возвращается на место с задержкой: ngModel переписывает поле после обработчика, и позиция,
   * выставленная сразу, потерялась бы.
   */
  insertPlaceholder(field: HTMLTextAreaElement, placeholder: string): void {
    const text = this.draft.body ?? '';
    const start = field.selectionStart ?? text.length;
    const end = field.selectionEnd ?? start;
    this.draft.body = text.slice(0, start) + placeholder + text.slice(end);
    const caret = start + placeholder.length;
    setTimeout(() => {
      field.focus();
      field.setSelectionRange(caret, caret);
    });
  }

  /** Цели вводятся построчно: так их проще править, чем в одну строку через запятую. */
  setPurposes(item: ItemDraft, value: string): void {
    item.purposes = value
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0);
  }

  nameOf(code: string): string {
    return this.options()?.types.find((type) => type.code === code)?.nameRu ?? 'тип не выбран';
  }

  private confirm(data: ConfirmData, action: () => void): void {
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          action();
        }
      });
  }

  /** Сравнение сериализацией: черновик — обычный объект под ngModel, отдельного сигнала на каждое поле нет. */
  private snapshot(): string {
    return JSON.stringify(this.draft);
  }

  private shorten(text: string): string {
    const clean = (text ?? '').trim();
    return clean.length > 60 ? `${clean.slice(0, 60)}…` : clean;
  }
}

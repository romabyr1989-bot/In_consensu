import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, BuilderOptions, FormDetails, ItemDraft } from '../api.service';

/**
 * UI-8: конструктор формы.
 *
 * Справа — чек-лист реквизитов ч. 4 ст. 9 152-ФЗ: он показывает, чего форме не хватает, до отправки на
 * согласование, а не после отказа. Черновик отправляется целиком: частичных правок нет, иначе состав
 * пунктов пришлось бы синхронизировать по частям.
 */
@Component({
  selector: 'ic-catalog-builder',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
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
          <a mat-button [routerLink]="['/catalog/forms', row.id]">К просмотру</a>
          <button mat-stroked-button color="warn" (click)="remove()">Удалить черновик</button>
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
              <mat-form-field appearance="outline" class="ic-span-2">
                <mat-label>Текст формы</mat-label>
                <textarea matInput rows="6" [(ngModel)]="draft.body"></textarea>
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
              <mat-card-subtitle>Клиент отмечает их по отдельности (FR-1.2)</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <mat-expansion-panel *ngFor="let item of draft.items; let index = index" class="ic-item">
                <mat-expansion-panel-header>
                  <mat-panel-title>Пункт {{ index + 1 }}: {{ nameOf(item.typeCode) }}</mat-panel-title>
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
                    <button mat-button color="warn" (click)="removeItem(index)">Убрать пункт</button>
                  </div>
                </div>
              </mat-expansion-panel>
              <p class="ic-empty" *ngIf="!draft.items.length">Пунктов нет: форма без пунктов не публикуется.</p>
              <button mat-stroked-button class="ic-gap" (click)="addItem()">Добавить пункт</button>
            </mat-card-content>
          </mat-card>
        </div>

        <mat-card class="ic-block ic-checklist">
          <mat-card-header><mat-card-title>Реквизиты по закону</mat-card-title></mat-card-header>
          <mat-card-content>
            <div class="ic-requisite" *ngFor="let requisite of row.checklist">
              <mat-icon [class]="requisite.satisfied ? 'ic-ok' : 'ic-missing'">
                {{ requisite.satisfied ? 'check_circle' : 'radio_button_unchecked' }}
              </mat-icon>
              <span>{{ requisite.nameRu }}</span>
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

  readonly form = signal<FormDetails | null>(null);
  readonly options = signal<BuilderOptions | null>(null);
  readonly loading = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  draft = {
    title: '',
    body: '',
    processingActions: '',
    revocationProcedure: '',
    sourceChannels: [] as string[],
    items: [] as ItemDraft[],
  };

  private id = '';

  constructor() {
    this.api.builderOptions().subscribe((options) => this.options.set(options));
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.load();
    });
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
      this.loading.set(false);
    });
  }

  save(): void {
    this.error.set('');
    this.api.saveDraft(this.id, this.draft).subscribe({
      next: (row) => {
        this.form.set(row);
        this.message.set('Черновик сохранён');
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Черновик не сохранён: проверьте поля.'),
    });
  }

  remove(): void {
    this.api.deleteDraft(this.id).subscribe({
      next: () => this.router.navigate(['/catalog/forms']),
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Черновик не удалён.'),
    });
  }

  addItem(): void {
    this.draft.items = [
      ...this.draft.items,
      { typeCode: '', text: '', purposes: [], categories: [], thirdPartyId: null, validity: '', mandatory: false },
    ];
  }

  removeItem(index: number): void {
    this.draft.items = this.draft.items.filter((_, position) => position !== index);
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
}

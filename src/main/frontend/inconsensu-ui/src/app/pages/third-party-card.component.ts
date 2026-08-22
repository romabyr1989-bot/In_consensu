import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, DictionaryItem, PartyCard } from '../api.service';

/**
 * UI-11: карточка третьего лица — реквизиты с договором и выгрузки.
 *
 * Перед формированием выгрузки экран называет, что в неё войдёт: сколько записей и какие категории
 * персональных данных. Партнёру с истёкшим договором выгрузка не формируется (FR-7.1).
 */
@Component({
  selector: 'ic-third-party-card',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-progress-bar mode="indeterminate" *ngIf="loading()"></mat-progress-bar>

    <ng-container *ngIf="card() as party">
      <div class="ic-card-head">
        <div>
          <h1 class="ic-title">{{ party.name }}</h1>
          <div class="ic-subtitle">
            ИНН {{ party.inn }} · {{ party.roleRu }}
            <span class="ic-badge danger" *ngIf="party.contractExpired">договор истёк</span>
            <span class="ic-badge danger" *ngIf="!party.active">не действует</span>
          </div>
        </div>
        <div class="ic-actions">
          <a mat-button routerLink="/third-parties">К справочнику</a>
          <button mat-stroked-button color="warn" *ngIf="party.active && editable" (click)="deactivate()">
            Деактивировать
          </button>
        </div>
      </div>

      <div class="ic-note" *ngIf="message()">{{ message() }}</div>
      <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

      <mat-tab-group class="ic-block">
        <mat-tab label="Реквизиты">
          <div class="ic-tab-body">
            <mat-card>
              <mat-card-content class="ic-form-grid">
                <mat-form-field appearance="outline">
                  <mat-label>Наименование</mat-label>
                  <input matInput [(ngModel)]="draft.name" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Краткое наименование</mat-label>
                  <input matInput [(ngModel)]="draft.shortName" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>ОГРН</mat-label>
                  <input matInput [(ngModel)]="draft.ogrn" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Роль</mat-label>
                  <mat-select [(ngModel)]="draft.role" [disabled]="!editable">
                    <mat-option *ngFor="let role of roles()" [value]="role.code">{{ role.nameRu }}</mat-option>
                  </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline" class="ic-span-2">
                  <mat-label>Адрес</mat-label>
                  <input matInput [(ngModel)]="draft.address" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Номер договора</mat-label>
                  <input matInput [(ngModel)]="draft.contractNumber" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Дата договора</mat-label>
                  <input matInput type="date" [(ngModel)]="draft.contractDate" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Договор действует до</mat-label>
                  <input matInput type="date" [(ngModel)]="draft.contractValidUntil" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Контактная почта</mat-label>
                  <input matInput [(ngModel)]="draft.contactEmail" [disabled]="!editable" />
                </mat-form-field>
                <mat-form-field appearance="outline" class="ic-span-2">
                  <mat-label>Категории персональных данных, которые можно передавать</mat-label>
                  <mat-select [(ngModel)]="draft.allowedPdnCategories" multiple [disabled]="!editable">
                    <mat-option *ngFor="let category of categories()" [value]="category.code">
                      {{ category.nameRu }}
                    </mat-option>
                  </mat-select>
                  <mat-hint>Сейчас: {{ party.allowedCategoriesRu || 'не заданы' }}</mat-hint>
                </mat-form-field>
              </mat-card-content>
              <mat-card-actions align="end" *ngIf="editable">
                <button mat-flat-button color="primary" (click)="save()">Сохранить реквизиты</button>
              </mat-card-actions>
            </mat-card>
          </div>
        </mat-tab>

        <mat-tab label="Выгрузки">
          <div class="ic-tab-body">
            <mat-card class="ic-block">
              <mat-card-content>
                <p *ngIf="party.exportAllowed">
                  В выгрузку войдёт записей: <b>{{ party.exportRecords }}</b>. Категории данных:
                  {{ party.exportCategories.join(', ') || 'не заданы' }}.
                </p>
                <p class="ic-danger" *ngIf="!party.exportAllowed">
                  Выгрузка недоступна: договор с партнёром закончился или не заданы категории данных.
                </p>
                <div class="ic-filters" *ngIf="party.exportAllowed">
                  <mat-form-field appearance="outline">
                    <mat-label>Формат</mat-label>
                    <mat-select [(ngModel)]="format">
                      <mat-option value="csv">CSV</mat-option>
                      <mat-option value="json">JSON</mat-option>
                    </mat-select>
                  </mat-form-field>
                  <button mat-flat-button color="primary" (click)="createExport()">Сформировать выгрузку</button>
                </div>
              </mat-card-content>
            </mat-card>

            <mat-card class="ic-block">
              <table mat-table [dataSource]="party.exports" class="ic-table" *ngIf="party.exports.length">
                <ng-container matColumnDef="requestedAt">
                  <th mat-header-cell *matHeaderCellDef>Когда</th>
                  <td mat-cell *matCellDef="let row">{{ row.requestedAt }}</td>
                </ng-container>
                <ng-container matColumnDef="requestedBy">
                  <th mat-header-cell *matHeaderCellDef>Кто запросил</th>
                  <td mat-cell *matCellDef="let row">{{ row.requestedBy }}</td>
                </ng-container>
                <ng-container matColumnDef="format">
                  <th mat-header-cell *matHeaderCellDef>Формат</th>
                  <td mat-cell *matCellDef="let row">{{ row.formatRu }}</td>
                </ng-container>
                <ng-container matColumnDef="records">
                  <th mat-header-cell *matHeaderCellDef>Записей</th>
                  <td mat-cell *matCellDef="let row">{{ row.recordsCount }}</td>
                </ng-container>
                <ng-container matColumnDef="expires">
                  <th mat-header-cell *matHeaderCellDef>Хранится до</th>
                  <td mat-cell *matCellDef="let row">{{ row.expiresAt }}</td>
                </ng-container>
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let row">
                    <a mat-button *ngIf="row.downloadable" [href]="'/ui/api/third-parties/exports/' + row.id + '/download'">
                      Скачать
                    </a>
                    <span class="ic-muted" *ngIf="!row.downloadable">срок хранения истёк</span>
                  </td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="columns"></tr>
                <tr mat-row *matRowDef="let row; columns: columns"></tr>
              </table>
              <p class="ic-empty" *ngIf="!party.exports.length">Выгрузок по этому партнёру ещё не было.</p>
            </mat-card>
          </div>
        </mat-tab>
      </mat-tab-group>
    </ng-container>
  `,
})
export class ThirdPartyCardComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  readonly card = signal<PartyCard | null>(null);
  readonly roles = signal<DictionaryItem[]>([]);
  readonly categories = signal<DictionaryItem[]>([]);
  readonly loading = signal(false);
  readonly message = signal('');
  readonly error = signal('');

  readonly columns = ['requestedAt', 'requestedBy', 'format', 'records', 'expires', 'actions'];

  /** UI-11: реквизиты видны всем, кто открыл карточку; правит их роль с правом на справочник. */
  editable = false;
  format = 'csv';
  draft = this.emptyDraft();

  private id = '';

  constructor() {
    this.api.partyOptions().subscribe((options) => {
      this.roles.set(options.roles);
      this.categories.set(options.pdnCategories);
    });
    this.api.me().subscribe((user) => {
      this.editable = user.roles.some((role) => ['LAWYER', 'DPO', 'ADMIN'].includes(role));
    });
    this.route.paramMap.subscribe((params) => {
      this.id = params.get('id') ?? '';
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.partyCard(this.id).subscribe((party) => {
      this.card.set(party);
      this.draft = {
        id: party.id,
        inn: party.inn,
        name: party.name,
        shortName: party.shortName ?? '',
        ogrn: party.ogrn ?? '',
        address: party.address ?? '',
        role: party.role,
        contractNumber: party.contractNumber ?? '',
        contractDate: party.contractDate ?? '',
        contractValidUntil: party.contractValidUntil ?? '',
        allowedPdnCategories: [...party.allowedPdnCategories],
        contactEmail: party.contactEmail ?? '',
      };
      this.loading.set(false);
    });
  }

  save(): void {
    this.error.set('');
    this.api.saveParty(this.draft).subscribe({
      next: (party) => {
        this.card.set(party);
        this.message.set('Реквизиты сохранены');
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Реквизиты не сохранены: проверьте поля.'),
    });
  }

  deactivate(): void {
    this.api.deactivateParty(this.id).subscribe({
      next: (party) => {
        this.card.set(party);
        this.message.set('Третье лицо деактивировано');
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Не удалось деактивировать.'),
    });
  }

  createExport(): void {
    this.error.set('');
    this.api.createExport(this.id, this.format).subscribe({
      next: (result) => {
        this.message.set(result.message);
        this.load();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Выгрузка не сформирована.'),
    });
  }

  private emptyDraft() {
    return {
      id: null as string | null,
      inn: '',
      name: '',
      shortName: '',
      ogrn: '',
      address: '',
      role: '',
      contractNumber: '',
      contractDate: '',
      contractValidUntil: '',
      allowedPdnCategories: [] as string[],
      contactEmail: '',
    };
  }
}

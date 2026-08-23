import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ApiService, DictionaryItem, UserRow } from '../api.service';
import { ConfirmData, ConfirmDialogComponent } from './confirm-dialog.component';

/**
 * UI-17: учётные записи сотрудников.
 *
 * Пароль задаётся при заведении и дальше только сбрасывается: показывать сохранённый пароль негде и
 * незачем — он хранится хешем. Поэтому сброс переспрашивает и называет учётную запись поимённо:
 * ошибиться строкой в списке легко, а вернуть прежний пароль потом нельзя.
 */
@Component({
  selector: 'ic-admin-users',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatDialogModule,
  ],
  template: `
    <h1 class="ic-title">Пользователи</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block">
      <mat-card-header>
        <mat-card-title>{{ draft.id ? 'Правка: ' + draft.login : 'Новая учётная запись' }}</mat-card-title>
      </mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Логин</mat-label>
          <input matInput [(ngModel)]="draft.login" [disabled]="!!draft.id" />
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="!draft.id">
          <mat-label>Пароль</mat-label>
          <input matInput type="password" [(ngModel)]="draft.password" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Фамилия и имя</mat-label>
          <input matInput [(ngModel)]="draft.fullName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Рабочая почта</mat-label>
          <input matInput [(ngModel)]="draft.email" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Роли</mat-label>
          <mat-select [(ngModel)]="draft.roles" multiple>
            <mat-option *ngFor="let role of roles()" [value]="role.code">{{ role.nameRu }}</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="ic-checks" *ngIf="draft.id">
          <mat-checkbox [(ngModel)]="draft.active">Учётная запись действует</mat-checkbox>
        </div>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-stroked-button *ngIf="draft.id" (click)="resetDraft()">Отмена</button>
        <button mat-flat-button color="primary" (click)="save()">Сохранить</button>
      </mat-card-actions>
    </mat-card>

    <mat-card class="ic-block" *ngIf="resetting()">
      <mat-card-header>
        <mat-card-title>Смена пароля</mat-card-title>
      </mat-card-header>
      <mat-card-content class="ic-filters">
        <mat-form-field appearance="outline" class="ic-grow">
          <mat-label>Новый пароль для {{ resetting() }}</mat-label>
          <input matInput type="password" [(ngModel)]="newPassword" />
        </mat-form-field>
        <button mat-flat-button color="warn" [disabled]="!newPassword" (click)="applyReset()">Сбросить пароль</button>
        <button mat-stroked-button (click)="cancelReset()">Отмена</button>
      </mat-card-content>
    </mat-card>

    <mat-card class="ic-block">
      <table mat-table [dataSource]="rows()" class="ic-table" *ngIf="rows().length">
        <ng-container matColumnDef="login">
          <th mat-header-cell *matHeaderCellDef>Логин</th>
          <td mat-cell *matCellDef="let row">
            {{ row.login }}
            <span class="ic-badge danger" *ngIf="!row.active">заблокирован</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="fullName">
          <th mat-header-cell *matHeaderCellDef>Сотрудник</th>
          <td mat-cell *matCellDef="let row">{{ row.fullName }}</td>
        </ng-container>
        <ng-container matColumnDef="email">
          <th mat-header-cell *matHeaderCellDef>Почта</th>
          <td mat-cell *matCellDef="let row">{{ row.email || '—' }}</td>
        </ng-container>
        <ng-container matColumnDef="roles">
          <th mat-header-cell *matHeaderCellDef>Роли</th>
          <td mat-cell *matCellDef="let row">{{ rolesRu(row.roles) }}</td>
        </ng-container>
        <ng-container matColumnDef="lastLogin">
          <th mat-header-cell *matHeaderCellDef>Последний вход</th>
          <td mat-cell *matCellDef="let row">{{ row.lastLoginAt || 'не входил' }}</td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let row">
            <button mat-stroked-button (click)="edit(row)">Править</button>
            <button mat-stroked-button (click)="startReset(row)">Сбросить пароль</button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
      <p class="ic-empty" *ngIf="!rows().length">
        Учётных записей нет. Заведите первую в форме выше: логин, пароль, фамилию с именем и хотя бы одну
        роль — без роли сотрудник войдёт, но ни одного раздела не увидит.
      </p>
      <!-- UI-0.8: постраничность и выбор размера страницы у каждого списка. -->
      <mat-paginator
        [length]="total()"
        [pageSize]="size"
        [pageIndex]="page"
        [pageSizeOptions]="[10, 20, 50]"
        (page)="turnPage($event)"
      ></mat-paginator>
    </mat-card>
  `,
})
export class AdminUsersComponent {
  private readonly api = inject(ApiService);
  private readonly dialogs = inject(MatDialog);

  readonly rows = signal<UserRow[]>([]);
  readonly roles = signal<DictionaryItem[]>([]);
  readonly total = signal(0);
  readonly resetting = signal('');
  readonly message = signal('');
  readonly error = signal('');

  readonly columns = ['login', 'fullName', 'email', 'roles', 'lastLogin', 'actions'];

  draft = this.emptyDraft();
  newPassword = '';
  resettingId = '';
  resettingName = '';
  page = 0;
  size = 10;

  constructor() {
    this.load();
  }

  load(): void {
    this.api.users(this.page, this.size).subscribe((view) => {
      this.rows.set(view.rows);
      this.roles.set(view.roles);
      this.total.set(view.total);
    });
  }

  save(): void {
    this.error.set('');
    this.api.saveUser(this.draft).subscribe({
      next: () => {
        this.message.set(this.draft.id ? 'Учётная запись обновлена' : 'Пользователь создан');
        this.resetDraft();
        this.load();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Учётная запись не сохранена: проверьте поля.'),
    });
  }

  edit(row: UserRow): void {
    this.draft = {
      id: row.id,
      login: row.login,
      password: '',
      fullName: row.fullName,
      email: row.email ?? '',
      roles: [...row.roles],
      active: row.active,
    };
  }

  startReset(row: UserRow): void {
    this.resetting.set(row.login);
    this.resettingId = row.id;
    this.resettingName = row.fullName ?? '';
    this.newPassword = '';
    this.message.set('');
    this.error.set('');
  }

  cancelReset(): void {
    this.resetting.set('');
    this.resettingId = '';
    this.resettingName = '';
    this.newPassword = '';
  }

  /** Переспрашиваем поимённо: строкой в списке ошибаются часто, а прежний пароль не вернуть. */
  applyReset(): void {
    const login = this.resetting();
    const who = this.resettingName ? `${this.resettingName}, логин ${login}` : `логин ${login}`;
    const data: ConfirmData = {
      title: `Сбросить пароль учётной записи «${login}»?`,
      consequences:
        `Пароль меняется у одной учётной записи: ${who}. Прежний пароль перестанет подходить сразу, и ` +
        'войти по нему сотрудник больше не сможет — до тех пор, пока вы не передадите ему новый. ' +
        'Показать этот пароль ещё раз система не умеет: он хранится хешем, и при потере остаётся только ' +
        'сбросить его заново.',
      confirmLabel: 'Сбросить пароль',
      danger: true,
    };
    this.dialogs
      .open(ConfirmDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed?: boolean) => {
        if (confirmed) {
          this.submitReset(login);
        }
      });
  }

  rolesRu(codes: string[]): string {
    const names = this.roles();
    return codes.map((code) => names.find((role) => role.code === code)?.nameRu ?? code).join(', ');
  }

  resetDraft(): void {
    this.draft = this.emptyDraft();
  }

  turnPage(event: PageEvent): void {
    this.page = event.pageIndex;
    this.size = event.pageSize;
    this.load();
  }

  private submitReset(login: string): void {
    this.error.set('');
    this.api.resetPassword(this.resettingId, this.newPassword).subscribe({
      next: (result) => {
        this.message.set(`${result.message}: учётная запись «${login}». Передайте новый пароль сотруднику лично.`);
        this.cancelReset();
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Пароль не сброшен: попробуйте ещё раз.'),
    });
  }

  private emptyDraft() {
    return {
      id: null as string | null,
      login: '',
      password: '',
      fullName: '',
      email: '',
      roles: [] as string[],
      active: true,
    };
  }
}

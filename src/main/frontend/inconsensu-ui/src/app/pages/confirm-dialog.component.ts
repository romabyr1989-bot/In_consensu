import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/**
 * Подтверждение необратимого действия (UI-0.6).
 *
 * @param consequences последствия словами: сколько согласий погаснет, какая версия публикуется, сколько
 *     строк уйдёт в базу. Диалог без последствий бесполезен — сотрудник жмёт «Да» не глядя.
 */
export interface ConfirmData {
  title: string;
  consequences: string;
  confirmLabel: string;
  danger?: boolean;
}

@Component({
  selector: 'ic-confirm-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.consequences }}</p>
      <p class="ic-danger" *ngIf="data.danger">Действие необратимо.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">Отмена</button>
      <button mat-flat-button [color]="data.danger ? 'warn' : 'primary'" [mat-dialog-close]="true">
        {{ data.confirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialogComponent {
  readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
}

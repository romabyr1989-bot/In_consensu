import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiService, SettingGroup } from '../api.service';

/**
 * UI-16: настройки оператора.
 *
 * Значения показываются группами и с русскими подписями, а не списком технических ключей. Часть
 * настроек задаётся при установке и правке не подлежит — такие поля закрыты, но видны: сотруднику
 * нужно знать, с чем работает система.
 */
@Component({
  selector: 'ic-admin-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <h1 class="ic-title">Настройки оператора</h1>

    <div class="ic-note" *ngIf="message()">{{ message() }}</div>
    <div class="ic-danger ic-gap" *ngIf="error()">{{ error() }}</div>

    <mat-card class="ic-block" *ngFor="let group of groups()">
      <mat-card-header><mat-card-title>{{ group.title }}</mat-card-title></mat-card-header>
      <mat-card-content class="ic-form-grid">
        <mat-form-field appearance="outline" *ngFor="let setting of group.settings">
          <mat-label>{{ setting.label }}</mat-label>
          <input matInput [(ngModel)]="values[setting.key]" [disabled]="setting.readOnly" />
          <mat-hint>{{ setting.hint }}</mat-hint>
        </mat-form-field>
      </mat-card-content>
    </mat-card>

    <div class="ic-actions ic-gap">
      <button mat-flat-button color="primary" (click)="save()">Сохранить настройки</button>
    </div>
  `,
})
export class AdminSettingsComponent {
  private readonly api = inject(ApiService);

  readonly groups = signal<SettingGroup[]>([]);
  readonly message = signal('');
  readonly error = signal('');

  values: Record<string, string> = {};

  constructor() {
    this.load();
  }

  load(): void {
    this.api.settings().subscribe((groups) => {
      this.groups.set(groups);
      this.values = {};
      groups.forEach((group) => group.settings.forEach((setting) => (this.values[setting.key] = setting.value)));
    });
  }

  /** Отправляются только изменяемые значения: закрытые поля сервер всё равно не примет. */
  save(): void {
    this.error.set('');
    const changes: Record<string, string> = {};
    this.groups().forEach((group) =>
      group.settings
        .filter((setting) => !setting.readOnly)
        .forEach((setting) => (changes[setting.key] = this.values[setting.key] ?? '')),
    );
    this.api.updateSettings(changes).subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.message.set('Настройки сохранены');
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'Настройки не сохранены: проверьте значения.'),
    });
  }
}

import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

/** Неизвестный адрес: раздел не открылся, и об этом сказано прямо, а не подменой на главную (UI-0.6). */
@Component({
  selector: 'ic-not-found',
  standalone: true,
  imports: [RouterLink, MatCardModule, MatButtonModule],
  template: `
    <h1 class="ic-title">Раздел не найден</h1>
    <mat-card class="ic-block">
      <mat-card-content>
        <p>Такого адреса в рабочем месте нет. Возможно, ссылка устарела или в ней опечатка.</p>
        <div class="ic-actions">
          <a mat-flat-button color="primary" routerLink="/">На главную</a>
          <a mat-stroked-button routerLink="/subjects">К клиентам</a>
        </div>
      </mat-card-content>
    </mat-card>
  `,
})
export class NotFoundComponent {}

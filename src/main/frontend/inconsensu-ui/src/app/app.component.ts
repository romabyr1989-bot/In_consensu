import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService, CurrentUser } from './api.service';

/** Пункт меню и роли, которым он открыт (§16.2). */
interface MenuItem {
  path: string;
  title: string;
  icon: string;
  roles?: string[];
  group?: string;
}

/** Каркас рабочего места: верхняя панель, меню по ролям, область содержимого (UI-0.5). */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  template: `
    <mat-toolbar class="ic-topbar">
      <span class="ic-brand">In consensu · {{ user()?.operatorName || 'Центр управления согласиями' }}</span>
      <span class="ic-spacer"></span>
      <span class="ic-user" *ngIf="user() as u">{{ u.login }} · {{ rolesRu(u.roles) }}</span>
      <form action="/ui/logout" method="post">
        <button mat-button type="submit">Выйти</button>
      </form>
    </mat-toolbar>

    <mat-sidenav-container class="ic-layout">
      <mat-sidenav mode="side" opened class="ic-sidebar">
        <mat-nav-list>
          <ng-container *ngFor="let item of visibleMenu()">
            <div class="ic-nav-group" *ngIf="item.group">{{ item.group }}</div>
            <a mat-list-item [routerLink]="item.path" routerLinkActive="ic-active"
               [routerLinkActiveOptions]="{ exact: item.path === '' }">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>{{ item.title }}</span>
            </a>
          </ng-container>
        </mat-nav-list>
      </mat-sidenav>
      <mat-sidenav-content class="ic-content">
        <router-outlet></router-outlet>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
})
export class AppComponent {
  private readonly api = inject(ApiService);
  readonly user = signal<CurrentUser | null>(null);

  /** Пункты §16.2: те же разделы и те же права, что в прежнем интерфейсе. */
  private readonly menu: MenuItem[] = [
    { path: '', title: 'Главная', icon: 'home' },
    { path: 'subjects', title: 'Клиенты', icon: 'people' },
    { path: 'catalog/types', title: 'Типы согласий', icon: 'checklist', group: 'Каталог' },
    { path: 'catalog/forms', title: 'Формы', icon: 'description' },
    { path: 'third-parties', title: 'Третьи лица', icon: 'apartment', group: 'Работа с данными' },
    { path: 'import', title: 'Импорт', icon: 'upload', roles: ['DPO', 'ADMIN'] },
    { path: 'notifications', title: 'Уведомления', icon: 'mail', roles: ['DPO', 'ADMIN'] },
    { path: 'webhooks', title: 'Webhooks', icon: 'sensors', roles: ['ADMIN'] },
    { path: 'audit', title: 'Аудит', icon: 'verified_user', roles: ['AUDITOR', 'DPO', 'ADMIN'] },
    { path: 'admin/users', title: 'Пользователи', icon: 'manage_accounts', roles: ['ADMIN'], group: 'Администрирование' },
    { path: 'admin/settings', title: 'Настройки оператора', icon: 'tune', roles: ['ADMIN', 'DPO'] },
  ];

  constructor() {
    this.api.me().subscribe((user) => this.user.set(user));
  }

  /** Меню строится по ролям: пункт, закрытый матрицей §16.2, не показывается вовсе. */
  visibleMenu(): MenuItem[] {
    const roles = this.user()?.roles ?? [];
    return this.menu.filter((item) => !item.roles || item.roles.some((role) => roles.includes(role)));
  }

  rolesRu(roles: string[]): string {
    const names: Record<string, string> = {
      ADMIN: 'Администратор',
      DPO: 'Ответственный за ПДн',
      LAWYER: 'Юрист',
      MANAGER: 'Менеджер',
      MARKETING: 'Маркетинг',
      AUDITOR: 'Аудитор',
    };
    return roles.map((role) => names[role] ?? role).join(', ');
  }
}

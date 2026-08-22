import { CommonModule } from '@angular/common';
import { Component, ElementRef, HostListener, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService, CurrentUser } from './api.service';

/** Пункт меню и роли, которым он открыт (§16.2). */
interface MenuItem {
  path: string;
  title: string;
  icon: string;
  roles?: string[];
  group?: string;
  counter?: boolean;
}

/** Крошка: у раздела есть адрес, у текущей страницы — нет (UI-0.5). */
interface Crumb {
  title: string;
  path?: string;
}

/**
 * Цвет оператора подставляется в CSS страницы, поэтому в разметку идёт только то, что похоже на HEX.
 * Значение проверено и на сервере, но настройку правит человек, и произвольная строка сюда не пойдёт.
 */
const HEX_COLOR = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

/** Заголовок страницы без хвоста «· In consensu»: в крошках он лишний. */
function pageTitle(title: string | undefined): string {
  return (title ?? '').split('·')[0].trim();
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
      <button mat-icon-button class="ic-menu-button" (click)="menuOpen.set(!menuOpen())" aria-label="Меню разделов">
        <mat-icon>menu</mat-icon>
      </button>
      <span class="ic-brand">
        <img class="ic-logo" *ngIf="user()?.logoUrl as logo" [src]="logo" alt="" />
        <span>In consensu · {{ user()?.operatorName || 'Центр управления согласиями' }}</span>
      </span>
      <span class="ic-spacer"></span>
      <span class="ic-user" *ngIf="user() as u">{{ u.login }} · {{ rolesRu(u.roles) }}</span>
      <form action="/ui/logout" method="post">
        <button mat-button type="submit">Выйти</button>
      </form>
    </mat-toolbar>

    <mat-sidenav-container class="ic-layout">
      <!-- На узком окне панель наезжает поверх содержимого и закрывается по выбору раздела: постоянные
           260 пикселей меню на планшете сдавливали таблицы и выносили их за край окна. -->
      <mat-sidenav
        class="ic-sidebar"
        [mode]="wide() ? 'side' : 'over'"
        [opened]="wide() || menuOpen()"
        (closedStart)="menuOpen.set(false)"
      >
        <mat-nav-list>
          <ng-container *ngFor="let item of visibleMenu()">
            <div class="ic-nav-group" *ngIf="item.group">{{ item.group }}</div>
            <a mat-list-item [routerLink]="item.path" routerLinkActive="ic-active"
               [routerLinkActiveOptions]="{ exact: item.path === '' }" (click)="closeOnNarrow()">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>
                {{ item.title }}
                <!-- Счётчик стоит внутри названия: у отдельного места в строке (matListItemMeta)
                     содержимое отбирается по признаку, и с *ngIf оно уезжает не в тот угол. -->
                <span class="ic-badge warn ic-menu-count" *ngIf="item.counter && awaitingForms() > 0"
                      [title]="'Форм ждут вашего решения: ' + awaitingForms()"
                      [attr.aria-label]="'Форм ждут вашего решения: ' + awaitingForms()">{{ awaitingForms() }}</span>
              </span>
            </a>
          </ng-container>
        </mat-nav-list>
      </mat-sidenav>
      <mat-sidenav-content class="ic-content">
        <nav class="ic-crumbs" aria-label="Хлебные крошки" *ngIf="crumbs().length">
          <ng-container *ngFor="let crumb of crumbs(); let last = last">
            <a *ngIf="crumb.path" [routerLink]="crumb.path">{{ crumb.title }}</a>
            <span *ngIf="!crumb.path" aria-current="page">{{ crumb.title }}</span>
            <span class="ic-crumb-sep" *ngIf="!last" aria-hidden="true">/</span>
          </ng-container>
        </nav>
        <router-outlet></router-outlet>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
})
export class AppComponent {
  /** Окно шире 1024 — меню открыто постоянно; уже — открывается кнопкой поверх содержимого. */
  readonly wide = signal(typeof window === 'undefined' || window.innerWidth >= 1024);
  readonly menuOpen = signal(false);

  @HostListener('window:resize')
  onResize(): void {
    this.wide.set(window.innerWidth >= 1024);
  }

  closeOnNarrow(): void {
    if (!this.wide()) {
      this.menuOpen.set(false);
    }
  }

  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly user = signal<CurrentUser | null>(null);
  /** Сколько форм ждёт решения этого сотрудника: у остальных ролей счётчика нет вовсе (UI-0.5). */
  readonly awaitingForms = signal(0);
  readonly crumbs = signal<Crumb[]>([]);

  /** Пункты §16.2: те же разделы и те же права, что в прежнем интерфейсе. */
  private readonly menu: MenuItem[] = [
    { path: '', title: 'Главная', icon: 'home' },
    { path: 'subjects', title: 'Клиенты', icon: 'people' },
    { path: 'catalog/types', title: 'Типы согласий', icon: 'checklist', group: 'Каталог' },
    { path: 'catalog/forms', title: 'Формы', icon: 'description', counter: true },
    { path: 'third-parties', title: 'Третьи лица', icon: 'apartment', group: 'Работа с данными' },
    { path: 'import', title: 'Импорт', icon: 'upload', roles: ['DPO', 'ADMIN'] },
    { path: 'notifications', title: 'Уведомления', icon: 'mail', roles: ['DPO', 'ADMIN'] },
    { path: 'webhooks', title: 'Webhooks', icon: 'sensors', roles: ['ADMIN'] },
    { path: 'audit', title: 'Аудит', icon: 'verified_user', roles: ['AUDITOR', 'DPO', 'ADMIN'] },
    { path: 'admin/users', title: 'Пользователи', icon: 'manage_accounts', roles: ['ADMIN'], group: 'Администрирование' },
    { path: 'admin/settings', title: 'Настройки оператора', icon: 'tune', roles: ['ADMIN', 'DPO'] },
  ];

  constructor() {
    this.api.me().subscribe((user) => {
      this.user.set(user);
      this.applyBranding(user);
      this.loadAwaitingForms(user.roles);
    });
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.updateCrumbs();
      }
    });
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

  /** Цвет оператора уходит переменной на корень приложения, всё непохожее на HEX остаётся без внимания. */
  private applyBranding(user: CurrentUser): void {
    if (HEX_COLOR.test((user.color ?? '').trim())) {
      this.host.nativeElement.style.setProperty('--ic-primary', user.color.trim());
    }
  }

  /** Счётчик считают только те, кто принимает решение по маршруту согласования: остальным он ни о чём. */
  private loadAwaitingForms(roles: string[]): void {
    if (!roles.includes('LAWYER') && !roles.includes('DPO')) {
      return;
    }
    // Страница списка форм здесь не нужна, нужен только перечень ждущих решения: берём самую короткую.
    this.api.forms({ size: '1' }).subscribe({
      next: (page) => this.awaitingForms.set(page.awaitingDecision?.length ?? 0),
      error: () => this.awaitingForms.set(0),
    });
  }

  /** Крошки собираются из маршрута: раздел берётся из меню, название страницы — из title маршрута. */
  private updateCrumbs(): void {
    let deepest = this.route;
    while (deepest.firstChild) {
      deepest = deepest.firstChild;
    }
    const page = pageTitle(deepest.snapshot.title);
    const url = this.router.url.split('?')[0].split('#')[0];
    if (!page || url === '/') {
      this.crumbs.set([]);
      return;
    }
    const crumbs: Crumb[] = [{ title: 'Главная', path: '/' }];
    const section = this.section(url);
    // Раздел добавляется, только если открыт не он сам: иначе выходило «Главная / Формы / Формы согласий».
    if (section && url !== `/${section.path}` && section.title !== page) {
      crumbs.push({ title: section.title, path: '/' + section.path });
    }
    crumbs.push({ title: page });
    this.crumbs.set(crumbs);
  }

  /** Раздел, которому принадлежит адрес: подходит самое длинное совпадение по началу пути. */
  private section(url: string): MenuItem | undefined {
    return this.menu
      .filter((item) => item.path && (url === `/${item.path}` || url.startsWith(`/${item.path}/`)))
      .sort((left, right) => right.path.length - left.path.length)[0];
  }
}

import { Routes } from '@angular/router';

/**
 * §16.2: адреса разделов повторяют прежние, чтобы ссылки из писем и закладок не сломались.
 *
 * Экраны грузятся по требованию: рабочее место — два десятка разделов, и складывать их все в первый
 * ответ значило бы заставлять каждого сотрудника скачивать то, чем он не пользуется.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dashboard.component').then((m) => m.DashboardComponent),
    title: 'Главная · In consensu',
  },
  {
    path: 'subjects',
    loadComponent: () => import('./pages/subjects.component').then((m) => m.SubjectsComponent),
    title: 'Клиенты · In consensu',
  },
  {
    path: 'subjects/:id',
    loadComponent: () => import('./pages/subject-card.component').then((m) => m.SubjectCardComponent),
    title: 'Карточка клиента · In consensu',
  },
  {
    path: 'consents/:id',
    loadComponent: () => import('./pages/consent-dossier.component').then((m) => m.ConsentDossierComponent),
    title: 'Досье согласия · In consensu',
  },
  {
    path: 'catalog/types',
    loadComponent: () => import('./pages/catalog-types.component').then((m) => m.CatalogTypesComponent),
    title: 'Типы согласий · In consensu',
  },
  {
    path: 'catalog/forms',
    loadComponent: () => import('./pages/catalog-forms.component').then((m) => m.CatalogFormsComponent),
    title: 'Формы согласий · In consensu',
  },
  {
    path: 'catalog/forms/:id',
    loadComponent: () => import('./pages/catalog-form.component').then((m) => m.CatalogFormComponent),
    title: 'Версия формы · In consensu',
  },
  {
    path: 'catalog/forms/:id/edit',
    loadComponent: () => import('./pages/catalog-builder.component').then((m) => m.CatalogBuilderComponent),
    title: 'Конструктор формы · In consensu',
  },
  {
    path: 'import',
    loadComponent: () => import('./pages/import.component').then((m) => m.ImportComponent),
    title: 'Импорт · In consensu',
  },
  {
    path: 'notifications',
    loadComponent: () => import('./pages/notifications.component').then((m) => m.NotificationsComponent),
    title: 'Уведомления · In consensu',
  },
  {
    path: 'third-parties',
    loadComponent: () => import('./pages/third-parties.component').then((m) => m.ThirdPartiesComponent),
    title: 'Третьи лица · In consensu',
  },
  {
    path: 'webhooks',
    loadComponent: () => import('./pages/webhooks.component').then((m) => m.WebhooksComponent),
    title: 'Webhooks · In consensu',
  },
  {
    path: 'audit',
    loadComponent: () => import('./pages/audit.component').then((m) => m.AuditComponent),
    title: 'Аудит · In consensu',
  },
  {
    path: 'admin/users',
    loadComponent: () => import('./pages/admin-users.component').then((m) => m.AdminUsersComponent),
    title: 'Пользователи · In consensu',
  },
  {
    path: 'admin/settings',
    loadComponent: () => import('./pages/admin-settings.component').then((m) => m.AdminSettingsComponent),
    title: 'Настройки оператора · In consensu',
  },
  { path: '**', redirectTo: '' },
];

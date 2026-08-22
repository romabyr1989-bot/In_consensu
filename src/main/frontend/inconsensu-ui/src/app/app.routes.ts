import { Routes } from '@angular/router';
import { ConsentDossierComponent } from './pages/consent-dossier.component';
import { DashboardComponent } from './pages/dashboard.component';
import { SubjectCardComponent } from './pages/subject-card.component';
import { SubjectsComponent } from './pages/subjects.component';
import { ThirdPartiesComponent } from './pages/third-parties.component';

/** §16.2: адреса разделов повторяют прежние, чтобы ссылки из писем и закладок не сломались. */
export const routes: Routes = [
  { path: '', component: DashboardComponent, title: 'Главная · In consensu' },
  { path: 'subjects', component: SubjectsComponent, title: 'Клиенты · In consensu' },
  { path: 'subjects/:id', component: SubjectCardComponent, title: 'Карточка клиента · In consensu' },
  { path: 'consents/:id', component: ConsentDossierComponent, title: 'Досье согласия · In consensu' },
  { path: 'third-parties', component: ThirdPartiesComponent, title: 'Третьи лица · In consensu' },
  { path: '**', redirectTo: '' },
];

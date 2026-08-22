import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { provideRouter, withHashLocation } from '@angular/router';

import { routes } from './app.routes';
import { russianPaginatorIntl } from './paginator-intl';

/**
 * Настройка приложения.
 *
 * XSRF-имена совпадают с тем, что выставляет Spring Security: запросы на изменение уходят с заголовком
 * X-XSRF-TOKEN, иначе сервер справедливо отвечает отказом (UI-0.3).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    provideAnimationsAsync(),
    { provide: MatPaginatorIntl, useFactory: russianPaginatorIntl },
  ],
};

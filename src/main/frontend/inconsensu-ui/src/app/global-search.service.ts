import { Injectable, signal } from '@angular/core';

/**
 * Запрос глобального поиска (§16.1).
 *
 * <p>Хранится в памяти приложения, а не в адресе: строка поиска содержит телефон, почту или ФИО, а
 * персональным данным нельзя попадать ни в адресную строку, ни в журнал веб-сервера (UI-0.10).
 */
@Injectable({ providedIn: 'root' })
export class GlobalSearchService {
  private readonly pending = signal('');

  ask(query: string): void {
    this.pending.set(query.trim());
  }

  /** Забирает запрос ровно один раз: возврат на экран поиска не должен повторять чужой запрос. */
  take(): string {
    const query = this.pending();
    this.pending.set('');
    return query;
  }
}

import { MatPaginatorIntl } from '@angular/material/paginator';

/**
 * Подписи постраничности по-русски (NFR-8).
 *
 * <p>Material отдаёт их по-английски, а интерфейс сотрудника русскоязычный целиком. Провайдер общий:
 * иначе каждый экран переводил бы одно и то же по-своему.
 */
export function russianPaginatorIntl(): MatPaginatorIntl {
  const intl = new MatPaginatorIntl();
  intl.itemsPerPageLabel = 'Строк на странице';
  intl.nextPageLabel = 'Следующая страница';
  intl.previousPageLabel = 'Предыдущая страница';
  intl.firstPageLabel = 'Первая страница';
  intl.lastPageLabel = 'Последняя страница';
  intl.getRangeLabel = (page: number, pageSize: number, length: number): string => {
    if (length === 0 || pageSize === 0) {
      return 'нет записей';
    }
    const total = Math.max(length, 0);
    const start = page * pageSize;
    const end = start < total ? Math.min(start + pageSize, total) : start + pageSize;
    return `${start + 1}–${end} из ${total}`;
  };
  return intl;
}

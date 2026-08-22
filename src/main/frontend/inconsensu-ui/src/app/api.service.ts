import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Кто вошёл и как выглядит оператор (UI-0.5, UI-0.12). */
export interface CurrentUser {
  login: string;
  roles: string[];
  operatorName: string;
  color: string;
  logoUrl: string;
}

/** Плитки и блоки главной (UI-2). */
export interface Dashboard {
  activeConsents: number;
  expiringConsents: number;
  revokedConsents: number;
  awaitingApproval: number;
  expiringContracts: number;
  publishedForms: number;
  recentNotifications: { recipient: string; subject: string; status: string }[];
  failedDeliveries: number;
  failedImports: number;
}

/** Строка результата поиска клиента (UI-3): контакты уже замаскированы по роли. */
export interface SubjectRow {
  id: string;
  fullName: string;
  externalId: string;
  phone: string;
  email: string;
  active: number;
  expiring: number;
  revoked: number;
}

/** Строка справочника третьих лиц (UI-11). */
export interface PartyRow {
  id: string;
  name: string;
  inn: string;
  roleRu: string;
  contractNumber: string;
  contractUntil: string;
  contractBadge: string;
  contractBadgeKind: string;
  categoriesRu: string;
  active: boolean;
  consentsActive: number;
  consentsExpiring: number;
  consentsRevoked: number;
}

/** Согласие в карточке клиента и в досье (UI-4). */
export interface ConsentCard {
  id: string;
  typeName: string;
  status: string;
  statusText: string;
  daysLeft: number | null;
  grantedAt: string;
  validUntil: string;
  source: string;
  thirdPartyName: string;
  categories: string;
  revocable: boolean;
  contractExpired: boolean;
}

export interface ContactCard {
  type: string;
  typeRu: string;
  value: string;
  masked: boolean;
}

export interface ChannelCard {
  channel: string;
  nameRu: string;
  allowed: boolean;
  validUntil: string;
  reason: string;
}

export interface TransferCard {
  thirdPartyName: string;
  role: string;
  categories: string;
  validUntil: string;
  daysLeft: string;
  basisConsentId: string | null;
  contractExpired: boolean;
}

/** Карточка клиента целиком: экран собирается одним запросом (UI-4). */
export interface SubjectCard {
  id: string;
  fullName: string;
  externalId: string;
  birthDate: string;
  summary: string;
  contacts: ContactCard[];
  channels: ChannelCard[];
  consents: ConsentCard[];
  transfers: TransferCard[];
  mayReveal: boolean;
  mayRevoke: boolean;
}

export interface HistoryEntry {
  occurredAt: string;
  eventTypeRu: string;
  description: string;
  actorRu: string;
  consentId: string | null;
}

/** truncated — событий больше, чем показано: остальные видны после сужения периода. */
export interface HistoryFeed {
  entries: HistoryEntry[];
  total: number;
  truncated: boolean;
}

export interface RevocableConsent {
  id: string;
  title: string;
}

/** Что отправляет диалог отзыва (UI-5). */
export interface RevocationRequest {
  reason: string;
  revocationSource: string;
  caseNumber: string;
  documentRef?: string;
  allAdvertising?: boolean;
}

/** Значение справочника: код уходит на сервер, название показывается человеку. */
export interface DictionaryItem {
  code: string;
  nameRu: string;
}

export interface Dictionaries {
  revocationSources: DictionaryItem[];
  auditEventTypes: DictionaryItem[];
}

/** Досье согласия (UI-4a): сведения, текст формы, контрольная сумма и события. */
export interface ConsentDossier {
  id: string;
  subjectId: string;
  subjectName: string;
  consentTypeRu: string;
  statusRu: string;
  grantedAt: string;
  validUntil: string;
  source: string;
  signatureTypeRu: string;
  revokedAt: string;
  revocationSourceRu: string;
  revocationReason: string;
  formTitle: string;
  formVersion: string;
  formText: string;
  storedChecksum: string;
  checksumMatches: boolean;
  integrityOk: boolean;
  integrityMessage: string;
  evidence: Record<string, unknown>;
  events: HistoryEntry[];
}

/**
 * Обращения к серверу.
 *
 * Данные берутся из `/ui/api`, а не из машинной цепочки §12: сотрудник работает по серверной сессии,
 * и токен в JavaScript не выносится (UI-0.3).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  me(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>('/ui/api/me');
  }

  dashboard(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/ui/api/dashboard');
  }

  /** Поиск идёт POST-ом: телефону, почте и ФИО нельзя попадать в адрес (UI-0.10). */
  searchSubjects(query: string): Observable<SubjectRow[]> {
    return this.http.post<SubjectRow[]>('/ui/api/subjects/search', { query });
  }

  thirdParties(contract?: string): Observable<PartyRow[]> {
    const query = contract ? `?contract=${encodeURIComponent(contract)}` : '';
    return this.http.get<PartyRow[]>(`/ui/api/third-parties${query}`);
  }

  subjectCard(id: string, superseded = false): Observable<SubjectCard> {
    return this.http.get<SubjectCard>(`/ui/api/subjects/${id}?superseded=${superseded}`);
  }

  history(id: string, eventType?: string, from?: string, to?: string): Observable<HistoryFeed> {
    const query = new URLSearchParams();
    if (eventType) {
      query.set('eventType', eventType);
    }
    if (from) {
      query.set('from', from);
    }
    if (to) {
      query.set('to', to);
    }
    const suffix = query.toString() ? `?${query}` : '';
    return this.http.get<HistoryFeed>(`/ui/api/subjects/${id}/history${suffix}`);
  }

  verifyHistory(id: string): Observable<{ intact: boolean; checked: number; message: string }> {
    return this.http.post<{ intact: boolean; checked: number; message: string }>(
      `/ui/api/subjects/${id}/history/verify`,
      {},
    );
  }

  /** Раскрытие контакта — действие, а не просмотр: оно попадает в журнал доступа к ПДн (UI-0.10). */
  reveal(id: string, type: string): Observable<ContactCard> {
    return this.http.post<ContactCard>(`/ui/api/subjects/${id}/reveal`, { type });
  }

  revocable(id: string): Observable<RevocableConsent[]> {
    return this.http.get<RevocableConsent[]>(`/ui/api/subjects/${id}/revocable`);
  }

  cascade(consentId: string): Observable<ConsentCard[]> {
    return this.http.get<ConsentCard[]>(`/ui/api/consents/${consentId}/cascade`);
  }

  revoke(consentId: string, request: RevocationRequest): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/consents/${consentId}/revoke`, request);
  }

  dictionaries(): Observable<Dictionaries> {
    return this.http.get<Dictionaries>('/ui/api/dictionaries');
  }

  dossier(consentId: string): Observable<ConsentDossier> {
    return this.http.get<ConsentDossier>(`/ui/api/consents/${consentId}`);
  }
}




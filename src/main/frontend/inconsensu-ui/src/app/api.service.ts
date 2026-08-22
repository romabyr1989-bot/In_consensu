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

/** Тип согласия (UI-6). */
export interface TypeRow {
  code: string;
  nameRu: string;
  description: string;
  category: string;
  categoryRu: string;
  channels: string[];
  requiresThirdParty: boolean;
  defaultValidity: string | null;
  dependsOnCode: string | null;
  businessSignificant: boolean;
  active: boolean;
  sortOrder: number;
  consentsActive: number;
  consentsExpiring: number;
  consentsRevoked: number;
}

/** Что отправляет форма типа: код задаётся только при создании (FR-1.1). */
export interface TypeRequest {
  code: string;
  nameRu: string;
  description: string;
  category: string;
  channels: string[];
  requiresThirdParty: boolean;
  defaultValidity: string | null;
  dependsOnCode: string | null;
  businessSignificant: boolean;
  sortOrder: number;
  update: boolean;
}

export interface FormRow {
  id: string;
  code: string;
  title: string;
  version: number;
  status: string;
  statusRu: string;
  updatedAt: string;
  editable: boolean;
}

export interface FormPage {
  rows: FormRow[];
  total: number;
  awaitingDecision: FormRow[];
}

export interface ItemDraft {
  typeCode: string;
  text: string;
  purposes: string[];
  categories: string[];
  thirdPartyId: string | null;
  validity: string | null;
  mandatory: boolean;
}

export interface ItemRow {
  typeRu: string;
  text: string;
  purposes: string;
  pdnCategoriesRu: string;
  thirdPartyRu: string;
  validityRu: string;
  mandatory: boolean;
}

/** Нарушение или предупреждение конструктора; itemNumber — номер пункта, если дело в нём. */
export interface Finding {
  code: string;
  messageRu: string;
  itemNumber: number | null;
}

export interface Requisite {
  code: string;
  nameRu: string;
  satisfied: boolean;
}

export interface ApprovalRow {
  roleRu: string;
  approved: boolean;
  actor: string;
  decidedAt: string;
  comment: string;
}

export interface WorkflowEntry {
  at: string;
  eventType: string;
  eventTypeRu: string;
  fromStatus: string;
  toStatus: string;
  actor: string;
  comment: string;
}

/** Форма целиком: одного запроса хватает и просмотру версии, и согласованию, и конструктору. */
export interface FormDetails {
  id: string;
  code: string;
  title: string;
  version: number;
  status: string;
  statusRu: string;
  body: string;
  processingActions: string;
  revocationProcedure: string;
  sourceChannels: string[];
  draftItems: ItemDraft[];
  items: ItemRow[];
  previewHtml: string;
  checksum: string;
  valid: boolean;
  violations: Finding[];
  warnings: Finding[];
  checklist: Requisite[];
  versions: FormRow[];
  issuedConsents: number;
  editable: boolean;
  approvals: ApprovalRow[];
  history: WorkflowEntry[];
}

/** Черновик целиком: конструктор всегда отправляет полный состав, частичных правок нет (UI-8). */
export interface DraftRequest {
  title: string;
  body: string;
  processingActions: string;
  revocationProcedure: string;
  sourceChannels: string[];
  items: ItemDraft[];
}

/** Справочники конструктора (UI-8). */
export interface BuilderOptions {
  types: { code: string; nameRu: string; requiresThirdParty: boolean }[];
  pdnCategories: DictionaryItem[];
  thirdParties: { id: string; name: string }[];
  sources: DictionaryItem[];
  statuses: DictionaryItem[];
  categories: DictionaryItem[];
  channels: DictionaryItem[];
}

/** Строка сравнения версий: kind — added / removed / same (FR-3.2). */
export interface DiffLine {
  kind: string;
  text: string;
}

/** Задача импорта (UI-12); dryRun — пробный запуск, ничего не пишется. */
export interface JobRow {
  id: string;
  fileName: string;
  source: string;
  dryRun: boolean;
  status: string;
  statusRu: string;
  total: number;
  imported: number;
  rejected: number;
  percent: number;
  startedBy: string;
  startedAt: string;
  finishedAt: string;
}

export interface JobDetails {
  job: JobRow;
  report: Record<string, unknown>[];
}

export interface ImportPage {
  rows: JobRow[];
  total: number;
  sources: DictionaryItem[];
}

/** Правило уведомлений (UI-13). */
export interface RuleRow {
  id: string;
  name: string;
  triggerRu: string;
  thresholds: string;
  filtersRu: string;
  recipients: string;
  channelsRu: string[];
  active: boolean;
}

export interface RuleRequest {
  ruleId: string | null;
  name: string;
  triggerType: string;
  daysBefore: string;
  recipientEmails: string[];
  recipientRoles: string[];
  channels: string[];
  consentTypeId: string | null;
  thirdPartyId: string | null;
}

/** Запись журнала отправок; canRetry — повтор предлагается только для неудавшихся. */
export interface JournalRow {
  id: string;
  createdAt: string;
  recipient: string;
  subjectLine: string;
  channelRu: string;
  statusRu: string;
  ruleName: string;
  error: string;
  canRetry: boolean;
}

export interface Journal {
  rows: JournalRow[];
  total: number;
}

export interface MessageView {
  id: string;
  recipient: string;
  subjectLine: string;
  body: string;
  channelRu: string;
  statusRu: string;
  createdAt: string;
  sentAt: string;
  error: string;
}

export interface NotificationOptions {
  triggers: DictionaryItem[];
  channels: DictionaryItem[];
  statuses: DictionaryItem[];
}

/** Подписка на webhooks (UI-14). */
export interface SubscriptionRow {
  id: string;
  name: string;
  url: string;
  eventTypes: string[];
  active: boolean;
  lastDeliveryAt: string;
  lastDeliveryResult: string;
  lastDeliverySuccessful: boolean;
}

export interface DeliveryRow {
  eventId: string;
  deliveredAt: string;
  attempt: number;
  responseCode: string;
  error: string;
  successful: boolean;
}

/** secret приходит только при создании: показать его можно один раз. */
export interface SubscriptionSaved {
  rows: SubscriptionRow[];
  secret: string | null;
}

export interface AuditEventRow {
  occurredAt: string;
  aggregateType: string;
  aggregateId: string;
  eventTypeRu: string;
  actor: string;
  payload: string;
}

export interface AccessRow {
  occurredAt: string;
  user: string;
  endpoint: string;
  subjectId: string;
  requestId: string;
}

export interface VerificationRow {
  id: string;
  status: string;
  startedBy: string;
  startedAt: string;
  finishedAt: string;
  integrity: string;
  aggregatesChecked: number;
  eventsChecked: number;
  anchorsChecked: number;
  problems: string;
  error: string;
}

export interface UserRow {
  id: string;
  login: string;
  fullName: string;
  email: string;
  roles: string[];
  active: boolean;
  lastLoginAt: string;
}

export interface UserRequest {
  id: string | null;
  login: string;
  password: string;
  fullName: string;
  email: string;
  roles: string[];
  active: boolean;
}

/** Настройка оператора: readOnly — значение задаётся при установке и правке не подлежит. */
export interface SettingView {
  key: string;
  label: string;
  hint: string;
  value: string;
  readOnly: boolean;
}

export interface SettingGroup {
  title: string;
  settings: SettingView[];
}

/** Страница списка: строки и сколько их всего под фильтром. */
export interface Paged<T> {
  rows: T[];
  total: number;
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

  // ---------- Каталог (UI-6 … UI-10) ----------

  types(filters: Record<string, string> = {}): Observable<TypeRow[]> {
    const query = new URLSearchParams(filters).toString();
    return this.http.get<TypeRow[]>(`/ui/api/catalog/types${query ? '?' + query : ''}`);
  }

  saveType(request: TypeRequest): Observable<TypeRow> {
    return this.http.post<TypeRow>('/ui/api/catalog/types', request);
  }

  deactivateType(code: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/catalog/types/${encodeURIComponent(code)}/deactivate`, {});
  }

  forms(filters: Record<string, string> = {}): Observable<FormPage> {
    const query = new URLSearchParams(filters).toString();
    return this.http.get<FormPage>(`/ui/api/catalog/forms${query ? '?' + query : ''}`);
  }

  createForm(code: string, title: string): Observable<FormRow> {
    return this.http.post<FormRow>('/ui/api/catalog/forms', { code, title });
  }

  form(id: string): Observable<FormDetails> {
    return this.http.get<FormDetails>(`/ui/api/catalog/forms/${id}`);
  }

  saveDraft(id: string, draft: DraftRequest): Observable<FormDetails> {
    return this.http.post<FormDetails>(`/ui/api/catalog/forms/${id}`, draft);
  }

  deleteDraft(id: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/catalog/forms/${id}/delete`, {});
  }

  /** Переходы по маршруту согласования: submit, approve, reject, publish, archive (FR-1.4). */
  formAction(id: string, action: string, comment?: string): Observable<FormDetails> {
    return this.http.post<FormDetails>(`/ui/api/catalog/forms/${id}/${action}`, { comment: comment ?? '' });
  }

  newVersion(id: string): Observable<FormRow> {
    return this.http.post<FormRow>(`/ui/api/catalog/forms/${id}/new-version`, {});
  }

  diff(id: string): Observable<DiffLine[]> {
    return this.http.get<DiffLine[]>(`/ui/api/catalog/forms/${id}/diff`);
  }

  builderOptions(): Observable<BuilderOptions> {
    return this.http.get<BuilderOptions>('/ui/api/catalog/builder-options');
  }

  // ---------- Импорт и уведомления (UI-12, UI-13) ----------

  jobs(): Observable<ImportPage> {
    return this.http.get<ImportPage>('/ui/api/import');
  }

  job(id: string): Observable<JobDetails> {
    return this.http.get<JobDetails>(`/ui/api/import/${id}`);
  }

  /** Пробный запуск по умолчанию: боевой импорт правит базу целиком (FR-4.5). */
  upload(file: File, dryRun: boolean, source: string): Observable<JobRow> {
    const body = new FormData();
    body.append('file', file);
    return this.http.post<JobRow>(`/ui/api/import?dryRun=${dryRun}&source=${source}`, body);
  }

  runForReal(id: string): Observable<JobRow> {
    return this.http.post<JobRow>(`/ui/api/import/${id}/run`, {});
  }

  rules(): Observable<RuleRow[]> {
    return this.http.get<RuleRow[]>('/ui/api/notifications/rules');
  }

  saveRule(request: RuleRequest): Observable<RuleRow[]> {
    return this.http.post<RuleRow[]>('/ui/api/notifications/rules', request);
  }

  deactivateRule(id: string): Observable<RuleRow[]> {
    return this.http.post<RuleRow[]>(`/ui/api/notifications/rules/${id}/deactivate`, {});
  }

  journal(filters: Record<string, string> = {}): Observable<Journal> {
    const query = new URLSearchParams(filters).toString();
    return this.http.get<Journal>(`/ui/api/notifications/journal${query ? '?' + query : ''}`);
  }

  message(id: string): Observable<MessageView> {
    return this.http.get<MessageView>(`/ui/api/notifications/${id}`);
  }

  retryNotification(id: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/notifications/${id}/retry`, {});
  }

  testEmail(email: string): Observable<{ message?: string; error?: string }> {
    return this.http.post<{ message?: string; error?: string }>('/ui/api/notifications/test-email', { email });
  }

  notificationOptions(): Observable<NotificationOptions> {
    return this.http.get<NotificationOptions>('/ui/api/notifications/options');
  }

  // ---------- Webhooks, аудит, администрирование (UI-14 … UI-17) ----------

  webhooks(): Observable<SubscriptionRow[]> {
    return this.http.get<SubscriptionRow[]>('/ui/api/webhooks');
  }

  saveWebhook(request: {
    subscriptionId: string | null;
    name: string;
    url: string;
    eventTypes: string[];
  }): Observable<SubscriptionSaved> {
    return this.http.post<SubscriptionSaved>('/ui/api/webhooks', request);
  }

  deliveries(id: string): Observable<Paged<DeliveryRow>> {
    return this.http.get<Paged<DeliveryRow>>(`/ui/api/webhooks/${id}/deliveries`);
  }

  retryDelivery(id: string, eventId: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/webhooks/${id}/deliveries/${eventId}/retry`, {});
  }

  testWebhook(id: string): Observable<{ message?: string; error?: string }> {
    return this.http.post<{ message?: string; error?: string }>(`/ui/api/webhooks/${id}/test`, {});
  }

  auditEvents(filters: Record<string, string> = {}): Observable<Paged<AuditEventRow>> {
    const query = new URLSearchParams(filters).toString();
    return this.http.get<Paged<AuditEventRow>>(`/ui/api/audit/events${query ? '?' + query : ''}`);
  }

  accessLog(filters: Record<string, string> = {}): Observable<Paged<AccessRow>> {
    const query = new URLSearchParams(filters).toString();
    return this.http.get<Paged<AccessRow>>(`/ui/api/audit/access-log${query ? '?' + query : ''}`);
  }

  verifications(): Observable<VerificationRow[]> {
    return this.http.get<VerificationRow[]>('/ui/api/audit/integrity');
  }

  startVerification(): Observable<VerificationRow[]> {
    return this.http.post<VerificationRow[]>('/ui/api/audit/integrity', {});
  }

  users(): Observable<Paged<UserRow> & { roles: DictionaryItem[] }> {
    return this.http.get<Paged<UserRow> & { roles: DictionaryItem[] }>('/ui/api/admin/users');
  }

  saveUser(request: UserRequest): Observable<UserRow> {
    return this.http.post<UserRow>('/ui/api/admin/users', request);
  }

  resetPassword(id: string, password: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/ui/api/admin/users/${id}/reset-password`, { password });
  }

  settings(): Observable<SettingGroup[]> {
    return this.http.get<SettingGroup[]>('/ui/api/admin/settings');
  }

  updateSettings(changes: Record<string, string>): Observable<SettingGroup[]> {
    return this.http.post<SettingGroup[]>('/ui/api/admin/settings', changes);
  }
}







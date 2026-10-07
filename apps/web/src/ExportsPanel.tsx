import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api, type CreateCsvExport, type TenantMembership } from './api';

type Language = 'ru' | 'en';
type Role = TenantMembership['role'];

const copy = {
  ru: {
    title: 'Экспорт CSV', from: 'С даты', to: 'По дату', scope: 'Область операций', all: 'Все участники',
    create: 'Создать CSV', loading: 'Создаём экспорт…', queued: 'В очереди', processing: 'Формируем файл',
    ready: 'Файл готов', failed: 'Не удалось создать файл', expired: 'Срок файла истёк',
    download: 'Скачать CSV', count: 'Операций: {count}', empty: 'Файл содержит только заголовки: операций за период нет.',
    createAnother: 'Создать другой экспорт', invalid: 'Проверьте даты: период должен быть от 1 до 366 дней.',
    error: 'Не удалось загрузить экспорт.', retry: 'Повторить', member: 'Только участник',
  },
  en: {
    title: 'CSV export', from: 'From date', to: 'To date', scope: 'Member scope', all: 'All members',
    create: 'Create CSV', loading: 'Creating export…', queued: 'Queued', processing: 'Preparing file',
    ready: 'Export ready', failed: 'Export failed', expired: 'Export expired',
    download: 'Download CSV', count: 'Transactions: {count}', empty: 'No transactions in this period. The file has headers only.',
    createAnother: 'Create another export', invalid: 'Check dates: period must be 1 to 366 days.',
    error: 'Could not load export.', retry: 'Retry', member: 'Member only',
  },
} as const;

function localDate(timezone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now);
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function shiftDate(value: string, days: number): string {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

function inclusiveDays(from: string, to: string): number {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  return Number.isFinite(start) && Number.isFinite(end) ? Math.floor((end - start) / 86_400_000) + 1 : 0;
}

function interpolate(value: string, values: Record<string, string | number>): string {
  return value.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''));
}

export function ExportsPanel({ tenantId, role, language, timezone }: {
  tenantId: string; role: Role; language: Language; timezone: string;
}) {
  const t = copy[language];
  const manager = role === 'owner' || role === 'admin';
  const today = useMemo(() => localDate(timezone), [timezone]);
  const [fromDate, setFromDate] = useState(() => shiftDate(today, -29));
  const [toDate, setToDate] = useState(today);
  const [memberScope, setMemberScope] = useState('own');
  const [exportId, setExportId] = useState<string | null>(null);
  const members = useQuery({
    queryKey: ['members', tenantId], queryFn: () => api.getMembers(tenantId),
    enabled: manager, retry: false,
  });
  const create = useMutation({
    mutationFn: (request: CreateCsvExport) => api.createExport(tenantId, request),
    onSuccess: (job) => setExportId(job.id),
  });
  const job = useQuery({
    queryKey: ['export', tenantId, exportId], queryFn: () => api.getExport(tenantId, exportId!),
    enabled: Boolean(exportId), retry: false, refetchInterval: (query) =>
      query.state.data && ['queued', 'processing'].includes(query.state.data.status) ? 1000 : false,
  });
  const days = inclusiveDays(fromDate, toDate);
  const validPeriod = days >= 1 && days <= 366;
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!validPeriod || create.isPending) return;
    const request: CreateCsvExport = { formatVersion: 'csv-v1', fromDate, toDate };
    if (manager && memberScope === 'all') request.memberId = 'all';
    else if (manager && memberScope !== 'own') request.memberId = memberScope;
    setExportId(null);
    create.mutate(request);
  };
  const status = job.data?.status;
  const statusLabel = status ? t[status] : null;
  const error = create.error?.message ?? job.error?.message;

  return <section className="exports-panel panel" aria-label={t.title}>
    <div className="panel-heading"><div><span className="eyebrow">F52 · csv-v1</span><h2>{t.title}</h2></div></div>
    <form className="export-form" onSubmit={submit}>
      <label>{t.from}<input aria-label={t.from} type="date" value={fromDate}
        onChange={(event) => setFromDate(event.target.value)} required /></label>
      <label>{t.to}<input aria-label={t.to} type="date" value={toDate}
        onChange={(event) => setToDate(event.target.value)} required /></label>
      {manager && <label>{t.scope}<select aria-label={t.scope} value={memberScope}
        onChange={(event) => setMemberScope(event.target.value)}>
        <option value="own">{t.member}</option><option value="all">{t.all}</option>
        {(members.data ?? []).map((member) =>
          <option key={member.userId} value={member.userId}>{member.displayName}</option>)}
      </select></label>}
      {!validPeriod && <p className="form-error" role="alert">{t.invalid}</p>}
      {error && <p className="form-error" role="alert">{error || t.error}</p>}
      <button className="button" type="submit" disabled={!validPeriod || create.isPending}>
        {create.isPending ? t.loading : status === 'failed' || status === 'expired' ? t.createAnother : t.create}
      </button>
    </form>
    {members.isError && manager && <p className="form-error" role="alert">{members.error.message || t.error}</p>}
    {job.isPending && exportId && <p className="export-status" role="status">{t.loading}</p>}
    {job.isError && <div className="export-status"><p role="alert">{job.error.message || t.error}</p>
      <button className="button button-quiet" onClick={() => void job.refetch()}>{t.retry}</button></div>}
    {job.data && status && <div className={`export-status export-status-${status}`} aria-live="polite">
      <strong>{statusLabel}</strong>
      {status === 'ready' && <p>{interpolate(t.count, { count: job.data.rowCount })}</p>}
      {status === 'ready' && job.data.rowCount === 0 && <p>{t.empty}</p>}
      {status === 'queued' && <progress aria-label={t.queued} />}
      {status === 'processing' && <progress aria-label={t.processing} />}
      {status === 'ready' && job.data.downloadUrl && <a className="button" href={job.data.downloadUrl}
        target="_blank" rel="noreferrer">{t.download}</a>}
    </div>}
  </section>;
}

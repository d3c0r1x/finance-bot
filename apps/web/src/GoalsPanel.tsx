import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type GoalCandidate, type MemberGoal } from './api';

type Language = 'ru' | 'en';

const copy = {
  ru: {
    title: 'Цель на месяц', unit: 'Формат цели', count: 'Покупки', sum: 'Сумма', saveUnit: 'Сохранить формат',
    loading: 'Загрузка целей…', error: 'Не удалось загрузить цели.', retry: 'Повторить', watermark: 'Версия данных',
    candidates: 'Предложения', groups: 'Категории', noCandidates: 'Пока нет подходящих целей. Нужны подтверждённые покупки и история чеков.',
    cadence: 'обычная частота: {rate} {noun} в месяц', target: 'цель: не больше {target} {noun} в месяц',
    limit: 'лимит в месяц', baselineSpend: 'Оценка расходов за месяц', estimated: 'оценка сокращения расходов', evidence: 'Подтверждённых сигналов',
    purchases: 'Покупок в истории', accept: 'Поставить цель', activeTitle: 'Текущая цель', active: 'Цель активна',
    remaining: 'Осталось дней: {days}', ends: 'До', cancel: 'Отменить цель', cancelConfirm: 'Цель будет остановлена. Продолжить?',
    cancelled: 'Цель отменена', noMoney: 'Сумма неизвестна', missingAmounts: 'Не хватает сумм в чеках',
    minimumSavings: 'Пока нет полезного денежного лимита', stale: 'Данные изменились. Обновите предложения и выберите цель заново.',
    writeError: 'Не удалось сохранить изменение.', refresh: 'Обновить предложения', group: 'Категория', product: 'Товар',
  },
  en: {
    title: 'Monthly goal', unit: 'Goal measure', count: 'Purchases', sum: 'Spend', saveUnit: 'Save measure',
    loading: 'Loading goals…', error: 'Could not load goals.', retry: 'Retry', watermark: 'Data version',
    candidates: 'Suggestions', groups: 'Categories', noCandidates: 'No eligible goals yet. Confirmed purchases and receipt history are needed.',
    cadence: 'usual rate: {rate} {noun} per month', target: 'target: at most {target} {noun} per month',
    limit: 'monthly limit', baselineSpend: 'Estimated monthly spend', estimated: 'estimated spend reduction', evidence: 'Confirmed signals',
    purchases: 'Purchases in history', accept: 'Set goal', activeTitle: 'Active goal', active: 'Goal is active',
    remaining: 'Days left: {days}', ends: 'Until', cancel: 'Cancel goal', cancelConfirm: 'This will stop the goal. Continue?',
    cancelled: 'Goal cancelled', noMoney: 'Amount unknown', missingAmounts: 'Receipt amounts are missing',
    minimumSavings: 'No useful spending limit yet', stale: 'Data changed. Refresh suggestions and choose again.',
    writeError: 'Could not save this change.', refresh: 'Refresh suggestions', group: 'Category', product: 'Product',
  },
} as const;

function money(value: string | null, language: Language): string {
  if (value === null) return '—';
  return new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(value));
}

function number(value: string, language: Language): string {
  return new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', { maximumFractionDigits: 2 }).format(Number(value));
}

function interpolate(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''));
}

function date(value: string, language: Language): string {
  return new Intl.DateTimeFormat(language === 'ru' ? 'ru-RU' : 'en-US', { dateStyle: 'medium' }).format(new Date(value));
}

function remainingDays(goal: MemberGoal): number {
  const startsAt = new Date(goal.acceptedAt).getTime();
  const endsAt = new Date(goal.endsAt).getTime();
  const totalDays = Math.ceil((endsAt - startsAt) / 86_400_000);
  return Math.min(totalDays, Math.max(0, Math.ceil((endsAt - Date.now()) / 86_400_000)));
}

function purchaseNoun(count: number, language: Language): string {
  if (language === 'en') return count === 1 ? 'purchase' : 'purchases';
  if (!Number.isInteger(count)) return 'покупки';
  const tail = count % 10;
  const lastTwo = count % 100;
  if (tail === 1 && lastTwo !== 11) return 'покупка';
  if (tail >= 2 && tail <= 4 && (lastTwo < 12 || lastTwo > 14)) return 'покупки';
  return 'покупок';
}

export function GoalsPanel({ tenantId, language, canWrite = true }: {
  tenantId: string; language: Language; canWrite?: boolean;
}) {
  const t = copy[language];
  const queryClient = useQueryClient();
  const queryKey = ['goals', tenantId];
  const query = useQuery({ queryKey, queryFn: () => api.getGoals(tenantId), retry: false });
  const [selectedUnit, setSelectedUnit] = useState<'count' | 'sum'>('count');
  useEffect(() => { if (query.data) setSelectedUnit(query.data.unit); }, [query.data?.unit]);
  const refresh = () => queryClient.invalidateQueries({ queryKey });
  const updateUnit = useMutation({ mutationFn: (unit: 'count' | 'sum') => api.updateGoalUnit(tenantId, unit), onSuccess: refresh });
  const accept = useMutation({
    mutationFn: (candidate: { candidateKey: string; inputWatermark: string }) => api.acceptGoal(tenantId, candidate),
    onSuccess: refresh,
  });
  const cancel = useMutation({ mutationFn: (goalId: string) => api.cancelGoal(tenantId, goalId), onSuccess: refresh });
  const overview = query.data;
  const mutationError = updateUnit.error ?? accept.error ?? cancel.error;
  const serverError = mutationError?.message ?? query.error?.message;
  const isStale = Boolean(serverError && /stale|changed|устар|изменил/i.test(serverError));
  const pending = updateUnit.isPending || accept.isPending || cancel.isPending;

  if (query.isPending) return <section className="goals-panel panel" aria-label={t.title}>
    <p className="empty-state" role="status">{t.loading}</p>
  </section>;
  if (query.isError || !overview) return <section className="goals-panel panel" aria-label={t.title}>
    <div className="empty-state"><p role="alert">{isStale ? t.stale : serverError || t.error}</p>
      <button className="button button-quiet" onClick={() => void query.refetch()}>{t.retry}</button></div>
  </section>;

  const candidates = overview.candidates;
  const groups = overview.groups;
  const shownError = mutationError ? (isStale ? t.stale : mutationError.message || t.writeError) : null;

  return <section className="goals-panel panel" aria-label={t.title}>
    <div className="panel-heading goals-heading">
      <div><span className="eyebrow">F44 · {t.watermark}: {overview.inputWatermark}</span><h2>{t.title}</h2></div>
      {canWrite && <div className="goal-unit-control">
        <label>{t.unit}<select aria-label={t.unit} value={selectedUnit} disabled={pending}
          onChange={(event) => setSelectedUnit(event.target.value as 'count' | 'sum')}>
          <option value="count">{t.count}</option><option value="sum">{t.sum}</option>
        </select></label>
        {selectedUnit !== overview.unit && <button className="button button-quiet" disabled={pending}
          onClick={() => updateUnit.mutate(selectedUnit)}>{t.saveUnit}</button>}
      </div>}
    </div>
    {shownError && <div className="goal-error"><p className="form-error" role="alert">{shownError}</p>
      <button className="button button-quiet" disabled={query.isFetching} onClick={() => {
        updateUnit.reset(); accept.reset(); cancel.reset(); void query.refetch();
      }}>{t.refresh}</button></div>}
    {overview.active && <article className="goal-active-card">
      <div><span className="goal-badge">{t.active}</span><h3>{overview.active.name}</h3>
        <p>{overview.active.unit === 'count'
          ? interpolate(t.target, { target: overview.active.countTarget,
            noun: purchaseNoun(overview.active.countTarget, language) })
          : `${t.limit}: ${money(overview.active.monthlyLimit, language)}`}</p>
        <p>{interpolate(t.cadence, { rate: number(overview.active.monthlyRate, language),
          noun: purchaseNoun(Number(overview.active.monthlyRate), language) })}</p>
        <p>{t.baselineSpend}: {money(overview.active.monthlySpend, language)}</p>
        <p>{interpolate(t.remaining, { days: remainingDays(overview.active) })} · {t.ends} {date(overview.active.endsAt, language)}</p>
        {overview.active.monthlySpend === null && <p>{t.noMoney}</p>}
      </div>
      {canWrite && <button className="button button-quiet" disabled={pending}
        onClick={() => { if (window.confirm(t.cancelConfirm)) cancel.mutate(overview.active!.id); }}>{t.cancel}</button>}
    </article>}
    {candidates.length + groups.length === 0 && <p className="empty-state">{t.noCandidates}</p>}
    {candidates.length > 0 && <GoalCandidates title={t.candidates} candidates={candidates}
      language={language} canWrite={canWrite} hasActive={Boolean(overview.active)} pending={pending}
      onAccept={(candidate) => accept.mutate({ candidateKey: candidate.key, inputWatermark: overview.inputWatermark })} />}
    {groups.length > 0 && <GoalCandidates title={t.groups} kind={t.group} candidates={groups}
      language={language} canWrite={canWrite} hasActive={Boolean(overview.active)} pending={pending}
      onAccept={(candidate) => accept.mutate({ candidateKey: candidate.key, inputWatermark: overview.inputWatermark })} />}
    {overview.skipped.length > 0 && <div className="goal-unavailable" aria-label={t.missingAmounts}>
      <h3>{t.minimumSavings}</h3>
      <ul>{overview.skipped.map((item) => <li key={item.productKey}>
        <span>{item.name}</span><small>{item.reasonCode === 'missing_amounts' ? t.missingAmounts : t.minimumSavings}</small>
      </li>)}</ul>
    </div>}
  </section>;
}

function GoalCandidates({ title, kind, candidates, language, canWrite, hasActive, pending, onAccept }: {
  title: string; kind?: string; candidates: GoalCandidate[]; language: Language;
  canWrite: boolean; hasActive: boolean; pending: boolean; onAccept: (candidate: GoalCandidate) => void;
}) {
  const t = copy[language];
  return <div className="goal-list-section"><h3>{title}</h3><div className="goal-candidate-grid">
    {candidates.map((candidate) => <article className="goal-candidate-card" key={candidate.key}>
      <div className="goal-candidate-top"><span className="eyebrow">{kind ?? t.product}</span>
        <h4>{candidate.name}</h4></div>
      <p>{interpolate(t.cadence, { rate: number(candidate.monthlyRate, language),
        noun: purchaseNoun(Number(candidate.monthlyRate), language) })}</p>
      <p>{candidate.unit === 'count' ? interpolate(t.target, { target: candidate.countTarget,
        noun: purchaseNoun(candidate.countTarget, language) })
        : `${t.limit}: ${money(candidate.monthlyLimit, language)}`}</p>
      <dl><div><dt>{t.estimated}</dt><dd>{money(candidate.estimatedReduction, language)}</dd></div>
        <div><dt>{t.baselineSpend}</dt><dd>{money(candidate.monthlySpend, language)}</dd></div>
        <div><dt>{t.evidence}</dt><dd>{candidate.evidenceCount}</dd></div>
        <div><dt>{t.purchases}</dt><dd>{candidate.purchaseCount}</dd></div>
      </dl>
      {canWrite && <button className="button button-primary" disabled={pending || hasActive}
        aria-label={`${t.accept}: ${candidate.name}`} onClick={() => onAccept(candidate)}>{t.accept}</button>}
    </article>)}
  </div></div>;
}

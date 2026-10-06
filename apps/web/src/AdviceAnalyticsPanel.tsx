import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './api';

type Language = 'ru' | 'en';

const copy = {
  ru: {
    title: 'Личная аналитика привычек покупок', watermark: 'Версия данных', calculate: 'Рассчитать аналитику',
    recalculate: 'Рассчитать заново', retry: 'Повторить расчёт', loading: 'Загрузка аналитики…',
    pending: 'Расчёт поставлен в очередь', processing: 'Анализируем историю покупок…',
    failed: 'Расчёт не выполнен', stale: 'Данные изменились — рассчитайте заново',
    complete: 'Расчёт готов', partial: 'Отчёт неполный: некоторые суммы неизвестны.',
    error: 'Не удалось загрузить аналитику.', ceiling: 'Теоретический потолок за месяц',
    ceilingNote: 'Оценка повторяющихся необязательных покупок за 90 дней, приведённая к 30 дням. Это не фактическая экономия.',
    shareIncome: 'от планового дохода', shareLimit: 'от личного лимита', repeatCount: 'покупок за 90 дней',
    spend90: 'сумма за 90 дней', monthly: 'оценка за 30 дней', unavailableCeiling: 'Нет данных для оценки потолка.',
    trend: 'Доля необязательных расходов по неделям', down: 'Доля снизилась', up: 'Доля выросла', steady: 'Без заметного изменения',
    spend: 'Все расходы', optional: 'Необязательные', share: 'Доля', recalculated: 'Затронуто пересчётом F42',
    unavailableTrend: 'Для сравнения нужны подтверждённые покупки как минимум за две недели.',
    trendPartial: 'Тренд не рассчитан: в части чеков неизвестны суммы.',
    effectTitle: 'Наблюдения после совета', afterAdvice: 'После совета', beforeAdvice: 'До совета',
    noFurtherPurchases: 'Покупок после совета не было в окне наблюдения.', pendingEffect: 'Пока рано сравнивать',
    daysLeft: 'дн. до достаточного окна', effectDisclaimer: 'Это изменение частоты после совета, а не доказательство причинной связи.',
    noEffects: 'Пока нет советов с достаточной историей для сравнения.', f42: 'Отдельно: пересчёт F42',
    f42Changed: 'Изменено позиций: {count}', f42Delta: 'Изменение необязательных расходов по расчёту F42',
    noData: 'Пока расчёта нет. Запустите его, когда будете готовы.', reasonMissing: 'Часть сумм в чеках неизвестна; итог не рассчитываем.',
    reasonHistory: 'Для этого показателя пока недостаточно истории.',
  },
  en: {
    title: 'Personal purchase-habit analytics', watermark: 'Input version', calculate: 'Calculate analytics',
    recalculate: 'Recalculate', retry: 'Retry calculation', loading: 'Loading analytics…',
    pending: 'Calculation queued', processing: 'Analyzing purchase history…',
    failed: 'Calculation failed', stale: 'Data changed — calculate again',
    complete: 'Calculation ready', partial: 'Partial report: some receipt amounts are unknown.',
    error: 'Could not load analytics.', ceiling: 'Theoretical monthly ceiling',
    ceilingNote: 'Repeated optional purchases over 90 days, scaled to 30 days. This is not money actually saved.',
    shareIncome: 'of planned income', shareLimit: 'of personal limit', repeatCount: 'purchases in 90 days',
    spend90: '90-day spend', monthly: '30-day estimate', unavailableCeiling: 'There is not enough data to estimate a ceiling.',
    trend: 'Optional-spend share by week', down: 'Share decreased', up: 'Share increased', steady: 'No clear change',
    spend: 'All spend', optional: 'Optional', share: 'Share', recalculated: 'Affected by F42 recalculation',
    unavailableTrend: 'At least two weeks of confirmed purchases are needed for comparison.',
    trendPartial: 'Trend is unavailable because some receipt amounts are unknown.',
    effectTitle: 'Observations after advice', afterAdvice: 'After advice', beforeAdvice: 'Before advice',
    noFurtherPurchases: 'No purchases appeared in the observation window.', pendingEffect: 'Too early to compare',
    daysLeft: 'days until the observation window is long enough', effectDisclaimer: 'This is a frequency change after advice, not evidence of cause.',
    noEffects: 'There are no advice observations with enough purchase history yet.', f42: 'Separate: F42 recalculation',
    f42Changed: 'Changed positions: {count}', f42Delta: 'F42 optional-spend estimate change',
    noData: 'No calculation yet. Start one when you are ready.', reasonMissing: 'Some receipt amounts are unknown; totals are not calculated.',
    reasonHistory: 'There is not enough history for this measure yet.',
  },
} as const;

function money(amount: string | null, language: Language): string {
  if (amount === null) return '—';
  return new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(amount));
}

function percent(amount: string, language: Language): string {
  return `${new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', { maximumFractionDigits: 1 }).format(Number(amount) * 100)}%`;
}

function percentValue(amount: string, language: Language): string {
  return `${new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', { maximumFractionDigits: 1 }).format(Number(amount))}%`;
}

function reasonText(reason: string, t: typeof copy[Language], kind: 'savings' | 'trend'): string {
  if (reason === 'missing_amounts') return kind === 'trend' ? t.trendPartial : t.reasonMissing;
  return kind === 'trend' ? t.unavailableTrend : t.reasonHistory;
}

export function AdviceAnalyticsPanel({ tenantId, language, canWrite = true }: {
  tenantId: string; language: Language; canWrite?: boolean;
}) {
  const t = copy[language];
  const queryClient = useQueryClient();
  const queryKey = ['advice-analytics', tenantId];
  const jobQuery = useQuery({
    queryKey,
    queryFn: () => api.getAdviceAnalytics(tenantId),
    retry: false,
    refetchInterval: (query) => ['pending', 'processing'].includes(query.state.data?.state ?? '') ? 1500 : false,
  });
  const requestMutation = useMutation({
    mutationFn: () => api.requestAdviceAnalytics(tenantId),
    onSuccess: (job) => { queryClient.setQueryData(queryKey, job); },
  });
  const job = jobQuery.data;
  const report = job?.state === 'ready' && job.report?.inputWatermark === job.inputWatermark ? job.report : null;
  const label = job?.state === 'pending' ? t.pending
    : job?.state === 'processing' ? t.processing
      : job?.state === 'failed' ? t.failed
        : job?.state === 'stale' ? t.stale
          : jobQuery.isPending ? t.loading
            : jobQuery.isError ? t.error
              : job?.state === 'ready' ? t.complete : t.noData;
  const canRequest = job?.state !== 'pending' && job?.state !== 'processing';
  const actionText = job?.state === 'failed' ? t.retry
    : job?.state === 'stale' || job?.state === 'ready' ? t.recalculate : t.calculate;

  return <section className="advice-analytics" aria-label={t.title}>
    <div className="advice-analytics-heading">
      <div><span className="eyebrow">F43 · {t.watermark}: {job?.inputWatermark ?? '—'}</span><h3>{t.title}</h3></div>
      {canWrite && <button className="button button-primary" disabled={!canRequest || requestMutation.isPending}
        onClick={() => requestMutation.mutate()}>{requestMutation.isPending ? t.loading : actionText}</button>}
    </div>
    {jobQuery.isPending ? <p className="empty-state" role="status">{t.loading}</p>
      : jobQuery.isError ? <p className="advice-analytics-status" role="alert">{jobQuery.error.message || t.error}</p>
        : <p className={`advice-analytics-status state-${job?.state ?? 'idle'}`} role="status" aria-live="polite">{label}</p>}
    {requestMutation.isError && <p className="advice-analytics-status" role="alert">{requestMutation.error.message || t.error}</p>}
    {report && <>
      {report.completeness === 'partial' && <p className="advice-analytics-notice">{t.partial}</p>}
      <div className="advice-analytics-grid">
        <article className="advice-analytics-card">
          <h4>{t.ceiling}</h4>
          {report.savings.available && report.savings.monthlyCeiling !== null ? <>
            <strong className="advice-analytics-value">{money(report.savings.monthlyCeiling, language)}</strong>
            <p>{t.ceilingNote}</p>
            {report.savings.shareOfIncome !== null && <p>{percentValue(report.savings.shareOfIncome, language)} {t.shareIncome}</p>}
            {report.savings.shareOfLimit !== null && <p>{percentValue(report.savings.shareOfLimit, language)} {t.shareLimit}</p>}
            <ul>{report.savings.groups.map((group) => <li key={group.productKey}>
              <span>{group.name} · {group.count} {t.repeatCount}</span><strong>{money(group.monthlyCeiling, language)}</strong>
            </li>)}</ul>
          </> : <>
            <p>{reasonText(report.savings.reasonCode, t, 'savings')}</p>
            <p>{t.unavailableCeiling}</p>
          </>}
        </article>
        <article className="advice-analytics-card">
          <h4>{t.trend}</h4>
          {report.trend.available ? <>
            <p className="advice-analytics-direction">{report.trend.direction === 'down' ? t.down
              : report.trend.direction === 'up' ? t.up : t.steady}
              {report.trend.delta !== null && ` · ${percent(report.trend.delta, language)}`}</p>
            <ul>{report.trend.weeks.map((week) => <li key={week.start}>
              <span>{week.start} — {week.end}{week.recalculated && <small>{t.recalculated}</small>}</span>
              <strong>{percent(week.optionalShare, language)}</strong>
            </li>)}</ul>
          </> : <p>{reasonText(report.trend.reasonCode, t, 'trend')}</p>}
        </article>
        <article className="advice-analytics-card">
          <h4>{t.effectTitle}</h4>
          {report.effects.effects.length || report.effects.pending.length ? <>
            <ul>{report.effects.effects.map((effect) => <li key={effect.productKey}>
              <span><strong>{effect.name}</strong><small>{t.afterAdvice}: {effect.afterCount} · {t.beforeAdvice}: {effect.beforeCount}
                {effect.afterCount === 0 && ` · ${t.noFurtherPurchases}`}</small>
                <small>{effect.advice}</small></span><strong>{percentValue(effect.change, language)}</strong>
            </li>)}</ul>
            {report.effects.pending.map((effect) => <p key={effect.productKey}>
              {effect.name}: {t.pendingEffect} ({effect.daysLeft} {t.daysLeft}).
            </p>)}
            <p className="advice-analytics-disclaimer">{t.effectDisclaimer}</p>
          </> : <p>{t.noEffects}</p>}
        </article>
        {report.recalculation.available && <article className="advice-analytics-card advice-analytics-f42">
          <h4>{t.f42}</h4>
          <p>{t.f42Changed.replace('{count}', String(report.recalculation.changedItemCount))}</p>
          <p>{t.f42Delta}: {money(report.recalculation.optionalSpendDelta, language)}</p>
        </article>}
      </div>
    </>}
  </section>;
}

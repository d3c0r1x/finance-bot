import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type ReceiptRecalculationPreview } from './api';

type Language = 'ru' | 'en';

const labels = {
  ru: {
    title: 'Старые разборы чеков', preview: 'Проверить старые разборы', apply: 'Применить пересчёт',
    loading: 'Подождите…', checked: 'Проверено позиций', updates: 'Подготовлено к обновлению',
    changed: 'Изменено позиций', before: 'Старое', after: 'Новое', amount: 'Сумма позиции',
    unchangedTotals: 'Суммы чеков и операций не меняются.', previewed: 'Предварительный просмотр. Изменения ещё не сохранены.',
    applied: 'Пересчёт применён.', noChanges: 'Нет позиций для обновления.',
    impact: 'Необязательные покупки', delta: 'Изменение', noReviewed: 'Дельта не рассчитана: нет проверенных позиций.',
    missingAmounts: 'Дельта не рассчитана: в чеках не хватает сумм.', unavailable: 'Дельта не рассчитана: аналитический сервис недоступен.',
    history: 'История пересчётов', showChanges: 'Показать сохранённые изменения', older: 'Предыдущие запуски',
    historyLoading: 'Загружаем историю…', noRuns: 'Запусков пока нет', runDate: 'Запуск',
  },
  en: {
    title: 'Older receipt reviews', preview: 'Preview old reviews', apply: 'Apply recalculation',
    loading: 'Please wait…', checked: 'Items checked', updates: 'Updates prepared',
    changed: 'Items changed', before: 'Before', after: 'After', amount: 'Line amount',
    unchangedTotals: 'Receipt and transaction totals stay unchanged.', previewed: 'Preview only. Changes are not saved yet.',
    applied: 'Recalculation applied.', noChanges: 'No items need an update.',
    impact: 'Optional purchases', delta: 'Change', noReviewed: 'No delta: there are no reviewed items.',
    missingAmounts: 'No delta: some receipt items have no amounts.', unavailable: 'No delta: analytics service is unavailable.',
    history: 'Recalculation history', showChanges: 'Show saved changes', older: 'Older runs',
    historyLoading: 'Loading history…', noRuns: 'No runs yet', runDate: 'Run',
  },
} as const;

export function ReceiptRecalculationPanel({ tenantId, language, canWrite }: {
  tenantId: string; language: Language; canWrite: boolean;
}) {
  const t = labels[language];
  const queryClient = useQueryClient();
  const [preview, setPreview] = useState<ReceiptRecalculationPreview | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const history = useInfiniteQuery({
    queryKey: ['receipt-recalculation-history', tenantId],
    queryFn: ({ pageParam }) => api.getReceiptRecalculationHistory(tenantId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: showHistory,
  });
  const runDetail = useInfiniteQuery({
    queryKey: ['receipt-recalculation-run', tenantId, selectedRunId],
    queryFn: ({ pageParam }) => api.getReceiptRecalculationRun(tenantId, selectedRunId!, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: showHistory && selectedRunId !== null,
  });
  const previewRequest = useMutation({
    mutationFn: () => api.previewReceiptRecalculation(tenantId),
    onSuccess: async (result) => {
      setPreview(result);
      await queryClient.invalidateQueries({ queryKey: ['receipt-recalculation-history', tenantId] });
    },
  });
  const applyRequest = useMutation({
    mutationFn: () => api.applyReceiptRecalculation(tenantId, preview!.runId),
    onSuccess: async (result) => {
      setPreview(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['report', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['summary', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['receipt-recalculation-history', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['receipt-recalculation-run', tenantId] }),
      ]);
      setAppliedCount(result.changedCount);
    },
    onError: () => setPreview(null),
  });
  const [appliedCount, setAppliedCount] = useState<number | null>(null);

  if (!canWrite) return null;

  const error = previewRequest.error ?? applyRequest.error;
  return <section className="panel receipt-recalculation" aria-label={t.title}>
    <h2>{t.title}</h2>
    <p>{t.unchangedTotals}</p>
    <button className="button button-quiet" type="button" disabled={previewRequest.isPending || applyRequest.isPending}
      onClick={() => { setPreview(null); applyRequest.reset(); setAppliedCount(null); previewRequest.mutate(); }}>
      {previewRequest.isPending ? t.loading : t.preview}
    </button>
    <button className="button button-quiet" type="button" onClick={() => setShowHistory((shown) => !shown)}>
      {t.history}
    </button>
    {error && <p className="form-error" role="alert">{error.message}</p>}
    {appliedCount !== null && <p role="status">{t.applied} {t.changed}: {appliedCount}</p>}
    {showHistory && <div aria-live="polite">
      {history.isPending && <p>{t.historyLoading}</p>}
      {history.error && <p className="form-error" role="alert">{history.error.message}</p>}
      {history.data && history.data.pages.flatMap((page) => page.runs).length === 0 && <p>{t.noRuns}</p>}
      <ul>{history.data?.pages.flatMap((page) => page.runs).map((run) => <li key={run.runId}>
        <strong>{t.runDate}: {new Date(run.createdAt).toLocaleString(language === 'ru' ? 'ru-RU' : 'en-US')}</strong>
        {' · '}{run.state} · {t.checked}: {run.checked} · {t.updates}: {run.updateCount} · {t.changed}: {run.changedCount}
        {run.impact?.optionalSpendDelta !== null && run.impact?.optionalSpendDelta !== undefined
          && <div>{t.delta}: {run.impact.optionalSpendDelta}{run.impact.currency ? ` ${run.impact.currency}` : ''}</div>}
        <button className="button button-quiet" type="button" onClick={() => setSelectedRunId(run.runId)}>
          {t.showChanges}
        </button>
      </li>)}</ul>
      {history.hasNextPage && <button className="button button-quiet" type="button" disabled={history.isFetchingNextPage}
        onClick={() => history.fetchNextPage()}>{history.isFetchingNextPage ? t.historyLoading : t.older}</button>}
      {runDetail.error && <p className="form-error" role="alert">{runDetail.error.message}</p>}
      {runDetail.data && <div aria-label={`${t.history}: ${selectedRunId}`}>
        <p>{runDetail.data.pages[0].run.state} · {t.changed}: {runDetail.data.pages[0].run.changedCount}</p>
        <ul>{runDetail.data.pages.flatMap((page) => page.changes).map((change) => <li key={change.itemId}>
          <strong>{change.name}</strong> · {t.amount}: {change.lineSum ?? '—'}
          <div>{t.before}: {change.beforeVerdict ?? '—'} · {change.beforeReason ?? '—'}</div>
          <div>{t.after}: {change.afterVerdict} · {change.afterReason ?? '—'}</div>
        </li>)}</ul>
        {runDetail.hasNextPage && <button className="button button-quiet" type="button"
          disabled={runDetail.isFetchingNextPage} onClick={() => runDetail.fetchNextPage()}>
          {runDetail.isFetchingNextPage ? t.historyLoading : t.older}
        </button>}
      </div>}
    </div>}
    {preview && <div aria-live="polite">
      <p>{t.previewed}</p>
      <p>{t.checked}: {preview.checked} · {t.updates}: {preview.updateCount} · {t.changed}: {preview.changedCount}</p>
      {preview.impact.reasonCode === 'available'
        && preview.impact.optionalSpendBefore !== null && preview.impact.optionalSpendAfter !== null
        && preview.impact.optionalSpendDelta !== null
        ? <div aria-label={t.impact}>
          <p>{t.impact}: {preview.impact.optionalSpendBefore}{preview.impact.currency ? ` ${preview.impact.currency}` : ''} → {preview.impact.optionalSpendAfter}{preview.impact.currency ? ` ${preview.impact.currency}` : ''}</p>
          <p>{t.delta}: {preview.impact.optionalSpendDelta}{preview.impact.currency ? ` ${preview.impact.currency}` : ''}</p>
        </div>
        : <p role="status">{preview.impact.reasonCode === 'missing_amounts' ? t.missingAmounts
          : preview.impact.reasonCode === 'no_reviewed_items' ? t.noReviewed : t.unavailable}</p>}
      {preview.changes.length === 0 && <p>{t.noChanges}</p>}
      <ul>{preview.changes.map((change) => <li key={change.itemId}>
        <strong>{change.name}</strong> · {t.amount}: {change.lineSum ?? '—'}
        <div>{t.before}: {change.beforeVerdict ?? '—'} · {change.beforeReason ?? '—'}</div>
        <div>{t.after}: {change.afterVerdict} · {change.afterReason ?? '—'}</div>
      </li>)}</ul>
      {preview.updateCount > 0 && <button className="button button-primary" type="button"
        disabled={applyRequest.isPending} onClick={() => applyRequest.mutate()}>
        {applyRequest.isPending ? t.loading : t.apply}
      </button>}
    </div>}
  </section>;
}

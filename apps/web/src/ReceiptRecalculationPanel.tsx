import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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
  },
  en: {
    title: 'Older receipt reviews', preview: 'Preview old reviews', apply: 'Apply recalculation',
    loading: 'Please wait…', checked: 'Items checked', updates: 'Updates prepared',
    changed: 'Items changed', before: 'Before', after: 'After', amount: 'Line amount',
    unchangedTotals: 'Receipt and transaction totals stay unchanged.', previewed: 'Preview only. Changes are not saved yet.',
    applied: 'Recalculation applied.', noChanges: 'No items need an update.',
    impact: 'Optional purchases', delta: 'Change', noReviewed: 'No delta: there are no reviewed items.',
    missingAmounts: 'No delta: some receipt items have no amounts.', unavailable: 'No delta: analytics service is unavailable.',
  },
} as const;

export function ReceiptRecalculationPanel({ tenantId, language, canWrite }: {
  tenantId: string; language: Language; canWrite: boolean;
}) {
  const t = labels[language];
  const queryClient = useQueryClient();
  const [preview, setPreview] = useState<ReceiptRecalculationPreview | null>(null);
  const previewRequest = useMutation({
    mutationFn: () => api.previewReceiptRecalculation(tenantId),
    onSuccess: setPreview,
  });
  const applyRequest = useMutation({
    mutationFn: () => api.applyReceiptRecalculation(tenantId, preview!.runId),
    onSuccess: async (result) => {
      setPreview(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['report', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['summary', tenantId] }),
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
    {error && <p className="form-error" role="alert">{error.message}</p>}
    {appliedCount !== null && <p role="status">{t.applied} {t.changed}: {appliedCount}</p>}
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

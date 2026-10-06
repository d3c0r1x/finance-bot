import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, type ShoppingCandidate, type ShoppingList } from './api';

const copy = {
  ru: {
    title: 'Пора купить', loading: 'Загрузка…', error: 'Список покупок временно недоступен.', retry: 'Повторить',
    empty: 'Пока нечего добавить: нужны минимум три покупки и интервалы от трёх дней.',
    disclosure: 'Это подсказка по ритму чеков, не учёт запасов: мы не знаем, что уже есть дома.',
    total: 'Оценка списка', due: 'Пора', overdue: (days: number) => `Просрочено на ${days} дн.`,
    inDays: (days: number) => `Через ${days} дн.`, purchases: (count: number) => `${count} покупки`,
    interval: (days: number) => `медиана: раз в ${days} дн.`, last: 'Последняя покупка',
    bought: 'Уже купил', mute: 'Скрыть', unmute: 'Вернуть подсказку', copy: 'Скопировать список', copied: 'Список скопирован',
    copyError: 'Не удалось скопировать список.', noActive: 'Активных подсказок пока нет.',
    boughtTitle: 'Уже куплено', boughtReason: 'Отметка действует до следующего обычного интервала покупки.',
    mutedTitle: 'Скрытые подсказки', mutedReason: 'Вы скрыли эту подсказку.', blockedTitle: 'Не брать',
    blockedReason: 'Вы отметили этот товар «не брать».', ruleBlockedReason: 'Отмечено правилами проверки чеков.',
    actionError: 'Не удалось сохранить решение. Повторите попытку.',
  },
  en: {
    title: 'Shopping list', loading: 'Loading…', error: 'Shopping suggestions are temporarily unavailable.', retry: 'Retry',
    empty: 'Nothing to suggest yet: at least three purchases with gaps of three days or more are needed.',
    disclosure: 'These are receipt-rhythm suggestions, not home inventory; we do not know what you already have.',
    total: 'Estimated list cost', due: 'Due now', overdue: (days: number) => `${days} days overdue`,
    inDays: (days: number) => `In ${days} days`, purchases: (count: number) => `${count} purchases`,
    interval: (days: number) => `median interval: every ${days} days`, last: 'Last purchased',
    bought: 'Already bought', mute: 'Hide', unmute: 'Restore suggestion', copy: 'Copy list', copied: 'List copied',
    copyError: 'Could not copy the list.', noActive: 'No active suggestions right now.',
    boughtTitle: 'Already bought', boughtReason: 'This mark expires after the next usual purchase interval.',
    mutedTitle: 'Hidden suggestions', mutedReason: 'You hid this suggestion.', blockedTitle: 'Do not buy',
    blockedReason: 'You marked this product as do not buy.', ruleBlockedReason: 'Flagged by receipt review rules.',
    actionError: 'Could not save this decision. Try again.',
  },
} as const;

export function ShoppingPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = copy[language];
  const queryClient = useQueryClient();
  const queryKey = ['shopping-candidates', tenantId];
  const [copyStatus, setCopyStatus] = useState('');
  const shopping = useQuery({
    queryKey,
    queryFn: () => api.getShoppingCandidates(tenantId),
    retry: false,
  });
  const decision = useMutation({
    mutationFn: ({ productKey, action }: { productKey: string; action: 'bought' | 'mute' | 'unmute' }) => {
      if (action === 'bought') return api.markShoppingBought(tenantId, productKey);
      return action === 'mute' ? api.muteShoppingSuggestion(tenantId, productKey)
        : api.unmuteShoppingSuggestion(tenantId, productKey);
    },
    onSuccess: (next) => queryClient.setQueryData<ShoppingList>(queryKey, next),
  });
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const money = (amount: string) => new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(amount));
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(value));
  const copyList = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      const active = shopping.data?.candidates ?? [];
      const text = [...active.map((candidate) => `${candidate.productName} — ${money(candidate.estimatedCost)}`),
        `${t.total}: ${money(shopping.data?.estimatedListCost ?? '0.00')}`].join('\n');
      await navigator.clipboard.writeText(text);
      setCopyStatus(t.copied);
    } catch {
      setCopyStatus(t.copyError);
    }
  };

  return <section className="panel shopping-panel" aria-labelledby="shopping-title" aria-busy={shopping.isPending}>
    <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2 id="shopping-title">{t.title}</h2></div>
      <button className="button" disabled={!shopping.data?.candidates.length} onClick={() => void copyList()}>{t.copy}</button></div>
    {shopping.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : shopping.isError ? <div className="empty-state" role="alert"><p>{t.error}</p>
        <button className="button button-primary" onClick={() => void shopping.refetch()}>{t.retry}</button></div>
        : <>
          {shopping.data.candidates.length === 0 ? <p className="empty-state">{shopping.data.boughtCandidates.length
            || shopping.data.mutedCandidates.length || shopping.data.blockedCandidates.length ? t.noActive : t.empty}</p>
            : <div className="shopping-list">
              {shopping.data.candidates.map((candidate) => <ShoppingCard key={`${candidate.productName}:${candidate.lastPurchasedAt}`}
                candidate={candidate} language={language} money={money} date={date} disabled={decision.isPending}
                onBought={() => decision.mutate({ productKey: candidate.productKey, action: 'bought' })}
                onMute={() => decision.mutate({ productKey: candidate.productKey, action: 'mute' })} />)}
            </div>}
          {shopping.data.boughtCandidates.length > 0 && <section className="shopping-hidden" aria-labelledby="shopping-bought-title">
            <h3 id="shopping-bought-title">{t.boughtTitle}</h3><p>{t.boughtReason}</p>
            {shopping.data.boughtCandidates.map((candidate) => <p key={candidate.productKey}>{candidate.productName}</p>)}
          </section>}
          {shopping.data.mutedCandidates.length > 0 && <section className="shopping-hidden" aria-labelledby="shopping-muted-title">
            <h3 id="shopping-muted-title">{t.mutedTitle}</h3>
            {shopping.data.mutedCandidates.map((candidate) => <article className="shopping-hidden-row" key={candidate.productKey}>
              <span>{candidate.productName} · {t.mutedReason}</span>
              <button className="button" disabled={decision.isPending}
                onClick={() => decision.mutate({ productKey: candidate.productKey, action: 'unmute' })}>{t.unmute}</button>
            </article>)}
          </section>}
          {shopping.data.blockedCandidates.length > 0 && <section className="shopping-hidden" aria-labelledby="shopping-blocked-title">
            <h3 id="shopping-blocked-title">{t.blockedTitle}</h3>
            {shopping.data.blockedCandidates.map((candidate) => <p key={candidate.productKey}>{candidate.productName} ·
              {' '}{candidate.reasonCode === 'rule_backed_not_to_buy' ? t.ruleBlockedReason : t.blockedReason}</p>)}
          </section>}
          {decision.isError && <p role="alert">{t.actionError}</p>}
          <p aria-live="polite" className="shopping-copy-status">{copyStatus}</p>
          <p className="shopping-total">{t.total}: <strong>{money(shopping.data.estimatedListCost)}</strong></p>
          <p className="shopping-disclosure">{t.disclosure}</p>
        </>}
  </section>;
}

function ShoppingCard({ candidate, language, money, date, disabled, onBought, onMute }: {
  candidate: ShoppingCandidate; language: 'ru' | 'en'; money: (amount: string) => string; date: (value: string) => string;
  disabled: boolean; onBought: () => void; onMute: () => void;
}) {
  const t = copy[language];
  const due = candidate.daysUntilDue < 0 ? t.overdue(Math.abs(candidate.daysUntilDue))
    : candidate.daysUntilDue === 0 ? t.due : t.inDays(candidate.daysUntilDue);
  return <article className="shopping-card">
    <div className="shopping-card-copy">
      <h3>{candidate.productName}</h3>
      <p>{due} · {t.purchases(candidate.purchaseCount)} · {t.interval(candidate.medianIntervalDays)}</p>
      <p>{t.last}: {date(candidate.lastPurchasedAt)}</p>
    </div>
    <div className="shopping-card-actions"><strong className="shopping-card-cost">{money(candidate.estimatedCost)}</strong>
      <button className="button" disabled={disabled} onClick={onBought}>{t.bought}</button>
      <button className="button" disabled={disabled} onClick={onMute}>{t.mute}</button></div>
  </article>;
}

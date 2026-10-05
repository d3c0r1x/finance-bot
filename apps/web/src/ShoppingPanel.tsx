import { useQuery } from '@tanstack/react-query';
import { api, type ShoppingCandidate } from './api';

const copy = {
  ru: {
    title: 'Пора купить', loading: 'Загрузка…', error: 'Список покупок временно недоступен.', retry: 'Повторить',
    empty: 'Пока нечего добавить: нужны минимум три покупки и интервалы от трёх дней.',
    disclosure: 'Это подсказка по ритму чеков, не учёт запасов: мы не знаем, что уже есть дома.',
    total: 'Оценка списка', due: 'Пора', overdue: (days: number) => `Просрочено на ${days} дн.`,
    inDays: (days: number) => `Через ${days} дн.`, purchases: (count: number) => `${count} покупки`,
    interval: (days: number) => `медиана: раз в ${days} дн.`, last: 'Последняя покупка',
  },
  en: {
    title: 'Shopping list', loading: 'Loading…', error: 'Shopping suggestions are temporarily unavailable.', retry: 'Retry',
    empty: 'Nothing to suggest yet: at least three purchases with gaps of three days or more are needed.',
    disclosure: 'These are receipt-rhythm suggestions, not home inventory; we do not know what you already have.',
    total: 'Estimated list cost', due: 'Due now', overdue: (days: number) => `${days} days overdue`,
    inDays: (days: number) => `In ${days} days`, purchases: (count: number) => `${count} purchases`,
    interval: (days: number) => `median interval: every ${days} days`, last: 'Last purchased',
  },
} as const;

export function ShoppingPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = copy[language];
  const shopping = useQuery({
    queryKey: ['shopping-candidates', tenantId],
    queryFn: () => api.getShoppingCandidates(tenantId),
    retry: false,
  });
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const money = (amount: string) => new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(amount));
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(value));

  return <section className="panel shopping-panel" aria-labelledby="shopping-title" aria-busy={shopping.isPending}>
    <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2 id="shopping-title">{t.title}</h2></div></div>
    {shopping.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : shopping.isError ? <div className="empty-state" role="alert"><p>{t.error}</p>
        <button className="button button-primary" onClick={() => void shopping.refetch()}>{t.retry}</button></div>
        : <>
          {shopping.data.candidates.length === 0 ? <p className="empty-state">{t.empty}</p>
            : <div className="shopping-list">
              {shopping.data.candidates.map((candidate) => <ShoppingCard key={`${candidate.productName}:${candidate.lastPurchasedAt}`}
                candidate={candidate} language={language} money={money} date={date} />)}
            </div>}
          <p className="shopping-total">{t.total}: <strong>{money(shopping.data.estimatedListCost)}</strong></p>
          <p className="shopping-disclosure">{t.disclosure}</p>
        </>}
  </section>;
}

function ShoppingCard({ candidate, language, money, date }: {
  candidate: ShoppingCandidate; language: 'ru' | 'en'; money: (amount: string) => string; date: (value: string) => string;
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
    <strong className="shopping-card-cost">{money(candidate.estimatedCost)}</strong>
  </article>;
}

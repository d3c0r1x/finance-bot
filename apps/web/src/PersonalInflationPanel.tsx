import { useQuery } from '@tanstack/react-query';
import { api, type PersonalInflationItem } from './api';

const copy = {
  ru: {
    title: 'Личная динамика цен', period: 'Последние 90 дней', loading: 'Загрузка…',
    error: 'Динамика цен временно недоступна.', disclosure: 'Расчёт использует только цены из ваших чеков; это не официальная статистика инфляции.',
    insufficient: 'Недостаточно истории для расчёта.',
    historyNeeded: 'Нужно минимум 3 товара: для каждого — 2 покупки до окна и 1 покупка за последние 90 дней.',
    before: 'Корзина по старым ценам', now: 'Та же корзина по новым ценам', index: 'Личный индекс',
    rising: 'Сильнее подорожали', falling: 'Сильнее подешевели', none: 'Нет заметных изменений.',
    old: 'старая цена', current: 'новая цена', weight: 'вес', purchases: 'покупок',
  },
  en: {
    title: 'Personal price trend', period: 'Last 90 days', loading: 'Loading…',
    error: 'Price trend is temporarily unavailable.',
    disclosure: 'This calculation uses prices from your receipts only; it is not official inflation statistics.',
    insufficient: 'There is not enough purchase history.',
    historyNeeded: 'At least 3 products are needed: each must have 2 purchases before the window and 1 within the last 90 days.',
    before: 'Basket at earlier prices', now: 'Same basket at recent prices', index: 'Personal index',
    rising: 'Largest increases', falling: 'Largest decreases', none: 'No notable changes.',
    old: 'earlier price', current: 'recent price', weight: 'weight', purchases: 'purchases',
  },
} as const;

export function PersonalInflationPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = copy[language];
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const inflation = useQuery({
    queryKey: ['personal-inflation', tenantId],
    queryFn: () => api.getPersonalInflation(tenantId),
    retry: false,
  });
  const money = (amount: string) => new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(amount));
  const percent = (amount: string) => {
    const value = Number(amount);
    return `${value > 0 ? '+' : ''}${new Intl.NumberFormat(locale, {
      minimumFractionDigits: 2, maximumFractionDigits: 2,
    }).format(value)}%`;
  };

  return <section className="panel personal-inflation" aria-labelledby="personal-inflation-title"
    aria-busy={inflation.isPending}>
    <div className="panel-heading"><div><span className="eyebrow">{t.period}</span>
      <h2 id="personal-inflation-title">{t.title}</h2></div></div>
    <p className="inflation-disclosure">{t.disclosure}</p>
    {inflation.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : inflation.isError ? <p className="empty-state" role="alert">{t.error}</p>
        : !inflation.data.available ? <div className="empty-state">
          <p>{t.insufficient}</p><p>{t.historyNeeded}</p>
        </div> : <>
          <dl className="inflation-summary">
            <div><dt>{t.before}</dt><dd>{money(inflation.data.basketBefore!)}</dd></div>
            <div><dt>{t.now}</dt><dd>{money(inflation.data.basketNow!)}</dd></div>
            <div><dt>{t.index}</dt><dd>{percent(inflation.data.indexPercent!)}</dd></div>
          </dl>
          <div className="inflation-comparisons">
            <InflationGroup title={t.rising} items={inflation.data.rising}
              money={money} percent={percent} labels={t} empty={t.none} />
            <InflationGroup title={t.falling} items={inflation.data.falling}
              money={money} percent={percent} labels={t} empty={t.none} />
          </div>
        </>}
  </section>;
}

function InflationGroup({ title, items, money, percent, labels, empty }: {
  title: string; items: PersonalInflationItem[]; money: (amount: string) => string;
  percent: (amount: string) => string; labels: typeof copy['ru'] | typeof copy['en']; empty: string;
}) {
  return <section className="inflation-group" aria-label={title}>
    <h3>{title}</h3>
    {!items.length ? <p>{empty}</p> : <div className="inflation-product-list">
      {items.map((item) => <article className="inflation-product" key={item.productName}>
        <div><h4>{item.productName}</h4><p>{labels.old}: {money(item.oldUnitPrice)} · {labels.current}: {money(item.newUnitPrice)}</p>
          <p>{labels.weight}: {money(item.oldSpendWeight)} · {item.olderPurchaseCount + item.windowPurchaseCount} {labels.purchases}</p></div>
        <strong>{percent(item.changePercent)}</strong>
      </article>)}
    </div>}
  </section>;
}

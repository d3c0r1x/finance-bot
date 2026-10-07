import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type ProductCatalogCard } from './api';
import { formatMoney } from './formatting';

const text = {
  ru: {
    title: 'Товары', search: 'Поиск товаров', find: 'Найти', loading: 'Загрузка…', error: 'История цен временно недоступна.',
    catalogEmpty: 'Каталог появится после трёх подтверждённых покупок товара.', searchEmpty: 'Совпадений нет.',
    usual: 'Обычная цена', last: 'Последняя покупка', cheapest: 'Самая низкая цена', spent: 'Потрачено',
    purchases: 'покупок', median: 'До покупки', onePurchase: 'Пока одна покупка: показываем её цену без выдуманной истории.',
    history: 'История цены', storeUnknown: 'магазин не указан',
  },
  en: {
    title: 'Products', search: 'Search products', find: 'Search', loading: 'Loading…', error: 'Price history is temporarily unavailable.',
    catalogEmpty: 'Catalog appears after three confirmed purchases of a product.', searchEmpty: 'No matches.',
    usual: 'Usual price', last: 'Last purchase', cheapest: 'Lowest price', spent: 'Spent',
    purchases: 'purchases', median: 'Before latest purchase', onePurchase: 'One purchase so far: showing its price without invented history.',
    history: 'Price history', storeUnknown: 'store not listed',
  },
} as const;

export function ProductCatalogPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = text[language];
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState('');
  const products = useQuery({
    queryKey: ['product-catalog', tenantId, query],
    queryFn: () => api.getProductCatalog(tenantId, query),
    retry: false,
  });

  return <section className="panel product-catalog" aria-labelledby="product-catalog-title">
    <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2 id="product-catalog-title">{t.title}</h2></div></div>
    <form className="product-search" onSubmit={(event) => { event.preventDefault(); setQuery(draft.trim()); }}>
      <label htmlFor="product-search">{t.search}</label>
      <div><input id="product-search" value={draft} maxLength={80} onChange={(event) => setDraft(event.target.value)} />
        <button className="button button-primary" type="submit">{t.find}</button></div>
    </form>
    {products.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : products.isError ? <p className="empty-state" role="alert">{t.error}</p>
        : products.data.products.length === 0 ? <p className="empty-state">{query ? t.searchEmpty : t.catalogEmpty}</p>
          : <div className="product-grid">{products.data.products.map((product) =>
            <ProductCardView key={`${product.productName}:${product.lastPurchasedAt}`} product={product} language={language} />)}</div>}
  </section>;
}

function ProductCardView({ product, language }: { product: ProductCatalogCard; language: 'ru' | 'en' }) {
  const t = text[language];
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const money = (amount: string) => formatMoney(amount, 'RUB', language);
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(value));
  const values = product.history.map((point) => Number(point.unitPrice)).filter(Number.isFinite);
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);
  const coordinates = product.history.map((point, index) => ({
    x: product.history.length === 1 ? 200 : 8 + 384 * index / (product.history.length - 1),
    y: 104 - (Number(point.unitPrice) - minimum) / (maximum - minimum || 1) * 88,
  }));
  const chartName = `${t.history}: ${product.productName}`;

  return <article className="product-card">
    <div className="product-card-title"><h3>{product.productName}</h3><span>{product.purchaseCount} {t.purchases}</span></div>
    <dl className="product-metrics">
      <div><dt>{t.usual}</dt><dd>{money(product.usualUnitPrice)}</dd></div>
      <div><dt>{t.last}</dt><dd>{money(product.lastUnitPrice)} · {date(product.lastPurchasedAt)}</dd></div>
      <div><dt>{t.cheapest}</dt><dd>{money(product.cheapestUnitPrice)} · {product.cheapestMerchant ?? t.storeUnknown}</dd></div>
      <div><dt>{t.spent}</dt><dd>{money(product.totalSpent)}</dd></div>
      {product.hasBaseline && product.baselineUnitPrice &&
        <div><dt>{t.median}</dt><dd>{money(product.baselineUnitPrice)}</dd></div>}
    </dl>
    {!product.hasBaseline && <p className="product-note">{t.onePurchase}</p>}
    {product.chartAvailable && product.history.length >= 2 && <svg className="product-price-chart" role="img" aria-label={chartName}
      viewBox="0 0 400 120" preserveAspectRatio="none">
      <polyline points={coordinates.map((point) => `${point.x},${point.y}`).join(' ')} />
      {coordinates.map((point, index) => <circle key={`${product.history[index].receiptId}:${product.history[index].itemId}`}
        cx={point.x} cy={point.y} r="4" />)}
    </svg>}
  </article>;
}

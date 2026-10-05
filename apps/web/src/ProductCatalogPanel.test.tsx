import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ProductCatalogPanel } from './ProductCatalogPanel';

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const history = [
  { receiptId: 'r1', itemId: 'i1', purchasedAt: '2026-09-01T10:00:00Z', merchant: 'Market A',
    name: 'Tea Green 500g', unitPrice: '100.000000', current: false },
  { receiptId: 'r2', itemId: 'i2', purchasedAt: '2026-09-10T10:00:00Z', merchant: 'Market B',
    name: 'Tea Green 500g', unitPrice: '120.000000', current: false },
  { receiptId: 'r3', itemId: 'i3', purchasedAt: '2026-09-20T10:00:00Z', merchant: 'Market C',
    name: 'Tea Green 500g', unitPrice: '160.000000', current: false },
];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('loads the three-purchase catalog and shows real price history and cheapest merchant', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === `/bff/tenants/${tenantId}/products`) return json({
      mode: 'catalog', query: '', products: [card('Tea Green 500g', 3)],
    });
    throw new Error(`Unexpected request ${url}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<MemoryRouter><QueryClientProvider client={client}>
    <ProductCatalogPanel tenantId={tenantId} language="ru" />
  </QueryClientProvider></MemoryRouter>);

  expect(await screen.findByRole('heading', { name: 'Товары' })).toBeInTheDocument();
  expect(await screen.findByText('Tea Green 500g')).toBeInTheDocument();
  expect(screen.getByText(/Market A/)).toBeInTheDocument();
  expect(screen.getByRole('img', { name: /История цены: Tea Green 500g/ })).toBeInTheDocument();
  expect(screen.getByText(/Обычная цена/)).toBeInTheDocument();
});

it('searches from one purchase and shows no fabricated baseline or chart', async () => {
  const user = userEvent.setup();
  const requests: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    requests.push(url);
    if (url === `/bff/tenants/${tenantId}/products`) return json({ mode: 'catalog', query: '', products: [] });
    if (url === `/bff/tenants/${tenantId}/products?query=milk`) return json({
      mode: 'search', query: 'milk', products: [card('Milk Domik 930ml', 1)],
    });
    throw new Error(`Unexpected request ${url}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<MemoryRouter><QueryClientProvider client={client}>
    <ProductCatalogPanel tenantId={tenantId} language="ru" />
  </QueryClientProvider></MemoryRouter>);

  await user.type(await screen.findByLabelText('Поиск товаров'), 'milk');
  await user.click(screen.getByRole('button', { name: 'Найти' }));
  expect(await screen.findByText('Milk Domik 930ml')).toBeInTheDocument();
  expect(await screen.findByText(/Пока одна покупка/)).toBeInTheDocument();
  expect(screen.queryByRole('img', { name: /История цены: Milk Domik/ })).not.toBeInTheDocument();
  expect(requests).toContain(`/bff/tenants/${tenantId}/products?query=milk`);
});

function card(productName: string, purchaseCount: number) {
  const realHistory = history.slice(0, purchaseCount);
  return {
    productName, purchaseCount, usualUnitPrice: purchaseCount > 1 ? '110.000000' : '100.000000',
    hasBaseline: purchaseCount > 1, baselineUnitPrice: purchaseCount > 1 ? '100.000000' : null,
    lastUnitPrice: realHistory.at(-1)?.unitPrice ?? '100.000000',
    lastPurchasedAt: realHistory.at(-1)?.purchasedAt ?? '2026-09-01T10:00:00Z',
    lastMerchant: realHistory.at(-1)?.merchant ?? 'Market A',
    cheapestUnitPrice: '100.000000', cheapestMerchant: 'Market A', totalSpent: '380.00',
    change: purchaseCount > 1 ? '20.000000' : null, relative: purchaseCount > 1 ? '0.200000' : null,
    signal: false, direction: null, priorPurchases: purchaseCount > 1 ? purchaseCount - 1 : 0,
    chartAvailable: purchaseCount > 1, history: realHistory,
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

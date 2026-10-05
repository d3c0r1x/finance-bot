import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PersonalInflationPanel } from './PersonalInflationPanel';

const tenantId = '9f529dee-205a-4fed-94c2-7d9c66b19d24';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('loads the authenticated personal basket and labels prices as receipt data', async () => {
  const requests: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    requests.push(url);
    if (url === `/bff/tenants/${tenantId}/analytics/personal-inflation`) {
      return json({
        available: true, reasonCode: 'available', asOf: '2026-10-06T12:00:00Z', windowDays: 90,
        productCount: 4, basketBefore: '2510.00', basketNow: '2334.00', indexPercent: '-7.01',
        rising: [{ productName: 'Coffee', oldUnitPrice: '100.00', newUnitPrice: '110.00',
          oldSpendWeight: '500.00', changePercent: '10.00', olderPurchaseCount: 2, windowPurchaseCount: 1 }],
        falling: [{ productName: 'Milk', oldUnitPrice: '200.00', newUnitPrice: '180.00',
          oldSpendWeight: '700.00', changePercent: '-10.00', olderPurchaseCount: 3, windowPurchaseCount: 2 }],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><PersonalInflationPanel tenantId={tenantId} language="ru" /></QueryClientProvider>);

  expect(await screen.findByRole('heading', { name: 'Личная динамика цен' })).toBeInTheDocument();
  expect(await screen.findByText('Coffee')).toBeInTheDocument();
  expect(screen.getByText('Milk')).toBeInTheDocument();
  expect(screen.getByText(/-7,01%/)).toBeInTheDocument();
  expect(screen.getByText(/официальная статистика/)).toBeInTheDocument();
  expect(requests).toEqual([`/bff/tenants/${tenantId}/analytics/personal-inflation`]);
});

it('shows insufficient history without fabricated basket totals', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({
    available: false, reasonCode: 'insufficient_history', asOf: '2026-10-06T12:00:00Z', windowDays: 90,
    productCount: 0, basketBefore: null, basketNow: null, indexPercent: null, rising: [], falling: [],
  })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><PersonalInflationPanel tenantId={tenantId} language="ru" /></QueryClientProvider>);

  expect(await screen.findByText(/недостаточно истории/i)).toBeInTheDocument();
  expect(screen.getByText(/минимум 3 товара/i)).toBeInTheDocument();
  expect(screen.queryByText(/0,00/)).not.toBeInTheDocument();
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

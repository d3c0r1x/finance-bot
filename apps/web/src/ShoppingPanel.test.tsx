import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ShoppingPanel } from './ShoppingPanel';

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const shopping = {
  candidates: [{ productName: 'Молоко 1 л', purchaseCount: 3, medianIntervalDays: 10,
    usualUnitPrice: '100.000000', estimatedCost: '100.00', lastPurchasedAt: '2026-10-04T00:00:00Z',
    dueAt: '2026-10-05T00:00:00Z', daysUntilDue: 0 }],
  estimatedListCost: '100.00', inventoryTracked: false,
};

function show(language: 'ru' | 'en' = 'ru') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>
    <ShoppingPanel tenantId={tenantId} language={language} />
  </QueryClientProvider>);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('shopping suggestions', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows member-scoped due suggestions, estimated cost, and no-inventory meaning', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      requests.push(String(input));
      return json(shopping);
    }));
    show();

    expect(await screen.findByText('Молоко 1 л')).toBeInTheDocument();
    expect(screen.getAllByText(/100,00/)).toHaveLength(2);
    expect(screen.getByText(/раз в 10 дн./)).toBeInTheDocument();
    expect(screen.getByText(/не учёт запасов/i)).toBeInTheDocument();
    expect(requests).toEqual([`/bff/tenants/${tenantId}/shopping`]);
  });

  it('shows an honest empty state and English copy', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ candidates: [], estimatedListCost: '0.00', inventoryTracked: false })));
    show('en');

    expect(await screen.findByText(/three purchases/i)).toBeInTheDocument();
    expect(screen.getByText(/not home inventory/i)).toBeInTheDocument();
  });
});

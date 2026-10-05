import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ShoppingPanel } from './ShoppingPanel';

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const shopping = {
  candidates: [{ productName: 'Молоко 1 л', productKey: 'milk', purchaseCount: 3, medianIntervalDays: 10,
    usualUnitPrice: '100.000000', estimatedCost: '100.00', lastPurchasedAt: '2026-10-04T00:00:00Z',
    dueAt: '2026-10-05T00:00:00Z', daysUntilDue: 0 }],
  estimatedListCost: '100.00', inventoryTracked: false, boughtCandidates: [], mutedCandidates: [], blockedCandidates: [],
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
    vi.stubGlobal('fetch', vi.fn(async () => json({ candidates: [], estimatedListCost: '0.00', inventoryTracked: false,
      boughtCandidates: [], mutedCandidates: [], blockedCandidates: [] })));
    show('en');

    expect(await screen.findByText(/three purchases/i)).toBeInTheDocument();
    expect(screen.getByText(/not home inventory/i)).toBeInTheDocument();
  });

  it('marks a suggestion bought through the CSRF protected BFF and explains expiry', async () => {
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    const updated = { ...shopping, candidates: [], estimatedListCost: '0.00', boughtCandidates: shopping.candidates };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, init });
      if (url === '/bff/csrf') return json({ token: 'csrf-test' });
      if (init?.method === 'POST') return json(updated);
      return json(shopping);
    }));
    show();

    fireEvent.click(await screen.findByRole('button', { name: 'Уже купил' }));

    expect(await screen.findByText(/отметка действует до следующего обычного интервала/i)).toBeInTheDocument();
    const action = requests.find((request) => request.init?.method === 'POST');
    expect(action?.url).toBe(`/bff/tenants/${tenantId}/shopping/milk/bought`);
    expect(new Headers(action?.init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-test');
  });

  it('copies active candidates only and shows muted and blocked reasons', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    vi.stubGlobal('fetch', vi.fn(async () => json({ ...shopping,
      mutedCandidates: [{ ...shopping.candidates[0], productKey: 'tea' }],
      blockedCandidates: [{ productKey: 'candy', productName: 'Конфеты', reasonCode: 'confirmed_not_to_buy' }],
    })));
    show();

    expect(await screen.findByText(/вы отметили этот товар/i)).toBeInTheDocument();
    expect(screen.getByText(/вы скрыли эту подсказку/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Скопировать список' }));

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('Молоко 1 л'));
    expect(writeText.mock.calls[0][0]).not.toContain('Конфеты');
    expect(writeText.mock.calls[0][0]).not.toContain('tea');
  });
});

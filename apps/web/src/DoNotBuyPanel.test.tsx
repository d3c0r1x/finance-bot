import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DoNotBuyPanel } from './DoNotBuyPanel';

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const chips = { productKey: 'chips', productName: 'Чипсы', count: 2, amount: '30.00',
  missingAmountCount: 0, ruleCount: 1, modelCount: 1, unmarkedCount: 0, modelOnly: false,
  latestVerdict: 'harmful', latestAdvice: 'Покупать реже', lastPurchasedAt: '2026-10-01T10:00:00Z' };
const coffee = { productKey: 'coffee', productName: 'Кофе', count: 2, amount: '40.00',
  missingAmountCount: 0, ruleCount: 0, modelCount: 2, unmarkedCount: 0, modelOnly: true,
  latestVerdict: 'unnecessary', latestAdvice: '', lastPurchasedAt: '2026-10-02T10:00:00Z' };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function show(language: 'ru' | 'en' = 'ru', canWrite = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}>
    <DoNotBuyPanel tenantId={tenantId} language={language} canWrite={canWrite} />
  </QueryClientProvider>);
}

describe('personal do-not-buy evidence', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('keeps model-only evidence separate until a CSRF-protected human confirmation', async () => {
    let confirmed = false;
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, init });
      if (url === '/bff/csrf') return json({ token: 'csrf-evidence' });
      if (url.endsWith('/products/decisions')) return json({ productKeys: [], confirmedProductKeys: confirmed ? ['coffee'] : [] });
      if (url.endsWith('/products/do-not-buy')) return json({ available: true, reasonCode: 'available',
        algorithmVersion: 'advice-evidence.v1', inputVersion: '0'.repeat(64),
        banned: confirmed ? [chips, coffee] : [chips], guesses: confirmed ? [] : [coffee] });
      if (init?.method === 'PUT') { confirmed = true; return json({ productKey: 'coffee', decision: 'confirmed', version: 1,
        updatedAt: '2026-10-06T00:00:00Z' }); }
      throw new Error(`Unexpected request: ${url}`);
    }));
    show();

    expect(await screen.findByRole('heading', { name: 'Догадки модели' })).toBeInTheDocument();
    expect(screen.getByText('Чипсы')).toBeInTheDocument();
    expect(screen.getByText('Кофе')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить «не брать»' }));

    await waitFor(() => expect(screen.getByText('Подтверждено вами')).toBeInTheDocument());
    const action = requests.find((request) => request.init?.method === 'PUT');
    expect(action?.url).toBe(`/bff/tenants/${tenantId}/products/coffee/decision`);
    expect(action?.init?.body).toBe(JSON.stringify({ decision: 'confirmed' }));
    expect(new Headers(action?.init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-evidence');
  });

  it('shows an explicit unavailable state and keeps viewer controls read-only', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/products/decisions')
      ? json({ productKeys: [], confirmedProductKeys: [] })
      : json({ available: false, reasonCode: 'analytics_unavailable', algorithmVersion: null,
        inputVersion: null, banned: [], guesses: [] })));
    show('en', false);
    expect(await screen.findByText(/advice evidence is temporarily unavailable/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /confirm/i })).not.toBeInTheDocument();
  });

  it('allows a rule-backed product through a CSRF-protected decision', async () => {
    let allowed = false;
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, init });
      if (url === '/bff/csrf') return json({ token: 'csrf-allow' });
      if (url.endsWith('/products/decisions')) return json({ productKeys: allowed ? ['chips'] : [], confirmedProductKeys: [] });
      if (url.endsWith('/products/do-not-buy')) return json({ available: true, reasonCode: 'available',
        algorithmVersion: 'advice-evidence.v1', inputVersion: '0'.repeat(64),
        banned: allowed ? [] : [chips], guesses: [] });
      if (init?.method === 'PUT') { allowed = true; return json({ productKey: 'chips', decision: 'allowed', version: 1,
        updatedAt: '2026-10-06T00:00:00Z' }); }
      throw new Error(`Unexpected request: ${url}`);
    }));
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Можно брать' }));
    expect(await screen.findByText(/повторяющихся отметок/i)).toBeInTheDocument();
    const action = requests.find((request) => request.init?.method === 'PUT');
    expect(action?.url).toBe(`/bff/tenants/${tenantId}/products/chips/decision`);
    expect(action?.init?.body).toBe(JSON.stringify({ decision: 'allowed' }));
    expect(new Headers(action?.init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-allow');
  });

  it('shows evidence to a viewer without decision controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/products/decisions')
      ? json({ productKeys: [], confirmedProductKeys: [] })
      : json({ available: true, reasonCode: 'available', algorithmVersion: 'advice-evidence.v1',
        inputVersion: '0'.repeat(64), banned: [chips], guesses: [coffee] })));
    show('en', false);
    expect(await screen.findByText('Чипсы')).toBeInTheDocument();
    expect(screen.getByText('Кофе')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm do not buy' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Allow purchase' })).not.toBeInTheDocument();
  });
});

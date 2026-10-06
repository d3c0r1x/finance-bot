import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReceiptRecalculationPanel } from './ReceiptRecalculationPanel';

const tenantId = 'tenant-42';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const preview = {
  runId: 'run-1', algorithmVersion: 'receipt-basket.v1', state: 'previewed', checked: 4,
  updateCount: 2, changedCount: 1,
  changes: [{
    itemId: 'item-1', name: 'Chips', lineSum: '125.00', itemVersion: 3,
    beforeVerdict: 'neutral', beforeReason: 'old reason', beforeAction: null, beforeSource: 'model',
    afterVerdict: 'unnecessary', afterReason: 'Current rule', afterAction: 'Skip next time',
    afterSource: 'rule', changed: true,
  }],
};
const applied = { ...preview, state: 'applied', appliedCount: 2 };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

function mount(canWrite = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>
    <ReceiptRecalculationPanel tenantId={tenantId} language="ru" canWrite={canWrite} />
  </QueryClientProvider>);
}

describe('ReceiptRecalculationPanel', () => {
  it('previews changes first and applies only after explicit confirmation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf' });
      if (url.endsWith('/review-recalculations/preview')) return json(preview);
      if (url.endsWith('/review-recalculations/apply')) return json(applied);
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Проверить старые разборы' }));
    expect(await screen.findByText('Старое: neutral · old reason')).toBeInTheDocument();
    expect(screen.getByText('Новое: unnecessary · Current rule')).toBeInTheDocument();
    expect(screen.getByText(/Суммы чеков и операций не меняются/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/apply'))).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Применить пересчёт' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/apply'))).toBe(true));
    expect(await screen.findByText(/Изменено позиций: 1/)).toBeInTheDocument();
  });

  it('does not expose preview or apply actions to a viewer', () => {
    mount(false);
    expect(screen.queryByRole('button', { name: 'Проверить старые разборы' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Применить пересчёт' })).not.toBeInTheDocument();
  });

  it('drops an older preview when refreshing it fails', async () => {
    let attempts = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/bff/csrf') return json({ token: 'csrf' });
      attempts += 1;
      return attempts === 1 ? json(preview) : json({ detail: 'Preview unavailable' }, 503);
    });
    vi.stubGlobal('fetch', fetchMock);
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Проверить старые разборы' }));
    expect(await screen.findByRole('button', { name: 'Применить пересчёт' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Проверить старые разборы' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Preview unavailable');
    expect(screen.queryByRole('button', { name: 'Применить пересчёт' })).not.toBeInTheDocument();
  });
});

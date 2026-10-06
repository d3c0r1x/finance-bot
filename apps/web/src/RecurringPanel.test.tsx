import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecurringPanel } from './RecurringPanel';
import type { RecurringProjection, RecurringSeries } from './api';

const tenantId = '9f529dee-205a-4fed-94c2-7d9c66b19d24';
const phone = series('1', 'Phone plan', 2);
const overdue = series('2', 'Old payment', -7);

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('shows three-day warnings and overdue expenses in separate sections', async () => {
  const requests: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input); requests.push(url);
    if (url === `/bff/tenants/${tenantId}/analytics/recurring`) return json({
      algorithmVersion: 'recurring.v1', completeness: 'complete', timeZone: 'Europe/Moscow',
      asOf: '2026-08-20T00:00:00+03:00', expenseSeries: [phone, overdue], incomeSeries: [],
      dueSoon: [phone], overdue: [overdue], nextIncome: null, monthlyExpenseEstimate: '857.14',
      monthlyExpenseEstimates: { RUB: '857.14' }, mutedSeries: [],
    });
    throw new Error(`Unexpected request ${url}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RecurringPanel tenantId={tenantId} language="ru" /></QueryClientProvider>);

  expect(await screen.findByRole('heading', { name: 'Регулярные платежи и доходы' })).toBeInTheDocument();
  expect(await screen.findAllByText('Phone plan')).toHaveLength(2);
  const dueSoon = screen.getByRole('region', { name: 'Скоро' });
  const overdueSection = screen.getByRole('region', { name: 'Просрочено' });
  expect(within(dueSoon).getByText('Phone plan')).toBeInTheDocument();
  expect(within(dueSoon).getByText(/интервал: 7–7 дн\./)).toBeInTheDocument();
  expect(within(dueSoon).queryByText('Old payment')).not.toBeInTheDocument();
  expect(within(overdueSection).getByText('Old payment')).toBeInTheDocument();
  expect(screen.getByText(/857,14/)).toBeInTheDocument();
  expect(requests).toEqual([`/bff/tenants/${tenantId}/analytics/recurring`]);
});

it('explains the minimum history without inventing recurring amounts', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json({
    algorithmVersion: 'recurring.v1', completeness: 'complete', timeZone: 'UTC', asOf: '2026-08-20T00:00:00Z',
    expenseSeries: [], incomeSeries: [], dueSoon: [], overdue: [], nextIncome: null,
    monthlyExpenseEstimate: null, monthlyExpenseEstimates: {}, mutedSeries: [],
  })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RecurringPanel tenantId={tenantId} language="en" /></QueryClientProvider>);
  expect(await screen.findByText(/No recurring transactions/)).toBeInTheDocument();
  expect(screen.getByText(/At least 3 similar transactions/)).toBeInTheDocument();
  expect(screen.queryByText(/0\.00/)).not.toBeInTheDocument();
});

it('mutes and restores recurring reminders through member-scoped BFF actions', async () => {
  const muted = series('4', 'Cloud backup', 1);
  const active: RecurringProjection = {
    algorithmVersion: 'recurring.v1', completeness: 'complete', timeZone: 'UTC', asOf: '2026-08-20T00:00:00Z',
    expenseSeries: [phone], incomeSeries: [], dueSoon: [phone], overdue: [], nextIncome: null,
    monthlyExpenseEstimate: '100.00', monthlyExpenseEstimates: { RUB: '100.00' }, mutedSeries: [muted],
  };
  const mutedPhone: RecurringProjection = {
    ...active, expenseSeries: [], dueSoon: [], monthlyExpenseEstimate: null, monthlyExpenseEstimates: {},
    mutedSeries: [muted, phone],
  };
  let current: RecurringProjection = active;
  const calls: Array<{ url: string; method: string }> = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'; calls.push({ url, method });
    if (url === '/bff/csrf') return json({ token: 'test-csrf-token' });
    if (url === `/bff/tenants/${tenantId}/analytics/recurring` && method === 'GET') return json(current);
    if (url === `/bff/tenants/${tenantId}/analytics/recurring/${phone.id}/mute` && method === 'PUT') {
      current = mutedPhone; return json(current);
    }
    if (url === `/bff/tenants/${tenantId}/analytics/recurring/${phone.id}/mute` && method === 'DELETE') {
      current = active; return json(current);
    }
    throw new Error(`Unexpected request ${method} ${url}`);
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RecurringPanel tenantId={tenantId} language="ru" /></QueryClientProvider>);

  fireEvent.click(await screen.findByRole('button', { name: 'Отключить Phone plan' }));
  expect(await screen.findByRole('region', { name: 'Отключённые напоминания' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Восстановить Phone plan' })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: 'Расходы' })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Восстановить Phone plan' }));
  await waitFor(() => expect(screen.getByRole('region', { name: 'Расходы' })).toBeInTheDocument());
  expect(calls.filter((call) => call.method !== 'GET')).toEqual([
    { url: `/bff/tenants/${tenantId}/analytics/recurring/${phone.id}/mute`, method: 'PUT' },
    { url: `/bff/tenants/${tenantId}/analytics/recurring/${phone.id}/mute`, method: 'DELETE' },
  ]);
});

function series(id: string, name: string, daysUntil: number): RecurringSeries {
  const lastDate = daysUntil < 0 ? '2026-08-06' : '2026-08-15';
  const nextDate = daysUntil < 0 ? '2026-08-13' : '2026-08-22';
  return { id: id.repeat(32), key: name.toLowerCase(), name, category: 'utilities', type: 'expense', currency: 'RUB',
    amount: '100.00', minAmount: '100.00', maxAmount: '100.00', periodCode: 'week', periodDays: 7,
    minIntervalDays: 7, maxIntervalDays: 7, occurrences: 3, lastDate, nextDate, daysUntil };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

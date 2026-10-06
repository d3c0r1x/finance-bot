import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdviceAnalyticsPanel } from './AdviceAnalyticsPanel';
import type { AdviceAnalyticsJob, AdviceAnalyticsReport } from './api';

const tenantId = '9f529dee-205a-4fed-94c2-7d9c66b19d24';
const report: AdviceAnalyticsReport = {
  algorithmVersion: 'advice-f43.v1', inputWatermark: '4', completeness: 'complete', reasonCode: 'available',
  savings: { available: true, reasonCode: 'available', label: 'theoretical_ceiling_not_actual_savings', days: 90,
    monthlyCeiling: '1200.00', shareOfIncome: '6.0', shareOfLimit: '12.0',
    groups: [{ productKey: 'snack', name: 'Снеки', count: 3, spend: '3600.00', monthlyCeiling: '1200.00' }] },
  trend: { available: true, reasonCode: 'available', delta: '-0.125', direction: 'down', weeks: [
    { start: '2026-09-01', end: '2026-09-07', spend: '5000.00', optionalSpend: '1500.00', optionalShare: '0.300', itemCount: 8, recalculated: false },
    { start: '2026-09-08', end: '2026-09-14', spend: '4000.00', optionalSpend: '700.00', optionalShare: '0.175', itemCount: 6, recalculated: true },
  ] },
  effects: { effects: [{ productKey: 'snack', name: 'Снеки', advice: 'Не брать по дороге', beforeCount: 2, afterCount: 0,
    daysBefore: 35, daysAfter: 30, intervalBefore: '17.5', intervalAfter: null, change: '-100.0', direction: 'less_often', afterSpend: '0.00' }],
    pending: [], causalityClaim: false },
  recalculation: { available: true, changedItemCount: 1, optionalSpendDelta: '-50.00', windows: [
    { start: '2026-09-08', end: '2026-09-14', changedItemCount: 1, optionalSpendDelta: '-50.00' },
  ] },
};

const job = (state: AdviceAnalyticsJob['state'], data: AdviceAnalyticsReport | null = null): AdviceAnalyticsJob => ({
  id: 'job-1', state, inputWatermark: data?.inputWatermark ?? '4', algorithmVersion: 'advice-f43.v1',
  completeness: data?.completeness ?? null, errorCode: null, report: data, updatedAt: '2026-10-07T00:00:00Z',
});

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

function mount(language: 'ru' | 'en' = 'ru', canWrite = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <AdviceAnalyticsPanel tenantId={tenantId} language={language} canWrite={canWrite} />
  </QueryClientProvider>);
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('AdviceAnalyticsPanel', () => {
  it('shows idle and only enqueues after the user explicitly requests a calculation', async () => {
    const calls: Array<{ path: string; method: string }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input); const method = init?.method ?? 'GET'; calls.push({ path, method });
      if (path === `/bff/tenants/${tenantId}/analytics/advice` && method === 'GET') return json(job(null));
      if (path === '/bff/csrf') return json({ token: 'csrf' });
      if (method === 'POST' && path === `/bff/tenants/${tenantId}/analytics/advice`) return json(job('pending'), 202);
      throw new Error(`Unexpected request ${method} ${path}`);
    }));
    mount();
    expect(await screen.findByRole('button', { name: 'Рассчитать аналитику' })).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Рассчитать аналитику' }));
    expect(await screen.findByText('Расчёт поставлен в очередь')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST' && call.path.endsWith('/analytics/advice'))).toBe(true);
  });

  it('polls pending jobs, then shows the theoretical ceiling and data with explicit non-causal wording', async () => {
    let reads = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) !== `/bff/tenants/${tenantId}/analytics/advice`) throw new Error('unexpected URL');
      reads++;
      return json(reads === 1 ? job('pending') : job('ready', report));
    }));
    mount();
    expect(await screen.findByText('Расчёт поставлен в очередь')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Теоретический потолок за месяц')).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.getAllByText(/1\s?200,00/).length).toBeGreaterThan(0);
    expect(screen.getByText('Снеки')).toBeInTheDocument();
    expect(screen.getByText(/После совета:/)).toBeInTheDocument();
    expect(screen.getByText(/не доказательство причинной связи/)).toBeInTheDocument();
    expect(screen.getByText('Изменено позиций: 1')).toBeInTheDocument();
    expect(screen.queryByText(/Сэкономлено вами/)).not.toBeInTheDocument();
    expect(reads).toBeGreaterThan(1);
  });

  it('shows partial completeness and missing-amount reasons without filling zeros', async () => {
    const partial: AdviceAnalyticsReport = { ...report, completeness: 'partial', reasonCode: 'missing_amounts',
      savings: { ...report.savings, available: false, reasonCode: 'missing_amounts', monthlyCeiling: null, groups: [] },
      trend: { ...report.trend, available: false, reasonCode: 'missing_amounts', delta: null, weeks: [] } };
    vi.stubGlobal('fetch', vi.fn(async () => json(job('ready', partial))));
    mount();
    expect(await screen.findByText(/Часть сумм в чеках неизвестна/)).toBeInTheDocument();
    expect(screen.getByText(/Нет данных для оценки потолка/)).toBeInTheDocument();
    const savingsCard = screen.getByRole('heading', { name: 'Теоретический потолок за месяц' }).closest('article');
    expect(within(savingsCard!).queryByText(/0,00/)).not.toBeInTheDocument();
  });

  it('keeps unavailable weekly trend explicit when fewer than two weeks have data', async () => {
    const unavailable = { ...report, trend: { ...report.trend, available: false, reasonCode: 'insufficient_history', weeks: [], delta: null } };
    vi.stubGlobal('fetch', vi.fn(async () => json(job('ready', unavailable))));
    mount('en');
    expect(await screen.findByText('Optional-spend share by week')).toBeInTheDocument();
    expect(screen.getByText(/At least two weeks of confirmed purchases are needed/)).toBeInTheDocument();
  });

  it('does not show an older report as current when the job is stale', async () => {
    const old = { ...job('stale', report), report };
    vi.stubGlobal('fetch', vi.fn(async () => json(old)));
    mount();
    expect(await screen.findByText('Данные изменились — рассчитайте заново')).toBeInTheDocument();
    expect(screen.queryByText('Теоретический потолок за месяц')).not.toBeInTheDocument();
  });

  it('does not let a viewer enqueue recalculation', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(job('ready', report))));
    mount('ru', false);
    expect(await screen.findByText('Расчёт готов')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Рассчитать заново' })).not.toBeInTheDocument();
  });

  it('lets the member retry a failed job and displays failure state', async () => {
    let current = job('failed');
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === `/bff/tenants/${tenantId}/analytics/advice` && init?.method === 'POST') { current = job('pending'); return json(current, 202); }
      if (path === `/bff/tenants/${tenantId}/analytics/advice`) return json(current);
      if (path === '/bff/csrf') return json({ token: 'csrf' });
      throw new Error(`Unexpected request ${path}`);
    }));
    mount();
    expect(await screen.findByText('Расчёт не выполнен')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить расчёт' }));
    expect(await screen.findByText('Расчёт поставлен в очередь')).toBeInTheDocument();
  });
});

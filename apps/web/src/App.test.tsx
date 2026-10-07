import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from './App';

const tenant = {
  tenantId: 'b9224f75-9555-4ec3-983e-16eb6f332921',
  userId: 'owner-user-id',
  displayName: 'Дом',
  role: 'owner',
  timezone: 'Europe/Moscow',
};

const foodStatusWithoutHistory = {
  fromDate: '2026-09-25', toDate: '2026-10-01', limit: '0.00', spent: '0.00', remaining: null,
  limitStatus: 'disabled', usualWeeklySpend: null, historyWeeks: 0, paceStatus: 'insufficient_history', paceShare: null,
};

function dashboardSummary(overrides: Record<string, unknown> = {}) {
  return {
    month: '2026-10', currency: 'RUB', incomeTotal: '0.00', expenseTotal: '0.00', transactionCount: 0,
    asOfDate: '2026-10-01', daysElapsed: 1, daysInMonth: 31, daysRemaining: 30,
    dailyExpensePace: null, projectedExpenseTotal: null, safeToSpend: null, ...overrides,
    rolling7FoodStatus: overrides.rolling7FoodStatus ?? foodStatusWithoutHistory,
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('web onboarding and transaction flow', () => {
  beforeEach(() => {
    let transactions: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url === '/bff/me/telegram-link' && init?.method === 'POST') {
        return json({ code: 'ABCD-EFGH-JKLM-NPQR', expiresAt: '2026-10-02T08:20:00Z' });
      }
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url === `/bff/tenants/${tenant.tenantId}/profile/me`) {
        return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      }
      if (url.startsWith(`/bff/tenants/${tenant.tenantId}/transactions`) && !init?.method) {
        return json({ items: transactions, nextCursor: null });
      }
      if (url === `/bff/tenants/${tenant.tenantId}/transactions` && init?.method === 'POST') {
        const request = JSON.parse(String(init.body)) as Record<string, unknown>;
        transactions = [{
          id: '3671e8fa-9de9-4439-9f4e-024ae61f30bd',
          tenantId: tenant.tenantId,
          type: request.type,
          amount: request.amount,
          currency: 'RUB',
          categoryCode: request.categoryCode,
          subcategoryCode: null,
          description: request.description,
          source: request.source,
          occurredAt: request.occurredAt,
          accountId: null,
          status: 'posted',
          version: 1,
          createdAt: request.occurredAt,
        }, ...transactions];
        return json(transactions[0], 201);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the current budget alert, remaining amount and month-end pace forecast', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary({
        month: '2026-10', currency: 'RUB', incomeTotal: '120000.00', expenseTotal: '90000.00', transactionCount: 4,
        asOfDate: '2026-10-10', daysElapsed: 10, daysInMonth: 31, daysRemaining: 21,
        dailyExpensePace: '9000.00', projectedExpenseTotal: '279000.00',
        safeToSpend: { incomeBasis: 'actual_income', incomeBase: '120000.00', month: '2026-10', horizonDate: '2026-10-31',
          daysRemaining: 21, monthlyExpenses: '90000.00', reserve: '12000.00', promisedPayments: '10000.00',
          safeTotal: '8000.00', safePerDay: '380.95' },
      }));
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: 120000, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets')) return json({
        currency: 'RUB', month: '2026-10', familyLimits: {}, personalOverrides: {}, effectiveLimits: {}, monthlySpent: {}, limitStatus: {},
        familyVersions: {}, personalVersions: {}, familyTotalLimit: '100000.00', personalTotalOverride: null, effectiveTotalLimit: '100000.00',
        totalMonthlySpent: '90000.00', totalLimitStatus: 'near', familyTotalVersion: 1, personalTotalVersion: 0,
        rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null, effectiveRolling7FoodLimit: '0.00', rolling7FoodSpent: '0.00',
        rolling7FoodLimitStatus: 'disabled', familyRolling7FoodVersion: 0, personalRolling7FoodVersion: 0,
      });
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/dashboard']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    expect(await screen.findByText('Средний темп расходов в день:', { exact: false })).toHaveTextContent(/9\s*000,00/);
    expect(screen.getByText('Почти достигнут')).toBeInTheDocument();
    expect(screen.getByText('Остаток месячного лимита:', { exact: false })).toHaveTextContent(/10\s*000,00/);
    expect(screen.getByText('Прогноз расходов к концу месяца:', { exact: false })).toHaveTextContent(/279\s*000,00/);
    expect(screen.getByText('Ожидаемый перерасход лимита:', { exact: false })).toHaveTextContent(/179\s*000,00/);
    expect(screen.getByText('Безопасно тратить в день:', { exact: false })).toHaveTextContent(/380,95/);
    expect(screen.getByText('Обязательные списания:', { exact: false })).toHaveTextContent(/10\s*000,00/);
    expect(screen.getByText('Истории мало для сравнения; лимит за 7 дней действует.')).toBeInTheDocument();
  });

  it('shows date-bounded reports and uses the family monthly limit for family scope', async () => {
    const reportRequests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.includes('/reports/')) {
        reportRequests.push(url);
        const family = url.includes('/reports/family');
        const monthlyFamilyReport = family && url.includes('period=month');
        return json({
          period: 'month', scope: family ? 'family' : 'personal', fromDate: '2026-10-01', toDate: '2026-10-31',
          asOfDate: '2026-10-10', timezone: 'Europe/Moscow', currency: 'RUB', incomeTotal: '100000.00',
          expenseTotal: monthlyFamilyReport ? '75000.00' : family ? '25000.00' : '15000.00', debtPaymentTotal: '3000.00', refundTotal: '500.00',
          transactionCount: 7, expenseByCategory: { food: monthlyFamilyReport ? '75000.00' : family ? '25000.00' : '15000.00' },
          expenseByDay: { '2026-10-01': '0.00', '2026-10-02': monthlyFamilyReport ? '75000.00' : family ? '25000.00' : '0.00', '2026-10-03': family ? '0.00' : '15000.00' },
          weekendSharePercent: 40, monthlyBudgetLimit: url.includes('period=month') ? (family ? '70000.00' : '55000.00') : null,
          monthlyBudgetRemaining: url.includes('period=month') ? (family ? '-4500.00' : '40500.00') : null,
          rolling7FoodStatus: foodStatusWithoutHistory,
          waste: url.includes('period=custom')
            ? { available: false, reasonCode: 'missing_amounts', completeness: 'partial', reviewedSpend: null,
              optionalSpend: null, optionalShare: null, reviewedItemCount: 2, optionalItemCount: 0, missingAmountCount: 1,
              bySource: {}, optionalByDay: {}, topItems: [], corrected: [] }
            : { available: true, reasonCode: 'available', completeness: 'complete', reviewedSpend: '120.00',
              optionalSpend: '20.00', optionalShare: '0.166667', reviewedItemCount: 5, optionalItemCount: 2,
              missingAmountCount: 0, bySource: { model: '13.00', rule: '7.00' },
              optionalByDay: { '2026-10-01': '0.00', '2026-10-02': '13.00', '2026-10-03': '7.00' },
              topItems: [{ name: 'Сок', amount: '13.00', verdict: 'optional', source: 'model' }],
              corrected: [{ productName: 'Молоко', count: 1, amount: '30.00' }] },
        });
      }
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      if (url.endsWith('/budgets')) return json({ currency: 'RUB', month: '2026-10', familyLimits: {}, personalOverrides: {},
        effectiveLimits: {}, monthlySpent: {}, limitStatus: {}, familyVersions: {}, personalVersions: {}, familyTotalLimit: '55000.00',
        personalTotalOverride: null, effectiveTotalLimit: '55000.00', totalMonthlySpent: '0.00', totalLimitStatus: 'normal',
        familyTotalVersion: 0, personalTotalVersion: 0, rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null,
        effectiveRolling7FoodLimit: '0.00', rolling7FoodSpent: '0.00', rolling7FoodLimitStatus: 'disabled',
        familyRolling7FoodVersion: 0, personalRolling7FoodVersion: 0 });
      if (url.endsWith('/debts')) return json({ items: [] });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/reports']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.click(await screen.findByRole('link', { name: 'Отчёты' }));
    expect((await screen.findByText('Возвраты')).closest('article')).toHaveTextContent(/500,00/);
    expect(screen.getByText('Платежи по долгам').closest('article')).toHaveTextContent(/3\s*000,00/);
    expect(screen.getByText('Истории мало для сравнения; лимит за 7 дней действует.')).toBeInTheDocument();
    expect(screen.getByText(/Остаток месячного лимита/).closest('p')).toHaveTextContent(/40\s*500,00/);
    expect(screen.getByRole('figure', { name: 'Использование месячных лимитов' }))
      .toHaveTextContent(/14\s*500,00.*55\s*000,00/);
    expect(screen.getByText('Необязательные покупки').closest('article'))
      .toHaveTextContent(/20,00.*16,7%.*120,00/);
    expect(screen.getByText(/model:/).closest('li')).toHaveTextContent(/13,00/);
    expect(screen.getByText(/Сок:/).closest('li')).toHaveTextContent(/13,00/);
    expect(screen.getByText(/Молоко:/).closest('li')).toHaveTextContent(/30,00/);
    expect(screen.getByRole('figure', { name: 'Необязательные покупки по дням' }))
      .toHaveTextContent(/2026-10-02.*13,00.*2026-10-03.*7,00/);
    expect(screen.getByRole('figure', { name: 'Необязательные покупки по дням' }))
      .toHaveTextContent(/2026-10-01.*0,00/);
    await user.selectOptions(screen.getByLabelText('Область отчёта'), 'family');
    await waitFor(() => expect(screen.getByText(/Остаток месячного лимита/).closest('p'))
      .toHaveTextContent(/Семейный месячный лимит.*70\s*000,00.*-4\s*500,00/));
    expect(screen.getByRole('figure', { name: 'Использование месячных лимитов' }))
      .toHaveTextContent(/74\s*500,00.*70\s*000,00/);
    expect(screen.getByRole('figure', { name: 'Использование месячных лимитов' }).querySelector('.chart-bar'))
      .toHaveStyle({ width: '100%' });
    await user.click(screen.getByRole('button', { name: 'EN' }));
    expect(screen.getByText('Optional purchases').closest('article')).toHaveTextContent(/20\.00.*16\.7%.*120\.00/);
    expect(screen.getByRole('figure', { name: 'Optional purchases by day' }))
      .toHaveTextContent(/2026-10-02.*13\.00.*2026-10-03.*7\.00/);
    expect(screen.getByRole('figure', { name: 'Optional purchases by day' }))
      .toHaveTextContent(/2026-10-01.*0\.00/);
    await user.click(screen.getByRole('button', { name: 'RU' }));
    await user.selectOptions(screen.getByLabelText('Период отчёта'), 'custom');
    await user.type(screen.getByLabelText('С даты отчёта'), '2026-10-01');
    await user.type(screen.getByLabelText('По дату отчёта'), '2026-10-03');
    await waitFor(() => expect(reportRequests.some((url) => url.includes('period=custom')
      && url.includes('from=2026-10-01') && url.includes('to=2026-10-03'))).toBe(true));
    expect(await screen.findByText(/Не все позиции чеков имеют сумму/)).toBeInTheDocument();
    expect(screen.getByText('Необязательные покупки').closest('article')).not.toHaveTextContent(/0,00/);
    expect(screen.queryByRole('figure', { name: 'Необязательные покупки по дням' })).not.toBeInTheDocument();
    expect(reportRequests.some((url) => url.includes('/reports/family?period=month'))).toBe(true);
    await waitFor(() => expect(screen.queryByRole('figure', { name: 'Использование месячных лимитов' })).not.toBeInTheDocument());
    expect(screen.getByRole('figure', { name: 'Расходы по дням' })).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: 'Расходы по категориям' })).toBeInTheDocument();
    expect(screen.getByText('2026-10-01')).toBeInTheDocument();
  });

  it('creates a RUB transaction and shows it in history', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.type(screen.getByLabelText(/сумма/i), '345.60');
    await user.type(screen.getByLabelText(/описание/i), 'Продукты');
    await user.click(screen.getByRole('button', { name: /сохранить/i }));

    expect(await screen.findByText('Продукты')).toBeInTheDocument();
    const request = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST');
    expect(request).toBeDefined();
    const headers = new Headers(request?.[1]?.headers);
    expect(headers.get('Idempotency-Key')).toBeTruthy();
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-test-token');
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1));
  });

  it('shows the Core budget threshold notice after recording an expense', async () => {
    const alert = { budgetKey: 'other', threshold: 'near', limit: '5000.00', spent: '4500.00' };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null,
        onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/transactions') && init?.method === 'POST') return json({
        id: 'tx-budget-alert', tenantId: tenant.tenantId, type: 'expense', amount: '4500.00', currency: 'RUB',
        categoryCode: 'other', description: 'Large purchase', source: 'manual', occurredAt: '2026-10-04T10:00:00Z',
        status: 'posted', version: 1, createdAt: '2026-10-04T10:00:00Z', budgetAlerts: [alert],
      }, 201);
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      if (url.endsWith('/budgets')) return json({
        currency: 'RUB', month: '2026-10', familyLimits: {}, personalOverrides: {}, effectiveLimits: {},
        monthlySpent: {}, limitStatus: {}, familyVersions: {}, personalVersions: {},
        familyTotalLimit: '55000.00', personalTotalOverride: null, effectiveTotalLimit: '55000.00',
        totalMonthlySpent: '0.00', totalLimitStatus: 'normal', familyTotalVersion: 0, personalTotalVersion: 0,
        rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null, effectiveRolling7FoodLimit: '0.00',
        rolling7FoodSpent: '0.00', rolling7FoodLimitStatus: 'disabled', familyRolling7FoodVersion: 0,
        personalRolling7FoodVersion: 0,
      });
      if (url.endsWith('/debts')) return json({ items: [] });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await user.type(await screen.findByLabelText(/сумма/i), '4500');
    await user.type(screen.getByLabelText(/описание/i), 'Large purchase');
    await user.click(screen.getByRole('button', { name: /сохранить/i }));

    expect(await screen.findByRole('status')).toHaveTextContent('Почти достигнут: other');
    expect(screen.getByRole('status')).toHaveTextContent('4 500,00');
    expect(screen.getByRole('status')).toHaveTextContent('5 000,00');
    await user.click(screen.getByRole('button', { name: 'EN' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Near limit: other');
    expect(screen.getByRole('status')).toHaveTextContent('4,500.00');
    expect(screen.getByRole('status')).toHaveTextContent('5,000.00');
  });

  it('records manual income with its source label', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.selectOptions(screen.getByLabelText('Тип операции'), 'income');
    await user.type(screen.getByLabelText(/сумма/i), '80000');
    await user.type(screen.getByLabelText('Описание'), 'Зарплата');
    await user.clear(screen.getByLabelText('Источник'));
    await user.type(screen.getByLabelText('Источник'), 'salary');
    await user.click(screen.getByRole('button', { name: /сохранить/i }));

    expect(await screen.findByText('Зарплата')).toBeInTheDocument();
    expect(screen.getByText(/Источник: salary/)).toBeInTheDocument();
    const request = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(request?.[1]?.body))).toMatchObject({ type: 'income', source: 'salary' });
  });

  it('keeps an AI text extraction editable and creates no transaction before confirmation', async () => {
    const requests: Array<[string, RequestInit | undefined]> = [];
    let posted: Record<string, unknown> | undefined;
    let draft = {
      id: 'draft-1', tenantId: tenant.tenantId, type: 'expense', amount: '2000.00', currency: 'RUB',
      categoryCode: 'transport', subcategoryCode: null, description: 'Такси', occurredAt: '2026-10-01T09:00:00Z',
      state: 'pending', version: 1, provider: 'ollama', modelVersion: 'test-model',
      promptVersion: 'transaction-draft.v1', createdAt: '2026-10-01T09:00:00Z',
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions?')) return json({ items: posted ? [posted] : [], nextCursor: null });
      if (url.endsWith('/transaction-drafts') && init?.method === 'POST') return json(draft, 201);
      if (url.endsWith('/transaction-drafts/draft-1') && init?.method === 'PATCH') {
        draft = { ...draft, ...JSON.parse(String(init.body)), version: 2 };
        return json(draft);
      }
      if (url.endsWith('/transaction-drafts/draft-1/confirm') && init?.method === 'POST') {
        posted = { ...draft, id: 'posted-1', tenantId: tenant.tenantId, status: 'posted', version: 1 };
        return json(posted);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Операция текстом'), 'Такси 2 тыс');
    await user.click(screen.getByRole('button', { name: 'Разобрать текст' }));
    expect(await screen.findByLabelText('Сумма черновика')).toHaveValue('2000.00');
    expect(screen.getByText(/test-model/)).toBeInTheDocument();
    expect(screen.getByText('Операций пока нет')).toBeInTheDocument();
    expect(requests.some(([url, init]) => url.endsWith('/transactions') && init?.method === 'POST')).toBe(false);

    await user.clear(screen.getByLabelText('Сумма черновика'));
    await user.type(screen.getByLabelText('Сумма черновика'), '2100.00');
    await user.click(screen.getByRole('button', { name: 'Подтвердить и записать' }));
    expect(await screen.findByText('Такси')).toBeInTheDocument();
    const update = requests.find(([url, init]) => url.endsWith('/transaction-drafts/draft-1') && init?.method === 'PATCH');
    const confirm = requests.find(([url, init]) => url.endsWith('/transaction-drafts/draft-1/confirm') && init?.method === 'POST');
    expect(JSON.parse(String(update?.[1]?.body))).toMatchObject({ amount: '2100.00', categoryCode: 'transport' });
    expect(new Headers(update?.[1]?.headers).get('If-Match')).toBe('"1"');
    expect(new Headers(confirm?.[1]?.headers).get('If-Match')).toBe('"2"');
    expect(new Headers(confirm?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('csrf-test-token');
    expect(posted?.amount).toBe('2100.00');
  });

  it('requires a user-selected debt and offers quick amounts for a parsed debt payment', async () => {
    const requests: Array<[string, RequestInit | undefined]> = [];
    let posted: Record<string, unknown> | undefined;
    const debt = { id: 'debt-1', tenantId: tenant.tenantId, name: 'Credit card', currentBalance: '2000.00', status: 'open', version: 1 };
    let draft = {
      id: 'draft-debt', tenantId: tenant.tenantId, type: 'debt_payment', amount: '1500.00', currency: 'RUB',
      categoryCode: 'долги', subcategoryCode: null, description: 'Платёж по кредитке', occurredAt: '2026-10-01T09:00:00Z',
      debtId: null, state: 'pending', version: 1, provider: 'ollama', modelVersion: 'test-model',
      promptVersion: 'transaction-draft.v1', createdAt: '2026-10-01T09:00:00Z',
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [debt] });
      if (url.includes('/transactions?')) return json({ items: posted ? [posted] : [], nextCursor: null });
      if (url.endsWith('/transaction-drafts') && init?.method === 'POST') return json(draft, 201);
      if (url.endsWith('/transaction-drafts/draft-debt') && init?.method === 'PATCH') {
        draft = { ...draft, ...JSON.parse(String(init.body)), version: 2 };
        return json(draft);
      }
      if (url.endsWith('/transaction-drafts/draft-debt/confirm') && init?.method === 'POST') {
        posted = { ...draft, id: 'payment-1', status: 'posted', version: 1 };
        return json(posted);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Операция текстом'), 'Платёж по кредитке 1500');
    await user.click(screen.getByRole('button', { name: 'Разобрать текст' }));
    const confirmButton = await screen.findByRole('button', { name: 'Подтвердить и записать' });
    expect(confirmButton).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Быстрая сумма 500' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Быстрая сумма 500' }));
    expect(screen.getByLabelText('Сумма черновика')).toHaveValue('500.00');
    await screen.findByRole('option', { name: 'Credit card' });
    await user.selectOptions(screen.getByLabelText('Долг для платежа'), 'debt-1');
    await user.click(confirmButton);
    expect(await screen.findByText('Платёж по кредитке')).toBeInTheDocument();
    const update = requests.find(([url, init]) => url.endsWith('/transaction-drafts/draft-debt') && init?.method === 'PATCH');
    expect(JSON.parse(String(update?.[1]?.body))).toMatchObject({ type: 'debt_payment', amount: '500.00', debtId: 'debt-1' });
  });

  it('edits a transaction, repeats it once, and safely voids it', async () => {
    const transactions: Record<string, unknown>[] = [];
    let nextId = 1;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([{ ...tenant, timezone: 'America/Los_Angeles' }]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.includes('/transactions?')) return json({ items: transactions, nextCursor: null });
      if (url.endsWith('/transactions') && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as Record<string, unknown>;
        const item = { id: `transaction-${++nextId}`, tenantId: tenant.tenantId, ...body, subcategoryCode: body.subcategoryCode ?? null,
          accountId: null, status: 'posted', version: 1, createdAt: body.occurredAt };
        transactions.unshift(item);
        return json(item, 201);
      }
      const itemIndex = transactions.findIndex((item) => url.endsWith(`/transactions/${item.id}`)
        || url.endsWith(`/transactions/${item.id}/void`));
      if (itemIndex >= 0 && init?.method === 'PATCH') {
        const updated = { ...transactions[itemIndex], ...JSON.parse(String(init.body)), version: Number(transactions[itemIndex].version) + 1 };
        transactions[itemIndex] = updated;
        return json(updated);
      }
      if (itemIndex >= 0 && init?.method === 'POST' && url.endsWith('/void')) {
        transactions[itemIndex] = { ...transactions[itemIndex], status: 'voided', version: Number(transactions[itemIndex].version) + 1 };
        return json(transactions[itemIndex]);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.type(screen.getByLabelText(/сумма/i), '345.60');
    await user.type(screen.getByLabelText(/описание/i), 'Продукты');
    await user.type(screen.getByLabelText('Подкатегория'), 'Овощи');
    await user.click(screen.getByRole('button', { name: /сохранить/i }));
    await screen.findByText('Продукты');

    await user.click(screen.getByRole('button', { name: 'Изменить Продукты' }));
    await user.clear(screen.getByLabelText(/описание/i));
    await user.type(screen.getByLabelText(/описание/i), 'Покупки');
    await user.clear(screen.getByLabelText('Подкатегория'));
    await user.type(screen.getByLabelText('Подкатегория'), 'Молочные');
    await user.clear(screen.getByLabelText('Дата'));
    await user.type(screen.getByLabelText('Дата'), '2026-10-02');
    await user.click(screen.getByRole('button', { name: 'Обновить' }));
    expect(await screen.findByRole('button', { name: 'Повторить Покупки' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Повторить Покупки' }));
    await waitFor(() => expect(transactions).toHaveLength(2));
    await user.click(screen.getAllByRole('button', { name: 'Отменить Покупки' })[0]);

    const calls = vi.mocked(fetch).mock.calls;
    const patchCall = calls.find(([, init]) => init?.method === 'PATCH');
    expect(patchCall).toBeDefined();
    expect(JSON.parse(String(patchCall?.[1]?.body))).toMatchObject({ subcategoryCode: 'Молочные' });
    expect(JSON.parse(String(patchCall?.[1]?.body)).occurredAt).toBe('2026-10-02T19:00:00.000Z');
    expect(new Headers(patchCall?.[1]?.headers).get('If-Match')).toBe('"1"');
    expect(calls.some(([url, init]) => String(url).endsWith('/void') && init?.method === 'POST')).toBe(true);
    expect(calls.filter(([url, init]) => String(url).endsWith('/transactions') && init?.method === 'POST')).toHaveLength(2);
  });

  it('edits and voids debt payments while keeping repeat disabled', async () => {
    const debt = { id: 'debt-1', tenantId: tenant.tenantId, name: 'Credit card', currentBalance: '900.00', status: 'open', version: 2 };
    let payment: Record<string, unknown> = {
      id: 'payment-1', tenantId: tenant.tenantId, type: 'debt_payment', amount: '100.00', currency: 'RUB',
      categoryCode: 'долги', subcategoryCode: null, description: 'Payment', source: 'debt',
      occurredAt: '2026-10-01T09:00:00Z', accountId: null, debtId: 'debt-1', ownerUserId: tenant.userId,
      memberName: 'Alex', status: 'posted', version: 1, createdAt: '2026-10-01T09:00:00Z',
    };
    const calls: Array<[string, RequestInit | undefined]> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/members')) return json([{ userId: tenant.userId, displayName: 'Alex', role: 'owner' }]);
      if (url.endsWith('/debts')) return json({ items: [debt] });
      if (url.includes('/transactions?')) return json({ items: [payment], nextCursor: null });
      if (url.endsWith('/transactions/payment-1') && init?.method === 'PATCH') {
        payment = { ...payment, ...JSON.parse(String(init.body)), version: 2 };
        return json(payment);
      }
      if (url.endsWith('/transactions/payment-1/void') && init?.method === 'POST') {
        payment = { ...payment, status: 'voided', version: 3 };
        return json(payment);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await user.click(await screen.findByRole('button', { name: 'Изменить Payment' }));
    await user.clear(screen.getByLabelText(/сумма/i));
    await user.type(screen.getByLabelText(/сумма/i), '150.00');
    await user.selectOptions(screen.getByLabelText('Долг для платежа'), 'debt-1');
    await user.click(screen.getByRole('button', { name: 'Обновить' }));
    await waitFor(() => expect(calls.some(([, init]) => init?.method === 'PATCH'
      && JSON.parse(String(init.body)).debtId === 'debt-1'
      && JSON.parse(String(init.body)).amount === '150.00')).toBe(true));
    expect(screen.queryByRole('button', { name: 'Повторить Payment' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Отменить Payment' }));
    await waitFor(() => expect(calls.some(([url, init]) => url.endsWith('/void') && init?.method === 'POST')).toBe(true));
  });

  it('shows a recoverable profile error instead of loading forever', async () => {
    let profileAttempts = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/profile/me') && ++profileAttempts === 1) return json({ title: 'Temporary failure' }, 503);
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/profile']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    expect(await screen.findByRole('alert')).toHaveTextContent('Temporary failure');
    await user.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByLabelText('Ваше имя')).toHaveValue('Alex');
    expect(profileAttempts).toBe(2);
  });

  it('loads family budgets and saves a versioned personal override', async () => {
    let budget = {
      currency: 'RUB', month: '2026-10', familyLimits: { 'еда': '20000.00' }, personalOverrides: {}, effectiveLimits: { 'еда': '20000.00' },
      monthlySpent: {}, limitStatus: { 'еда': 'normal' }, totalMonthlySpent: '0.00', totalLimitStatus: 'normal',
      familyVersions: { 'еда': 0 }, personalVersions: {}, familyTotalLimit: '55000.00', personalTotalOverride: null,
      effectiveTotalLimit: '55000.00', familyTotalVersion: 0, personalTotalVersion: 0,
      rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null, effectiveRolling7FoodLimit: '0.00',
      rolling7FoodSpent: '140.00', rolling7FoodLimitStatus: 'disabled', familyRolling7FoodVersion: 0, personalRolling7FoodVersion: 0,
    };
    const requests: Array<[string, RequestInit | undefined]> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: 100000, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets') && !init?.method) return json(budget);
      if (decodeURIComponent(url).endsWith('/budgets/еда') && init?.method === 'PUT') {
        const request = JSON.parse(String(init.body)) as { scope: string; amount: string; period: string };
        budget = request.period === 'rolling7'
          ? { ...budget, rolling7FoodLimit: request.amount + '.00', effectiveRolling7FoodLimit: request.amount + '.00', familyRolling7FoodVersion: 1 }
          : { ...budget, personalOverrides: { 'еда': request.amount + '.00' }, effectiveLimits: { 'еда': request.amount + '.00' }, personalVersions: { 'еда': 1 } };
        return json(budget);
      }
      if (url.endsWith('/budget-proposals/history') && init?.method === 'POST') return json({
        id: 'proposal-1', monthlyIncome: '100000.00', totalLimit: '70000.00', limits: { 'еда': '28000.00' },
        baseVersions: { 'еда': 0 }, baseTotalVersion: 0, status: 'pending', createdAt: '2026-10-01T10:00:00Z',
        proposalSource: 'history_ai', historyDays: 30, modelVersion: 'test-model', promptVersion: 'budget-proposal.v1',
      }, 201);
      if (url.endsWith('/budget-proposals') && init?.method === 'POST') return json({
        id: 'proposal-1', monthlyIncome: '100000.00', totalLimit: '70000.00', limits: { 'еда': '25500.00' },
        baseVersions: { 'еда': 0 }, baseTotalVersion: 0, status: 'pending', createdAt: '2026-10-01T10:00:00Z',
        proposalSource: 'income', historyDays: 0, modelVersion: null, promptVersion: null,
      }, 201);
      if (url.endsWith('/budget-proposals/proposal-1/apply') && init?.method === 'POST') {
        budget = { ...budget, familyTotalLimit: '70000.00' };
        return json(budget);
      }
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.click(await screen.findByRole('link', { name: 'Бюджеты' }));
    expect(await screen.findByLabelText('Лимит Еда')).toHaveValue('20000.00');
    expect(screen.getByRole('figure', { name: 'Использование месячных лимитов' })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Область бюджета Еда'), 'personal');
    const amountInput = screen.getByLabelText('Лимит Еда');
    await user.clear(amountInput);
    await user.type(amountInput, '18000');
    await user.click(screen.getByRole('button', { name: 'Сохранить лимит Еда' }));

    await waitFor(() => expect(budget.effectiveLimits['еда']).toBe('18000.00'));
    const update = requests.find(([url, init]) => decodeURIComponent(url).endsWith('/budgets/еда') && init?.method === 'PUT');
    expect(update).toBeDefined();
    expect(new Headers(update?.[1]?.headers).get('If-Match')).toBe('"0"');
    expect(JSON.parse(String(update?.[1]?.body))).toEqual({ scope: 'personal', amount: '18000', period: 'monthly' });

    const weeklyLimit = screen.getByLabelText('Лимит Еда за последние 7 дней');
    await user.clear(weeklyLimit);
    await user.type(weeklyLimit, '3000');
    await user.click(screen.getByRole('button', { name: 'Сохранить лимит Еда за последние 7 дней' }));
    await waitFor(() => expect(budget.rolling7FoodLimit).toBe('3000.00'));
    const weeklyUpdate = requests.find(([url, init]) => decodeURIComponent(url).endsWith('/budgets/еда')
      && init?.method === 'PUT' && JSON.parse(String(init.body)).period === 'rolling7');
    expect(weeklyUpdate).toBeDefined();

    await user.click(screen.getByRole('button', { name: 'Предложить ИИ-лимиты по истории' }));
    expect(await screen.findByText(/Предложенный общий лимит/)).toBeInTheDocument();
    expect(await screen.findByText('История расходов: 30 дн. Модель: test-model.')).toBeInTheDocument();
    expect(budget.familyTotalLimit).toBe('55000.00');
    const historyProposal = requests.find(([url, init]) => url.endsWith('/budget-proposals/history') && init?.method === 'POST');
    expect(historyProposal).toBeDefined();
    expect(historyProposal?.[1]?.body).toBeUndefined();
    await user.click(screen.getByRole('button', { name: 'Применить предложение' }));
    await waitFor(() => expect(budget.familyTotalLimit).toBe('70000.00'));

    await user.click(screen.getByRole('button', { name: 'Предложить лимиты по доходу' }));
    expect(await screen.findByText(/Предложенный общий лимит/)).toBeInTheDocument();
    expect(requests.some(([url, init]) => url.endsWith('/budget-proposals') && init?.method === 'POST')).toBe(true);
  });

  it('creates debt, records payment, adjusts balance and reads payoff forecast', async () => {
    const requests: Array<[string, RequestInit | undefined]> = [];
    let debt = {
      id: 'debt-1', tenantId: tenant.tenantId, name: 'Credit card', openingBalance: '1000.00',
      currentBalance: '1000.00', interestRate: '12.0000', minimumPayment: '100.00', status: 'open', version: 1,
      createdAt: '2026-10-01T10:00:00Z',
    };
    let created = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets') && !init?.method) return json({
        currency: 'RUB', month: '2026-10', familyLimits: {}, personalOverrides: {}, effectiveLimits: {}, monthlySpent: {}, limitStatus: {},
        familyVersions: {}, personalVersions: {}, familyTotalLimit: '55000.00', personalTotalOverride: null, effectiveTotalLimit: '55000.00',
        totalMonthlySpent: '0.00', totalLimitStatus: 'normal', familyTotalVersion: 0, personalTotalVersion: 0,
        rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null, effectiveRolling7FoodLimit: '0.00', rolling7FoodSpent: '0.00',
        rolling7FoodLimitStatus: 'disabled', familyRolling7FoodVersion: 0, personalRolling7FoodVersion: 0,
      });
      if (url.endsWith('/debts') && !init?.method) return json({ items: created ? [debt] : [] });
      if (url.endsWith('/debts') && init?.method === 'POST') {
        debt = { ...debt, name: JSON.parse(String(init.body)).name };
        created = true;
        return json(debt, 201);
      }
      if (url.endsWith('/debts/debt-1/payments') && init?.method === 'POST') {
        debt = { ...debt, currentBalance: '900.00', version: 2 };
        return json({ transactionId: 'tx-debt', balanceReduction: '100.00', debt });
      }
      if (url.endsWith('/debts/debt-1/balance') && init?.method === 'PUT') {
        debt = { ...debt, currentBalance: '850.00', version: 3 };
        return json(debt);
      }
      if (url.endsWith('/debts/debt-1/forecast')) return json({ debtId: 'debt-1', monthsToPayoff: 11, estimateBasis: 'fixed monthly estimate' });
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/debts']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Название долга'), 'Credit card');
    await user.type(screen.getByLabelText('Начальный остаток, ₽'), '1000');
    await user.type(screen.getByLabelText('Ставка, %'), '12');
    await user.type(screen.getByLabelText('Минимальный платёж, ₽'), '100');
    await user.click(screen.getByRole('button', { name: 'Создать долг' }));
    expect(await screen.findByRole('heading', { name: 'Credit card' })).toBeInTheDocument();

    await user.type(screen.getByLabelText('Платёж Credit card'), '100');
    await user.click(screen.getByRole('button', { name: 'Записать платёж Credit card' }));
    await waitFor(() => expect(debt.currentBalance).toBe('900.00'));
    await user.clear(screen.getByLabelText('Остаток Credit card'));
    await user.type(screen.getByLabelText('Остаток Credit card'), '850');
    await user.click(screen.getByRole('button', { name: 'Сохранить остаток Credit card' }));
    await waitFor(() => expect(debt.currentBalance).toBe('850.00'));
    await user.click(screen.getByRole('button', { name: 'Прогноз Credit card' }));
    expect(await screen.findByText(/11/)).toBeInTheDocument();
    expect(requests.some(([url, init]) => url.endsWith('/debts/debt-1/payments')
      && new Headers(init?.headers).get('If-Match') === '"1"')).toBe(true);
  });

  it('offers Keycloak login and switches login copy to English', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ authenticated: false, displayName: '' })));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    const login = await screen.findByRole('link', { name: 'Войти' });
    expect(login).toHaveAttribute('href', '/oauth2/authorization/keycloak');
    await user.click(screen.getByRole('button', { name: 'EN' }));
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('sends date, type and search filters to the server and saves member profile', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.type(screen.getByLabelText(/поиск/i), 'Lunch');
    await user.selectOptions(screen.getByLabelText('Все типы'), 'expense');
    await user.type(screen.getByLabelText('С даты'), '2026-10-01');
    await user.type(screen.getByLabelText('По дату'), '2026-10-31');
    await waitFor(() => {
      const listRequest = vi.mocked(fetch).mock.calls.map(([url]) => String(url)).reverse()
        .find((url) => url.includes('/transactions?') && url.includes('search=Lunch') && url.includes('type=expense'));
      expect(listRequest).toBeDefined();
      const filters = new URL(`http://test${listRequest}`).searchParams;
      expect(filters.get('type')).toBe('expense');
      expect(filters.get('from')).toBe('2026-10-01');
      expect(filters.get('to')).toBe('2026-10-31');
    });

    await user.click(screen.getByRole('link', { name: 'Мой профиль' }));
    await user.clear(await screen.findByLabelText('Ваше имя'));
    await user.type(screen.getByLabelText('Ваше имя'), 'Алекс');
    await user.type(screen.getByLabelText(/плановый доход/i), '90000');
    await user.click(screen.getByRole('button', { name: 'Сохранить профиль' }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(true));
    const update = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'PATCH');
    expect(JSON.parse(String(update?.[1]?.body))).toMatchObject({ displayName: 'Алекс', plannedIncome: 90000 });
  });

  it('lets workspace owners filter transaction history by member', async () => {
    const memberId = '6f973963-728f-4473-8485-089521f741ae';
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/members')) return json([
        { userId: 'owner-user-id', displayName: 'Alex', role: 'owner' },
        { userId: memberId, displayName: 'Taylor', role: 'member' },
      ]);
      if (url.includes('/transactions?')) {
        requests.push(url);
        const selectedMember = new URL(`http://test${url}`).searchParams.get('memberId') === memberId;
        const memberName = selectedMember ? 'Taylor' : 'Alex';
        return json({ items: [{
          id: selectedMember ? 'taylor-transaction' : 'owner-transaction', tenantId: tenant.tenantId,
          ownerUserId: selectedMember ? memberId : 'owner-user-id',
          type: 'expense', amount: '18.00', currency: 'RUB', categoryCode: 'food', subcategoryCode: null,
          description: `${memberName} lunch`, source: 'manual', occurredAt: '2026-10-01T10:00:00Z',
          accountId: null, status: 'posted', version: 1, createdAt: '2026-10-01T10:00:00Z', memberName,
        }], nextCursor: null });
      }
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    expect(await screen.findByText('Alex lunch')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Фильтр по участнику' }));
    await screen.findByRole('option', { name: 'Taylor' });
    await user.selectOptions(screen.getByLabelText('Участник операции'), memberId);
    expect(await screen.findByText('Taylor lunch')).toBeInTheDocument();
    await waitFor(() => expect(requests.some((url) => new URL(`http://test${url}`).searchParams.get('memberId') === memberId)).toBe(true));
    await user.click(screen.getByRole('button', { name: 'Изменить Taylor lunch' }));
    await user.selectOptions(await screen.findByLabelText('Пользователь операции'), 'owner-user-id');
    await user.click(screen.getByRole('button', { name: 'Обновить' }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'PATCH'
      && JSON.parse(String(init.body)).ownerUserId === 'owner-user-id')).toBe(true));
  });

  it('lets workspace owners assign a new transaction to a selected member', async () => {
    const memberId = '6f973963-728f-4473-8485-089521f741ae';
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/members')) return json([
        { userId: tenant.userId, displayName: 'Alex', role: 'owner' },
        { userId: memberId, displayName: 'Taylor', role: 'member' },
      ]);
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      if (url.endsWith('/transactions') && init?.method === 'POST') return json({ id: 'assigned-transaction' }, 201);
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.click(screen.getByRole('button', { name: 'Назначить участника' }));
    await user.selectOptions(await screen.findByLabelText('Пользователь операции'), memberId);
    await user.type(screen.getByLabelText(/сумма/i), '25.00');
    await user.type(screen.getByLabelText('Описание'), 'Семейный обед');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST'
      && JSON.parse(String(init.body)).ownerUserId === memberId)).toBe(true));
  });

  it('sends period, type and search filters to the transaction API', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.includes('/transactions?')) {
        requests.push(url);
        return json({ items: [], nextCursor: null });
      }
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/transactions']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await screen.findByRole('heading', { name: /дом/i });
    await user.type(screen.getByLabelText('Поиск по операциям'), 'coffee');
    await user.selectOptions(screen.getByLabelText('Все типы'), 'refund');
    await user.type(screen.getByLabelText('С даты'), '2026-10-01');
    await user.type(screen.getByLabelText('По дату'), '2026-10-31');
    await waitFor(() => expect(requests.some((url) => {
      const params = new URL(`http://test${url}`).searchParams;
      return params.get('search') === 'coffee' && params.get('type') === 'refund'
        && params.get('from') === '2026-10-01' && params.get('to') === '2026-10-31'
        && params.get('memberId') === 'all';
    })).toBe(true));
  });

  it('creates and displays a short-lived Telegram link code from the profile', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/profile']}>
      <QueryClientProvider client={client}><App /></QueryClientProvider>
    </MemoryRouter>);

    await user.click(await screen.findByRole('button', { name: 'Подключить Telegram' }));
    expect(await screen.findByText('ABCD-EFGH-JKLM-NPQR')).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls.some(([url, init]) =>
      String(url) === '/bff/me/telegram-link' && init?.method === 'POST'
        && new Headers(init.headers).get('X-XSRF-TOKEN') === 'csrf-test-token')).toBe(true);
  });

  it('supports backing up during onboarding, preserving the separate member name, and skipping income', async () => {
    let created = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json(created ? [tenant] : []);
      if (url === '/bff/tenants' && init?.method === 'POST') { created = true; return json(tenant, 201); }
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'started', timezone: 'UTC', currency: 'RUB' });
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: 'Начать настройку' }));
    await user.type(await screen.findByLabelText('Название пространства'), 'Дом');
    await user.type(screen.getByLabelText('Ваше имя'), 'Алекс');
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.click(screen.getByRole('button', { name: 'Назад' }));
    expect(screen.getByLabelText('Название пространства')).toHaveValue('Дом');
    expect(screen.getByLabelText('Ваше имя')).toHaveValue('Алекс');
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.click(screen.getByRole('button', { name: 'Пропустить доход сейчас' }));
    await user.click(screen.getByRole('button', { name: 'Создать пространство' }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true));
    const request = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(request?.[1]?.body))).toMatchObject({ displayName: 'Дом', memberDisplayName: 'Алекс', plannedIncome: null });
  });

  it('shows an income-based budget choice after setup without applying it automatically', async () => {
    let created = false;
    let applied = false;
    const budgets = {
      currency: 'RUB', month: '2026-10', familyLimits: {}, personalOverrides: {}, effectiveLimits: {},
      monthlySpent: {}, limitStatus: {}, totalMonthlySpent: '0.00', totalLimitStatus: 'disabled',
      familyVersions: {}, personalVersions: {}, familyTotalLimit: null, personalTotalOverride: null,
      effectiveTotalLimit: null, familyTotalVersion: 0, personalTotalVersion: 0,
      rolling7FoodLimit: '0.00', personalRolling7FoodOverride: null, effectiveRolling7FoodLimit: '0.00',
      rolling7FoodSpent: '0.00', rolling7FoodLimitStatus: 'disabled', familyRolling7FoodVersion: 0, personalRolling7FoodVersion: 0,
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json(created ? [tenant] : []);
      if (url === '/bff/tenants' && init?.method === 'POST') { created = true; return json(tenant, 201); }
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Алекс', plannedIncome: 120000, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets') && !init?.method) return json(budgets);
      if (url.endsWith('/budget-proposals') && init?.method === 'POST') return json({
        id: 'setup-proposal', monthlyIncome: '120000.00', totalLimit: '84000.00', limits: { 'еда': '30000.00' },
        baseVersions: {}, baseTotalVersion: 0, status: 'pending', createdAt: '2026-10-01T10:00:00Z',
        proposalSource: 'income', historyDays: 0, modelVersion: null, promptVersion: null,
      }, 201);
      if (url.endsWith('/budget-proposals/setup-proposal/apply') && init?.method === 'POST') {
        applied = true;
        return json(budgets);
      }
      if (url.includes('/transactions?')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.click(await screen.findByRole('button', { name: 'Начать настройку' }));
    await user.type(await screen.findByLabelText('Название пространства'), 'Дом');
    await user.type(screen.getByLabelText('Ваше имя'), 'Алекс');
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.type(await screen.findByLabelText('Плановый доход в месяц, ₽'), '120000');
    await user.click(screen.getByRole('button', { name: 'Создать пространство' }));

    const createRequest = vi.mocked(fetch).mock.calls.find(([url, init]) => String(url) === '/bff/tenants' && init?.method === 'POST');
    expect(JSON.parse(String(createRequest?.[1]?.body))).toMatchObject({ plannedIncome: 120000 });
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) =>
      String(url).endsWith('/budget-proposals') && init?.method === 'POST')).toBe(true));
    expect(await screen.findByText(/Предложенный общий лимит/)).toBeInTheDocument();
    expect(screen.getByText('84000.00 ₽')).toBeInTheDocument();
    expect(applied).toBe(false);
    expect(screen.getByRole('button', { name: 'Применить предложение' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Оставить текущие лимиты' }));
    expect(await screen.findByText('Обзор месяца')).toBeInTheDocument();
    expect(applied).toBe(false);
  });

  it('repeats setup through the existing profile and leaves transaction history untouched', async () => {
    let profile = { displayName: 'Alex', plannedIncome: null as number | null, onboardingState: 'complete' as const, timezone: 'Europe/Moscow', currency: 'RUB' as const };
    const existingTransaction = {
      id: 'history-1', tenantId: tenant.tenantId, type: 'expense', amount: '250.00', currency: 'RUB',
      categoryCode: 'food', subcategoryCode: null, description: 'Coffee', source: 'manual',
      occurredAt: '2026-10-01T08:00:00Z', accountId: null, status: 'posted', version: 1,
      createdAt: '2026-10-01T08:00:00Z',
    };
    const requests: Array<[string, RequestInit | undefined]> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push([url, init]);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/profile/me') && init?.method === 'PATCH') {
        profile = { ...profile, ...(JSON.parse(String(init.body)) as Partial<typeof profile>) };
        return json(profile);
      }
      if (url.endsWith('/profile/me')) return json(profile);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions?')) return json({ items: [existingTransaction], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/profile']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.click(await screen.findByRole('button', { name: 'Пройти настройку заново' }));
    await user.click(screen.getByRole('button', { name: 'Начать настройку' }));
    await user.clear(await screen.findByLabelText('Ваше имя'));
    await user.type(screen.getByLabelText('Ваше имя'), 'Алексей');
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.type(await screen.findByLabelText('Плановый доход в месяц, ₽'), '90000');
    await user.click(screen.getByRole('button', { name: 'Сохранить профиль' }));

    await waitFor(() => expect(profile).toMatchObject({ displayName: 'Алексей', plannedIncome: 90000 }));
    expect(requests.some(([url, init]) => url === '/bff/tenants' && init?.method === 'POST')).toBe(false);
    expect(requests.some(([url, init]) => url.includes('/transactions') && ['POST', 'PATCH', 'DELETE'].includes(init?.method ?? ''))).toBe(false);
    await user.click(screen.getByRole('link', { name: 'Операции' }));
    expect(await screen.findByText('Coffee')).toBeInTheDocument();
  });

  it('saves member-local digest schedule through the versioned browser API', async () => {
    const preferences = {
      timezone: 'Europe/Moscow', telegramLinked: true, language: 'ru', dailyEnabled: true,
      dailyLocalTime: '21:00', weeklyEnabled: true, weeklyDayOfWeek: 7, weeklyLocalTime: '19:00',
      quietHoursStart: null, quietHoursEnd: null, version: 0,
    };
    let saved: Partial<typeof preferences> = {};
    let saveHeaders: Headers | undefined;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null,
        onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/notification-preferences') && init?.method === 'PATCH') {
        saveHeaders = new Headers(init.headers);
        saved = JSON.parse(String(init.body));
        return json({ ...preferences, ...saved, version: 1 });
      }
      if (url.endsWith('/notification-preferences')) return json(preferences);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/profile']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await user.selectOptions(await screen.findByLabelText('Язык дайджеста'), 'en');
    await user.clear(screen.getByLabelText('Время ежедневной сводки'));
    await user.type(screen.getByLabelText('Время ежедневной сводки'), '08:30');
    await user.selectOptions(screen.getByLabelText('День недельного дайджеста'), '1');
    await user.type(screen.getByLabelText('Начало тихих часов'), '22:00');
    await user.type(screen.getByLabelText('Конец тихих часов'), '07:00');
    await user.click(screen.getByRole('button', { name: 'Сохранить расписание' }));

    await waitFor(() => expect(saved).toEqual({
      language: 'en', dailyEnabled: true, dailyLocalTime: '08:30', weeklyEnabled: true,
      weeklyDayOfWeek: 1, weeklyLocalTime: '19:00', quietHoursStart: '22:00', quietHoursEnd: '07:00',
    }));
    expect(saveHeaders?.get('If-Match')).toBe('"0"');
    expect(saveHeaders?.get('X-XSRF-TOKEN')).toBe('csrf-test-token');
  });

  it('keeps viewer transaction history read-only', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/csrf') return json({ token: 'csrf-test-token' });
      if (url === '/bff/me/tenants') return json([{ ...tenant, role: 'viewer' }]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me')) return json({ displayName: 'Alex', plannedIncome: null, onboardingState: 'complete', timezone: 'Europe/Moscow', currency: 'RUB' });
      if (url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions')) return json({ items: [{
        id: 'read-only-transaction', tenantId: tenant.tenantId, type: 'expense', amount: '25.00', currency: 'RUB',
        categoryCode: 'food', subcategoryCode: null, description: 'Coffee', occurredAt: '2026-10-01T10:00:00Z',
        accountId: null, status: 'posted', version: 1, createdAt: '2026-10-01T10:00:00Z',
      }], nextCursor: null });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter initialEntries={['/transactions']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    await screen.findByText('Coffee');
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Изменить Coffee' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Повторить Coffee' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отменить Coffee' })).not.toBeInTheDocument();
  });

  it('opens selected member transactions and marks current route active', async () => {
    const transactionUrls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me') || url.endsWith('/notification-preferences') || url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.endsWith('/members')) return json([
        { userId: tenant.userId, displayName: 'Alex', role: 'owner' },
        { userId: 'member-user-id', displayName: 'Taylor', role: 'member' },
      ]);
      if (url.includes('/transactions')) { transactionUrls.push(url); return json({ items: [], nextCursor: null }); }
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/family']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    for (const label of ['Пространство', 'Операции', 'Чеки', 'Бюджеты', 'Пользователи', 'Товары', 'Отчёты', 'Экспорт CSV']) {
      expect(await screen.findByRole('link', { name: label })).toBeInTheDocument();
    }
    await user.click(await screen.findByRole('link', { name: 'Показать операции: Taylor' }));
    await waitFor(() => expect(transactionUrls.at(-1)).toContain('memberId=member-user-id'));
    expect(screen.getByRole('link', { name: 'Операции' })).toHaveClass('active');
  });

  it('refreshes queries by button and F5 without browser reload', async () => {
    let summaryCalls = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/summary')) { summaryCalls += 1; return json(dashboardSummary()); }
      if (url.endsWith('/profile/me') || url.endsWith('/notification-preferences') || url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      if (url.includes('/transactions')) return json({ items: [], nextCursor: null });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/dashboard']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    const refresh = await screen.findByRole('button', { name: 'Обновить данные' });
    await waitFor(() => expect(summaryCalls).toBe(1));
    await user.click(refresh);
    await waitFor(() => expect(summaryCalls).toBe(2));
    const event = new KeyboardEvent('keydown', { key: 'F5', bubbles: true, cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    await waitFor(() => expect(summaryCalls).toBe(3));
  });

  it('opens the localized service status route and reads only the tenant BFF', async () => {
    const healthUrls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/bff/session') return json({ authenticated: true, displayName: 'Alex' });
      if (url === '/bff/me/tenants') return json([tenant]);
      if (url.endsWith('/health')) {
        healthUrls.push(url);
        return json({ capabilities: {
          localAi: { status: 'available', diagnosticCode: null },
          receiptVision: { status: 'disabled', diagnosticCode: 'VISION_DISABLED' },
          receiptOcr: { status: 'unavailable', diagnosticCode: 'TESSERACT_MISSING' },
        } });
      }
      if (url.endsWith('/summary')) return json(dashboardSummary());
      if (url.endsWith('/profile/me') || url.endsWith('/notification-preferences') || url.endsWith('/budgets')) return json({});
      if (url.endsWith('/debts')) return json({ items: [] });
      throw new Error(`Unexpected request ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={['/health']}><QueryClientProvider client={client}><App /></QueryClientProvider></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Состояние сервисов' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Состояние сервисов' })).toHaveClass('active');
    expect(healthUrls).toEqual([`/bff/tenants/${tenant.tenantId}/health`]);
    await user.click(screen.getByRole('button', { name: 'Обновить статус' }));
    await waitFor(() => expect(healthUrls).toHaveLength(2));
  });
});

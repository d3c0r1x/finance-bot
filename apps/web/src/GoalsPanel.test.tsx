import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GoalsPanel } from './GoalsPanel';
import { api } from './api';

vi.mock('./api', () => ({ api: {
  getGoals: vi.fn(), updateGoalUnit: vi.fn(), acceptGoal: vi.fn(), cancelGoal: vi.fn(),
} }));

const tenantId = '6c5e6ee6-15b7-4923-9547-844060f73b57';
const goal = { id: 'c80fc08b-8f86-4956-bdca-1e2646f3f514', key: 'chips', scope: 'product', name: 'Чипсы',
  unit: 'count', monthlyRate: '4.00', countTarget: 2, monthlySpend: null, monthlyLimit: null,
  evidenceCount: 4, inputWatermark: '17', acceptedAt: '2026-10-07T10:00:00Z',
  endsAt: '2026-11-06T10:00:00Z', status: 'active', version: 1 };
const overview = { unit: 'count', active: null, inputWatermark: '17',
  candidates: [{ key: 'chips', productKey: 'chips', name: 'Чипсы', unit: 'count', monthlyRate: '4.00',
    countTarget: 2, monthlySpend: null, monthlyLimit: null, estimatedReduction: null, purchaseCount: 4, evidenceCount: 4 }],
  groups: [{ key: 'cat:сладкое', productKey: null, name: 'Сладкое', unit: 'count', monthlyRate: '3.00',
    countTarget: 1, monthlySpend: null, monthlyLimit: null, estimatedReduction: null, purchaseCount: 3, evidenceCount: 5 }],
  skipped: [{ productKey: 'juice', name: 'Сок', monthlySpend: null, reasonCode: 'missing_amounts' }],
  activeProgress: null, history: [] };

function mount(canWrite = true, language: 'ru' | 'en' = 'ru') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <GoalsPanel tenantId={tenantId} language={language} canWrite={canWrite} />
  </QueryClientProvider>);
}

beforeEach(() => vi.clearAllMocks());
afterEach(() => cleanup());

describe('GoalsPanel', () => {
  it('shows current candidates without invented money and accepts only after an explicit click', async () => {
    vi.mocked(api.getGoals).mockResolvedValueOnce(overview as never).mockResolvedValue({ ...overview, active: goal } as never);
    vi.mocked(api.acceptGoal).mockResolvedValue(goal as never);
    vi.mocked(api.cancelGoal).mockResolvedValue(goal as never);
    mount();
    expect(await screen.findByRole('heading', { name: 'Цель на месяц' })).toBeInTheDocument();
    expect(screen.getByText('Не хватает сумм в чеках')).toBeInTheDocument();
    expect(screen.getByText('Чипсы')).toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    expect(api.acceptGoal).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Поставить цель: Чипсы' }));
    await waitFor(() => expect(api.acceptGoal).toHaveBeenCalledWith(tenantId, {
      candidateKey: 'chips', inputWatermark: '17',
    }));
    expect(await screen.findByText(/Цель активна/)).toBeInTheDocument();
    expect(screen.getAllByText(/2 покупки в месяц/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Осталось дней: 30/)).toBeInTheDocument();
  });

  it('lets a viewer inspect proposals and active terms but hides every write control', async () => {
    vi.mocked(api.getGoals).mockResolvedValue({ ...overview, active: goal } as never);
    mount(false);
    expect((await screen.findAllByText('Чипсы')).length).toBeGreaterThan(0);
    expect(screen.getByText(/Цель активна/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Поставить цель/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Отменить цель/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('saves a per-member measure change and asks before cancelling the active goal', async () => {
    vi.mocked(api.getGoals).mockResolvedValue({ ...overview, active: goal } as never);
    vi.mocked(api.updateGoalUnit).mockResolvedValue({ unit: 'sum' });
    vi.mocked(api.cancelGoal).mockResolvedValue({ ...goal, status: 'cancelled' } as never);
    vi.stubGlobal('confirm', vi.fn(() => true));
    mount();
    await screen.findByRole('heading', { name: 'Цель на месяц' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Формат цели' }), { target: { value: 'sum' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить формат' }));
    await waitFor(() => expect(api.updateGoalUnit).toHaveBeenCalledWith(tenantId, 'sum'));
    fireEvent.click(screen.getByRole('button', { name: 'Отменить цель' }));
    await waitFor(() => expect(api.cancelGoal).toHaveBeenCalledWith(tenantId, goal.id));
    expect(window.confirm).toHaveBeenCalled();
  });

  it('shows unavailable state and refreshes after stale candidate errors', async () => {
    vi.mocked(api.getGoals).mockRejectedValueOnce(new Error('Кандидат устарел')).mockResolvedValue(overview as never);
    mount(true, 'en');
    expect(await screen.findByRole('alert')).toHaveTextContent('Data changed');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Monthly goal' })).toBeInTheDocument();
    expect(screen.getByText('Receipt amounts are missing')).toBeInTheDocument();
  });

  it('refreshes the proposal after Core rejects its watermark as stale', async () => {
    vi.mocked(api.getGoals).mockResolvedValue(overview as never);
    vi.mocked(api.acceptGoal).mockRejectedValue(new Error('candidate is stale'));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'Поставить цель: Чипсы' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Данные изменились');
    fireEvent.click(screen.getByRole('button', { name: 'Обновить предложения' }));
    await waitFor(() => expect(api.getGoals).toHaveBeenCalledTimes(2));
  });

  it('shows confirmed-purchase progress and completed outcomes while keeping candidates available', async () => {
    vi.mocked(api.getGoals).mockResolvedValue({ ...overview, active: goal,
      activeProgress: { algorithmVersion: 'goal-progress-f45.v1', inputWatermark: '21', unit: 'count',
        bought: 1, spent: '40.00', amountsUnknown: false, over: false, met: true, finished: false,
        daysLeft: 18, windowStart: goal.acceptedAt, windowEnd: goal.endsAt },
      history: [{ id: 'e1e1e1e1-e1e1-41e1-81e1-e1e1e1e1e1e1', goalId: 'c80fc08b-8f86-4956-bdca-1e2646f3f514',
        key: 'coffee', name: 'Кофе', scope: 'product', unit: 'count', countTarget: 2, monthlyLimit: null,
        bought: 2, spent: '500.00', met: true, acceptedAt: '2026-09-01T10:00:00Z',
        completedAt: '2026-10-01T10:00:00Z', origin: 'completed' }],
    } as never);
    mount(false);
    expect(await screen.findByText(/Подтверждённые покупки: 1 из 2/)).toBeInTheDocument();
    expect(screen.getByText(/Потрачено за цель: 40/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'История целей' })).toBeInTheDocument();
    expect(screen.getByText('Кофе')).toBeInTheDocument();
    expect(screen.getByText('Выполнена')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Поставить цель: Чипсы' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Отменить цель/ })).not.toBeInTheDocument();
  });

  it('keeps unknown sum progress and outcome indeterminate instead of showing zero', async () => {
    vi.mocked(api.getGoals).mockResolvedValue({ ...overview,
      active: { ...goal, unit: 'sum', countTarget: 0, monthlyLimit: '300.00' },
      activeProgress: { algorithmVersion: 'goal-progress-f45.v1', inputWatermark: '22', unit: 'sum',
        bought: 1, spent: null, amountsUnknown: true, over: null, met: null, finished: false,
        daysLeft: 18, windowStart: goal.acceptedAt, windowEnd: goal.endsAt },
      history: [{ id: 'e2e2e2e2-e2e2-42e2-82e2-e2e2e2e2e2e2', goalId: null, key: 'tea', name: 'Чай',
        scope: 'product', unit: 'sum', countTarget: 0, monthlyLimit: '250.00', bought: 1, spent: null,
        met: null, acceptedAt: '2026-08-01T10:00:00Z', completedAt: '2026-08-31T10:00:00Z', origin: 'legacy' }],
    } as never);
    mount();
    expect(await screen.findByText(/Суммы чеков неизвестны/)).toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Итог по деньгам неизвестен').length).toBeGreaterThan(0);
  });

  it('shows the next candidate after completion alongside the retained outcome', async () => {
    vi.mocked(api.getGoals).mockResolvedValue({ ...overview, active: null,
      history: [{ id: 'e3e3e3e3-e3e3-43e3-83e3-e3e3e3e3e3e3', goalId: 'c80fc08b-8f86-4956-bdca-1e2646f3f514',
        key: 'chips', name: 'Чипсы', scope: 'product', unit: 'count', countTarget: 2, monthlyLimit: null,
        bought: 1, spent: null, met: false, acceptedAt: '2026-09-01T10:00:00Z',
        completedAt: '2026-10-01T10:00:00Z', origin: 'completed' }],
    } as never);
    mount();
    expect(await screen.findByRole('heading', { name: 'История целей' })).toBeInTheDocument();
    expect(screen.getByText('Не выполнена')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Поставить цель: Чипсы' })).toBeInTheDocument();
  });
});

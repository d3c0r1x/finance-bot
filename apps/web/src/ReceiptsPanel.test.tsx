import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ReceiptsPanel } from './ReceiptsPanel';

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const duplicateId = 'a2f9d980-6811-49c6-bef4-1e497c095b04';
const receiptId = 'e1482f0d-c06f-42d9-87ad-fd8e32bd587d';
let priceHistoryResponse: Record<string, unknown>;

describe('receipt duplicate review', () => {
  beforeEach(() => {
    priceHistoryResponse = {
      algorithmVersion: 'price-projection.v1', productName: 'Tea', hasBaseline: true,
      currentUnitPrice: '1200.000000', baselineUnitPrice: '864.000000', change: '336.000000',
      relative: '0.388889', signal: true, direction: 'up', priorPurchases: 1,
      history: [
        { receiptId: 'prior-receipt', itemId: 'prior-item', purchasedAt: '2026-09-01T10:00:00Z',
          merchant: 'Old Market', name: 'Tea', unitPrice: '864.000000', current: false },
        { receiptId, itemId: 'item-1', purchasedAt: '2026-10-01T10:10:00Z',
          merchant: 'Market', name: 'Tea', unitPrice: '1200.000000', current: true },
      ],
    };
    const item = { id: 'item-1', name: 'Tea', quantity: '1', unitPrice: '1200.00', lineSum: '1200.00', version: 1 };
    let receipt: Record<string, unknown> = {
      id: receiptId, tenantId, documentId: null, state: 'draft', version: 1,
      transactionId: null, currency: 'RUB', cashTotal: '1200.00', itemsTotal: '1200.00',
      merchant: 'Market', receiptDate: '2026-10-01', duplicateDecision: 'unknown', duplicateOfReceiptId: null,
      items: [item], itemCount: 1, createdAt: '2026-10-01T10:10:00Z',
    };
    const candidates = [{ id: duplicateId, cashTotal: '1200.00', merchant: 'Market', createdAt: '2026-10-01T10:05:00Z' }];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST') return json(receipt, 201);
      if (url.includes(`/bff/tenants/${tenantId}/products/price-history?`)) return json(priceHistoryResponse);
      if (url === `/bff/tenants/${tenantId}/receipts/${receiptId}/duplicate-candidates`) {
        return json({ receiptId, decision: receipt.duplicateDecision, candidates });
      }
      if (url.endsWith('/items?page=1')) return json({ items: [item], page: 1, totalItems: 1, hasMore: false });
      if (url.endsWith('/duplicate-decision') && init?.method === 'PUT') {
        const selection = JSON.parse(String(init.body)) as { decision: string; duplicateReceiptId: string | null };
        receipt = { ...receipt, duplicateDecision: selection.decision, duplicateOfReceiptId: selection.duplicateReceiptId,
          version: Number(receipt.version) + 1 };
        return json(receipt);
      }
      if (url.endsWith('/confirm') && init?.method === 'POST') {
        receipt = { ...receipt, state: 'confirmed', transactionId: 'posted-expense-id', version: Number(receipt.version) + 1 };
        return json(receipt);
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('requires a user decision for a candidate and confirms an independent receipt once', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['goals', tenantId], { inputWatermark: '1' });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '1200.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Tea');
    await user.type(screen.getByLabelText('Магазин'), 'Market');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));

    const confirm = await screen.findByRole('button', { name: 'Подтвердить расход' });
    expect(confirm).toBeDisabled();
    await user.click(await screen.findByRole('button', { name: /Это дубль/ }));
    expect(confirm).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Это отдельная покупка' }));
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText(/posted-expense-id/)).toBeInTheDocument();
    expect(client.getQueryState(['goals', tenantId])?.isInvalidated).toBe(true);
    const requests = vi.mocked(fetch).mock.calls;
    const createRequest = requests.find(([url, init]) => String(url) === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST');
    expect(new Headers(createRequest?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    const decisions = requests.filter(([url, init]) => String(url).endsWith('/duplicate-decision') && init?.method === 'PUT');
    expect(decisions.map(([, init]) => JSON.parse(String(init?.body)))).toEqual([
      { decision: 'duplicate', duplicateReceiptId: duplicateId },
      { decision: 'independent', duplicateReceiptId: null },
    ]);
    const confirmation = requests.find(([url, init]) => String(url).endsWith('/confirm') && init?.method === 'POST');
    expect(new Headers(confirmation?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    expect(new Headers(confirmation?.[1]?.headers).get('If-Match')).toBe('"3"');
    expect(new Headers(confirmation?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('csrf-token');
  });

  it('loads Core price history after confirmation and shows the prior median and real purchase points', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '1200.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Tea');
    await user.type(screen.getByLabelText('Магазин'), 'Market');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    expect(screen.queryByRole('button', { name: 'Сравнить цену Tea' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Это отдельная покупка' }));
    await user.click(await screen.findByRole('button', { name: 'Подтвердить расход' }));

    await user.click(await screen.findByRole('button', { name: 'Сравнить цену Tea' }));
    expect(await screen.findByText(/Обычно: 864,00/)).toBeInTheDocument();
    expect(screen.getByText(/Подорожание: \+38,9/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'История цены: Tea' })).toBeInTheDocument();
    expect(screen.getByText(/Old Market/)).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls.some(([url]) =>
      String(url) === `/bff/tenants/${tenantId}/products/price-history?receiptId=${receiptId}&itemId=item-1`)).toBe(true);
  });

  it('shows no invented baseline or chart when Core has no prior purchase', async () => {
    priceHistoryResponse = {
      algorithmVersion: 'price-projection.v1', productName: 'Tea', hasBaseline: false,
      currentUnitPrice: '1200.000000', baselineUnitPrice: null, change: null, relative: null,
      signal: false, direction: null, priorPurchases: 0,
      history: [{ receiptId, itemId: 'item-1', purchasedAt: '2026-10-01T10:10:00Z',
        merchant: 'Market', name: 'Tea', unitPrice: '1200.000000', current: true }],
    };
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '1200.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Tea');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    await user.click(await screen.findByRole('button', { name: 'Это отдельная покупка' }));
    await user.click(await screen.findByRole('button', { name: 'Подтвердить расход' }));
    await user.click(await screen.findByRole('button', { name: 'Сравнить цену Tea' }));

    expect(await screen.findByText('Пока нет сопоставимых покупок.')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'История цены: Tea' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Обычно/)).not.toBeInTheDocument();
  });

  it('keeps the cash total fixed after item correction until explicit synchronization', async () => {
    let receipt: Record<string, unknown> = {
      id: receiptId, tenantId, documentId: null, state: 'draft', version: 1, transactionId: null,
      currency: 'RUB', cashTotal: '1200.00', itemsTotal: '1200.00', merchant: 'Market',
      receiptDate: '2026-10-01', duplicateDecision: 'unknown', duplicateOfReceiptId: null,
      itemCount: 1, createdAt: '2026-10-01T10:10:00Z',
    };
    let item = { id: 'item-1', name: 'Tea', quantity: '1', unitPrice: '1200.00', lineSum: '1200.00', version: 1 };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST') {
        return json({ ...receipt, items: [item] }, 201);
      }
      if (url.endsWith('/items?page=1')) return json({ items: [item], page: 1, totalItems: 1, hasMore: false });
      if (url.endsWith('/duplicate-candidates')) return json({ receiptId, decision: 'unknown', candidates: [] });
      if (url.endsWith('/items/item-1') && init?.method === 'PATCH') {
        item = { ...item, ...JSON.parse(String(init.body)), version: item.version + 1 };
        receipt = { ...receipt, itemsTotal: '900.00', state: 'review_required', version: Number(receipt.version) + 1 };
        return json({ ...receipt, items: [item] });
      }
      if (url.endsWith('/sync-total') && init?.method === 'POST') {
        receipt = { ...receipt, cashTotal: '900.00', state: 'draft', version: Number(receipt.version) + 1 };
        return json({ ...receipt, items: [item] });
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '1200.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Tea');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    await user.click(await screen.findByRole('button', { name: 'Изменить Tea' }));
    await user.clear(screen.getAllByLabelText('Сумма позиции')[0]);
    await user.type(screen.getAllByLabelText('Сумма позиции')[0], '900.00');
    await user.click(screen.getByRole('button', { name: 'Сохранить позицию' }));

    expect(await screen.findByText(/Итог кассы: 1200.00 ₽/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Синхронизировать итог' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Синхронизировать итог' }));
    expect(await screen.findByText(/Итог кассы: 900.00 ₽/)).toBeInTheDocument();
  });

  it('pages receipt items in groups of eight', async () => {
    const pageOne = Array.from({ length: 8 }, (_, index) => ({
      id: `item-${index + 1}`, name: `Item ${index + 1}`, quantity: '1', unitPrice: '1.00', lineSum: '1.00', version: 1,
    }));
    const pageTwo = [{ id: 'item-9', name: 'Item 9', quantity: '1', unitPrice: '1.00', lineSum: '1.00', version: 1 }];
    const receipt: Record<string, unknown> = {
      id: receiptId, tenantId, documentId: null, state: 'draft', version: 1, transactionId: null,
      currency: 'RUB', cashTotal: '9.00', itemsTotal: '9.00', merchant: 'Market', receiptDate: '2026-10-01',
      duplicateDecision: 'unknown', duplicateOfReceiptId: null, items: pageOne, itemCount: 9,
      createdAt: '2026-10-01T10:10:00Z',
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST') return json(receipt, 201);
      if (url.endsWith('/items?page=1')) return json({ items: pageOne, page: 1, totalItems: 9, hasMore: true });
      if (url.endsWith('/items?page=2')) return json({ items: pageTwo, page: 2, totalItems: 9, hasMore: false });
      if (url.endsWith('/duplicate-candidates')) return json({ receiptId, decision: 'unknown', candidates: [] });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '9.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Item 1');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    expect(await screen.findByText('Item 1 · 1.00 ₽')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Далее' }));
    expect(await screen.findByText('Item 9 · 1.00 ₽')).toBeInTheDocument();
    expect(screen.getByText('Страница 2')).toBeInTheDocument();
  });

  it('adds and removes a receipt item without changing the cash total', async () => {
    let receipt: Record<string, unknown> = {
      id: receiptId, tenantId, documentId: null, state: 'draft', version: 1, transactionId: null,
      currency: 'RUB', cashTotal: '1200.00', itemsTotal: '1200.00', merchant: 'Market', receiptDate: '2026-10-01',
      duplicateDecision: 'unknown', duplicateOfReceiptId: null, itemCount: 1, createdAt: '2026-10-01T10:10:00Z',
    };
    let items = [{ id: 'item-1', name: 'Tea', quantity: '1', unitPrice: '1200.00', lineSum: '1200.00', version: 1 }];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST') return json({
        ...receipt, items, itemCount: items.length,
      }, 201);
      if (url.endsWith('/items?page=1')) return json({ items, page: 1, totalItems: items.length, hasMore: false });
      if (url.endsWith('/duplicate-candidates')) return json({ receiptId, decision: 'unknown', candidates: [] });
      if (url.endsWith('/items') && init?.method === 'POST') {
        const inputItem = JSON.parse(String(init.body)) as { name: string; quantity: string; unitPrice: string; lineSum: string };
        items = [...items, { ...inputItem, id: 'item-2', version: 1 }];
        receipt = { ...receipt, itemCount: items.length, itemsTotal: '1500.00', state: 'review_required', version: Number(receipt.version) + 1 };
        return json({ ...receipt, items });
      }
      if (url.endsWith('/items/item-2') && init?.method === 'DELETE') {
        items = items.filter((item) => item.id !== 'item-2');
        receipt = { ...receipt, itemCount: items.length, itemsTotal: '1200.00', state: 'draft', version: Number(receipt.version) + 1 };
        return json({ ...receipt, items });
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '1200.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Tea');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    await user.type(await screen.findByLabelText('Новая позиция'), 'Bread');
    await user.type(screen.getByLabelText('Цена за единицу'), '300.00');
    await user.type(screen.getByLabelText('Сумма позиции'), '300.00');
    await user.click(screen.getByRole('button', { name: 'Добавить позицию' }));
    expect(await screen.findByText('Bread · 300.00 ₽')).toBeInTheDocument();
    expect(screen.getByText(/Итог кассы: 1200.00 ₽/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Удалить Bread' }));
    await waitFor(() => expect(screen.queryByText('Bread · 300.00 ₽')).not.toBeInTheDocument());
    expect(screen.getByText(/Итог кассы: 1200.00 ₽/)).toBeInTheDocument();
  });

  it('saves receipt category, reviews basket provenance, and allows then revokes a disputed product', async () => {
    const item: { id: string; name: string; quantity: string; unitPrice: string; lineSum: string;
      productKey: string; provenance: string; confidence: number | null; categoryCode: string | null;
      verdict: string | null; advice: string | null; reviewReason: string | null; reviewAction: string | null;
      verdictSource: string; reviewProvider: string | null; reviewModelVersion: string | null;
      reviewPromptVersion: string | null; reviewAlgorithmVersion: string; version: number } = {
      id: 'item-1', name: 'Sweet drink', quantity: '1', unitPrice: '120.00', lineSum: '120.00',
      productKey: 'sweet-drink', provenance: 'manual', confidence: null, categoryCode: null,
      verdict: null, advice: null, reviewReason: null, reviewAction: null, verdictSource: 'unknown',
      reviewProvider: null, reviewModelVersion: null, reviewPromptVersion: null, reviewAlgorithmVersion: 'v1', version: 1 };
    let receipt: Record<string, unknown> = {
      id: receiptId, tenantId, documentId: null, state: 'draft', version: 1, transactionId: null,
      currency: 'RUB', cashTotal: '120.00', itemsTotal: '120.00', merchant: 'Market', receiptDate: '2026-10-01',
      categoryCode: null, categorySource: 'unknown', categoryAlgorithmVersion: 'v1', alcoholShare: null,
      leisureShare: null, leisure: false, duplicateDecision: 'unknown', duplicateOfReceiptId: null,
      items: [item], itemCount: 1, createdAt: '2026-10-01T10:10:00Z',
    };
    let allowed: string[] = [];
    let reviewedItem = item;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST') return json(receipt, 201);
      if (url.endsWith('/duplicate-candidates')) return json({ receiptId, decision: 'unknown', candidates: [] });
      if (url.endsWith('/items?page=1')) return json({ items: [reviewedItem], page: 1, totalItems: 1, hasMore: false });
      if (url.endsWith('/disputed-items?page=1')) return json({ items: [reviewedItem], page: 1, totalItems: 1, hasMore: false });
      if (url.endsWith('/repeat-warnings')) return json({ warnings: [
        { itemId: 'item-1', name: 'Sweet drink', productKey: 'sweet-drink', verdict: 'unnecessary',
          title: 'Покупка повторяется', count: 3, lastSum: '110.00', advice: 'Проверьте привычку' },
      ] });
      if (url === `/bff/tenants/${tenantId}/products/decisions`) return json({ productKeys: allowed });
      if (url.endsWith('/category') && init?.method === 'PATCH') {
        receipt = { ...receipt, categoryCode: JSON.parse(String(init.body)).categoryCode, categorySource: 'human',
          version: Number(receipt.version) + 1 };
        return json(receipt);
      }
      if (url.endsWith('/basket-review') && init?.method === 'POST') {
        reviewedItem = { ...item, verdict: 'unnecessary', advice: 'Проверьте привычку', reviewReason: 'Сладкий напиток',
          reviewAction: 'allow_or_skip', verdictSource: 'model', reviewProvider: 'local', reviewModelVersion: 'm1',
          reviewPromptVersion: 'p1', reviewAlgorithmVersion: 'basket-v1' };
        receipt = { ...receipt, state: 'review_required', version: Number(receipt.version) + 1 };
        return json(receipt);
      }
      if (url.endsWith('/products/sweet-drink/decision') && init?.method === 'PUT') {
        allowed = ['sweet-drink'];
        return json({ productKey: 'sweet-drink', decision: 'allowed', version: 1, updatedAt: '2026-10-01T10:00:00Z' });
      }
      if (url.endsWith('/products/sweet-drink/decision') && init?.method === 'DELETE') {
        allowed = [];
        return new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language="ru" canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.type(await screen.findByLabelText('Кассовый итог'), '120.00');
    await user.type(screen.getByLabelText('Товар в чеке'), 'Sweet drink');
    await user.click(screen.getByRole('button', { name: 'Создать черновик чека' }));
    await user.selectOptions(await screen.findByLabelText('Категория чека'), 'еда');
    await user.click(screen.getByRole('button', { name: 'Сохранить категорию' }));
    expect(await screen.findByText(/Источник категории: вручную/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Разобрать корзину' }));
    expect(await screen.findByText(/Причина: Сладкий напиток/)).toBeInTheDocument();
    expect(screen.getAllByText(/Совет: Проверьте привычку/).length).toBeGreaterThan(0);
    expect(await screen.findByText(/Повтор: 3/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Разрешить товар Sweet drink' }));
    expect(await screen.findByRole('button', { name: 'Отозвать разрешение Sweet drink' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Отозвать разрешение Sweet drink' }));
    expect(await screen.findByRole('button', { name: 'Разрешить товар Sweet drink' })).toBeInTheDocument();

    const requests = vi.mocked(fetch).mock.calls;
    const category = requests.find(([url, init]) => String(url).endsWith('/category') && init?.method === 'PATCH');
    expect(JSON.parse(String(category?.[1]?.body))).toEqual({ categoryCode: 'еда' });
    expect(new Headers(category?.[1]?.headers).get('If-Match')).toBe('"1"');
    const basket = requests.find(([url, init]) => String(url).endsWith('/basket-review') && init?.method === 'POST');
    expect(new Headers(basket?.[1]?.headers).get('If-Match')).toBe('"2"');
    expect(new Headers(basket?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('csrf-token');
  });

  it.each([
    { language: 'ru' as const, upload: 'Загрузить фото чека', progress: 'Чек готов', reading: 'Распознанный текст', vision: 'Модель Vision: qwen3-vl:4b', fallback: 'Резервная модель Vision: vision_model_unavailable', reconciliation: 'Сверка: нужно проверить расхождения', suggestions: 'Подсказки добора из OCR', unverified: 'Данные модели — проверьте перед использованием', visionDate: 'Дата в чтении Vision: 2026-10-04', categoryUnknown: 'Источник категории: не определён · receipt-category.v1' },
    { language: 'en' as const, upload: 'Upload receipt photo', progress: 'Receipt ready', reading: 'Recognized text', vision: 'Vision model: qwen3-vl:4b', fallback: 'Vision model fallback: vision_model_unavailable', reconciliation: 'Reconciliation: review differences', suggestions: 'OCR items to review', unverified: 'Model reading — verify before use', visionDate: 'Vision reading date: 2026-10-04', categoryUnknown: 'Category source: unknown · receipt-category.v1' },
  ])('uploads a receipt photo and opens OCR review in $language', async ({ language, upload, progress, reading, vision, fallback, reconciliation, suggestions, unverified, visionDate, categoryUnknown }) => {
    const jobId = 'd6051931-5f18-4961-8959-987237a2c08a';
    const documentId = 'e4c16bab-57f5-4a8e-9bf1-c18757da26be';
    const photoReceipt = {
      id: receiptId, tenantId, documentId, state: 'review_required', version: 1, transactionId: null,
      currency: 'RUB', cashTotal: null, itemsTotal: '0.00', merchant: null, receiptDate: null,
      selectedReader: 'ocr', categoryCode: null, categorySource: 'unknown', categoryAlgorithmVersion: 'receipt-category.v1',
      alcoholShare: null, leisureShare: null, leisure: false, duplicateDecision: 'unknown', duplicateOfReceiptId: null,
      items: [], itemCount: 0, createdAt: '2026-10-04T09:00:00Z',
    };
    const queuedJob = {
      id: jobId, tenantId, documentId, state: 'queued', stage: 'queued', progressPercent: 0, attemptCount: 0,
      retryable: false, errorCode: null, receiptId: null, createdAt: '2026-10-04T09:00:00Z', updatedAt: '2026-10-04T09:00:00Z',
    };
    const completeJob = { ...queuedJob, state: 'completed', stage: 'complete', progressPercent: 100, receiptId };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/bff/csrf') return json({ token: 'csrf-token' });
      if (url === `/bff/tenants/${tenantId}/receipts/photo-jobs` && init?.method === 'POST') return json(queuedJob, 202);
      if (url === `/bff/tenants/${tenantId}/receipt-jobs/${jobId}`) return json(completeJob);
      if (url === `/bff/tenants/${tenantId}/receipts/${receiptId}`) return json(photoReceipt);
      if (url === `/bff/tenants/${tenantId}/receipts/${receiptId}/readings`) {
        return json({ text: 'TOTAL 120.00', words: [{ text: 'TOTAL', confidence: 96, box: { x: 1, y: 2, width: 30, height: 9 } }],
          provider: 'tesseract', modelVersion: 'tesseract-5.3.0', promptVersion: 'tesseract-ocr.v2', confidence: 0.96,
          ocrTotal: '100.00', ocrItems: [
            { name: 'Bread', quantity: null, unitPrice: null, lineSum: '60.00' },
            { name: 'Apples', quantity: null, unitPrice: null, lineSum: '25.00' },
            { name: 'Milk', quantity: null, unitPrice: null, lineSum: '15.00' },
            { name: 'Candy', quantity: null, unitPrice: null, lineSum: '20.00' },
          ],
          reconciliation: { algorithmVersion: 'receipt-reconciliation.v1', decision: 'review_required', selectedReader: null,
            mismatchFields: [], ocrItemsTotal: '120.00', visionItemsTotal: '60.00', allowedDifference: '3.00',
            ocrItemsReconciled: false, visionItemsReconciled: false,
            itemEvidence: [{ visionOrdinal: 1, ocrOrdinal: 1, status: 'corroborated' }],
            suggestedTopUps: [{ ocrOrdinal: 2, name: 'Apples', lineSum: '25.00' },
              { ocrOrdinal: 3, name: 'Milk', lineSum: '15.00' }] },
          visionFallbackReason: null, ocrFallbackReason: null,
          vision: { store: 'Cafe', date: '2026-10-04', total: '100.00', items: [{ name: 'Bread', quantity: null, unitPrice: null, lineSum: '60.00' }],
            provider: 'ollama', modelVersion: 'qwen3-vl:4b', promptVersion: 'receipt-vision.v1', fallbackReason: 'vision_model_unavailable' } });
      }
      if (url.endsWith('/duplicate-candidates')) return json({ receiptId, decision: 'unknown', candidates: [] });
      if (url.endsWith('/items?page=1')) return json({ items: [], page: 1, totalItems: 0, hasMore: false });
      throw new Error(`Unexpected request ${init?.method ?? 'GET'} ${url}`);
    }));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<MemoryRouter><QueryClientProvider client={client}>
      <ReceiptsPanel tenantId={tenantId} language={language} canWrite />
    </QueryClientProvider></MemoryRouter>);

    await user.upload(await screen.findByLabelText(language === 'ru' ? 'Фото чека' : 'Receipt photo'),
      new File(['image'], 'receipt.png', { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: upload }));

    expect(await screen.findByText(progress)).toBeInTheDocument();
    expect(await screen.findByText('TOTAL 120.00')).toBeInTheDocument();
    expect(screen.getByText(reading)).toBeInTheDocument();
    expect(screen.getByText(vision)).toBeInTheDocument();
    expect(screen.getByText(fallback)).toBeInTheDocument();
    expect(screen.getByText(unverified)).toBeInTheDocument();
    expect(screen.getByText(visionDate)).toBeInTheDocument();
    expect(screen.getByText(categoryUnknown)).toBeInTheDocument();
    expect(screen.getByText(reconciliation)).toBeInTheDocument();
    expect(screen.getByText(suggestions)).toBeInTheDocument();
    expect(screen.getByText('OCR #2: Apples · 25.00 ₽')).toBeInTheDocument();
    const uploadRequest = vi.mocked(fetch).mock.calls.find(([url, init]) =>
      String(url) === `/bff/tenants/${tenantId}/receipts/photo-jobs` && init?.method === 'POST');
    expect(new Headers(uploadRequest?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    expect(vi.mocked(fetch).mock.calls.some(([url, init]) =>
      String(url) === `/bff/tenants/${tenantId}/receipts` && init?.method === 'POST')).toBe(false);
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

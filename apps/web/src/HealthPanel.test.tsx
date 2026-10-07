import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HealthPanel } from './HealthPanel';
import { api } from './api';

vi.mock('./api', () => ({ api: { getHealth: vi.fn() } }));

const tenantId = '6c5e6ee6-15b7-4923-9547-844060f73b57';
const health = {
  capabilities: {
    localAi: { status: 'available', diagnosticCode: null },
    receiptVision: { status: 'disabled', diagnosticCode: 'VISION_DISABLED' },
    receiptOcr: { status: 'unavailable', diagnosticCode: 'TESSERACT_MISSING' },
  },
};

function mount(language: 'ru' | 'en' = 'ru') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <HealthPanel tenantId={tenantId} language={language} />
  </QueryClientProvider>);
}

beforeEach(() => vi.clearAllMocks());
afterEach(() => vi.restoreAllMocks());

describe('HealthPanel', () => {
  it('shows translated healthy, disabled, and unavailable features without provider details', async () => {
    vi.mocked(api.getHealth).mockResolvedValue(health as never);
    mount();
    expect(await screen.findByRole('heading', { name: 'Состояние сервисов' })).toBeInTheDocument();
    expect(await screen.findByText('Локальный ИИ')).toBeInTheDocument();
    expect(screen.getByText('Недоступен')).toBeInTheDocument();
    expect(screen.getByText('OCR чеков')).toBeInTheDocument();
    expect(screen.queryByText(/qwen|ollama|tesseract\.exe|private-host/i)).not.toBeInTheDocument();
  });

  it('renders English status and manually refreshes the health query', async () => {
    vi.mocked(api.getHealth).mockResolvedValue(health as never);
    mount('en');
    expect(await screen.findByRole('heading', { name: 'Service status' })).toBeInTheDocument();
    const refresh = await screen.findByRole('button', { name: 'Check again' });
    fireEvent.click(refresh);
    await waitFor(() => expect(api.getHealth).toHaveBeenCalledTimes(2));
    expect(screen.getByText('Disabled')).toBeInTheDocument();
  });

  it('explains that remote model inventory is intentionally not checked', async () => {
    vi.mocked(api.getHealth).mockResolvedValue({ capabilities: {
      localAi: { status: 'disabled', diagnosticCode: 'REMOTE_MODEL_STATUS_UNCHECKED' },
      receiptVision: { status: 'disabled', diagnosticCode: 'REMOTE_MODEL_STATUS_UNCHECKED' },
      receiptOcr: { status: 'available', diagnosticCode: null },
    } } as never);
    mount();
    expect(await screen.findAllByText('Список удалённых моделей не запрашивается.')).toHaveLength(2);
  });

  it('shows a bounded retry state when the BFF cannot return status', async () => {
    vi.mocked(api.getHealth).mockRejectedValue(new Error('private host and token'));
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить состояние сервисов');
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeInTheDocument();
    expect(screen.queryByText(/private host|token/i)).not.toBeInTheDocument();
  });
});

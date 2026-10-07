import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ExportsPanel } from './ExportsPanel';
import { api } from './api';

vi.mock('./api', () => ({ api: { getMembers: vi.fn(), createExport: vi.fn(), getExport: vi.fn() } }));

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const members = [
  { userId: 'owner-id', displayName: 'Alex', role: 'owner' as const },
  { userId: 'member-id', displayName: 'Taylor', role: 'member' as const },
];
const readyJob = {
  id: 'a57ad1f4-0a8f-44e0-afcf-bdf81f3be9d7', status: 'ready' as const, formatVersion: 'csv-v1' as const,
  fromDate: '2026-09-08', toDate: '2026-10-07', memberId: null, allMembers: true, rowCount: 0,
  snapshotAt: '2026-10-07T09:00:00Z', createdAt: '2026-10-07T09:00:00Z', expiresAt: '2026-10-08T09:00:00Z',
  downloadUrl: 'https://s3.example/private.csv?signature=short',
};

function mount(role: 'owner' | 'admin' | 'member' | 'viewer' = 'owner', language: 'ru' | 'en' = 'ru') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <ExportsPanel tenantId={tenantId} role={role} language={language} timezone="Europe/Moscow" />
  </QueryClientProvider>);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getMembers).mockResolvedValue(members);
  vi.mocked(api.createExport).mockResolvedValue({ ...readyJob });
  vi.mocked(api.getExport).mockResolvedValue({ ...readyJob });
});
afterEach(() => cleanup());

describe('ExportsPanel', () => {
  it('lets owner select all members and downloads an empty header-only export', async () => {
    const user = userEvent.setup();
    mount('owner');
    const from = await screen.findByLabelText('С даты') as HTMLInputElement;
    const to = screen.getByLabelText('По дату') as HTMLInputElement;
    expect(from.value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(to.value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(Date.parse(`${to.value}T00:00:00Z`) - Date.parse(`${from.value}T00:00:00Z`)).toBeLessThan(366 * 86_400_000);
    await user.selectOptions(await screen.findByLabelText('Область операций'), 'all');
    await user.click(screen.getByRole('button', { name: 'Создать CSV' }));
    await waitFor(() => expect(api.createExport).toHaveBeenCalledWith(tenantId, expect.objectContaining({
      formatVersion: 'csv-v1', fromDate: from.value, toDate: to.value, memberId: 'all',
    })));
    expect(await screen.findByText('Файл готов')).toBeInTheDocument();
    expect(screen.getByText('Операций: 0')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Скачать CSV' })).toHaveAttribute('href', readyJob.downloadUrl);
  });

  it('limits member and viewer to own scope and offers English labels', async () => {
    const user = userEvent.setup();
    mount('viewer', 'en');
    expect(await screen.findByRole('heading', { name: 'CSV export' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Member scope/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create CSV' }));
    await waitFor(() => expect(api.createExport).toHaveBeenCalledWith(tenantId, expect.objectContaining({
      formatVersion: 'csv-v1',
    })));
    const request = vi.mocked(api.createExport).mock.calls[0][1];
    expect(request.memberId).toBeUndefined();
    expect(api.getMembers).not.toHaveBeenCalled();
  });

  it('polls retry-pending and processing states, then shows terminal failure without download', async () => {
    const queued = { ...readyJob, status: 'queued' as const, downloadUrl: null };
    vi.mocked(api.createExport).mockResolvedValue(queued);
    vi.mocked(api.getExport).mockResolvedValueOnce(queued)
      .mockResolvedValueOnce({ ...readyJob, status: 'processing', downloadUrl: null })
      .mockResolvedValueOnce({ ...readyJob, status: 'failed', downloadUrl: null });
    mount('member', 'en');
    await screen.findByRole('heading', { name: 'CSV export' });
    fireEvent.click(screen.getByRole('button', { name: 'Create CSV' }));
    expect(await screen.findByText('Queued')).toBeInTheDocument();
    expect(await screen.findByText('Preparing file', {}, { timeout: 4000 })).toBeInTheDocument();
    expect(await screen.findByText('Export failed', {}, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Download CSV' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create another export' })).toBeInTheDocument();
    expect(api.getExport).toHaveBeenCalledTimes(3);
  });

  it('sends one selected member only for owner or admin scope', async () => {
    const user = userEvent.setup();
    mount('admin');
    await screen.findByRole('option', { name: 'Taylor' });
    await user.selectOptions(await screen.findByLabelText('Область операций'), 'member-id');
    await user.click(screen.getByRole('button', { name: 'Создать CSV' }));
    await waitFor(() => expect(api.createExport).toHaveBeenCalledWith(tenantId, expect.objectContaining({
      memberId: 'member-id',
    })));
  });

  it('rejects reversed or overlong periods before sending a request', async () => {
    const user = userEvent.setup();
    mount('admin');
    const from = await screen.findByLabelText('С даты');
    const to = screen.getByLabelText('По дату');
    await user.clear(from);
    await user.type(from, '2025-01-01');
    await user.clear(to);
    await user.type(to, '2026-10-07');
    expect(screen.getByRole('button', { name: 'Создать CSV' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent(/366/);
    expect(api.createExport).not.toHaveBeenCalled();
  });

  it('hides a stale signed URL after export expiry', async () => {
    vi.mocked(api.createExport).mockResolvedValue({ ...readyJob, status: 'queued', downloadUrl: null });
    vi.mocked(api.getExport).mockResolvedValue({ ...readyJob, status: 'expired' });
    mount('member', 'en');
    fireEvent.click(await screen.findByRole('button', { name: 'Create CSV' }));
    expect(await screen.findByText('Export expired')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Download CSV' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create another export' })).toBeInTheDocument();
  });
});

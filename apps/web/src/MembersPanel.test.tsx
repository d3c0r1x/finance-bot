import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MembersPanel } from './MembersPanel';
import { api } from './api';

vi.mock('./api', () => ({ api: { getMembers: vi.fn() } }));

const tenantId = 'b9224f75-9555-4ec3-983e-16eb6f332921';
const ownerId = 'owner-user-id';
const members = [
  { userId: ownerId, displayName: 'Alex', role: 'owner' as const },
  { userId: 'member-user-id', displayName: 'Taylor', role: 'member' as const },
];

function mount(role: 'owner' | 'admin' | 'member' | 'viewer' = 'owner', currentUserId = ownerId,
  language: 'ru' | 'en' = 'ru') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<MemoryRouter><QueryClientProvider client={client}>
    <MembersPanel tenantId={tenantId} role={role} currentUserId={currentUserId} language={language} />
  </QueryClientProvider></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getMembers).mockResolvedValue(members);
});
afterEach(() => cleanup());

describe('MembersPanel', () => {
  it('shows roles, member transactions, and own profile action', async () => {
    mount();
    expect(await screen.findByRole('heading', { name: 'Пользователи' })).toBeInTheDocument();
    expect(screen.getByText('Владелец')).toBeInTheDocument();
    expect(screen.getByText('Участник')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Изменить профиль' })).toHaveAttribute('href', '/profile');
    expect(screen.getByRole('link', { name: 'Показать операции: Taylor' }))
      .toHaveAttribute('href', `/transactions?memberId=${members[1].userId}`);
  });

  it('uses API-provided self scope for viewer and does not show other members', async () => {
    vi.mocked(api.getMembers).mockResolvedValue([members[0]]);
    mount('viewer');
    expect(await screen.findByText('Alex')).toBeInTheDocument();
    expect(screen.queryByText('Taylor')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Показать операции: Alex' }))
      .toHaveAttribute('href', '/transactions');
  });

  it('shows English roles and actions', async () => {
    mount('owner', ownerId, 'en');
    expect(await screen.findByRole('heading', { name: 'Members' })).toBeInTheDocument();
    expect(screen.getByText('Owner')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View transactions: Taylor' }))
      .toHaveAttribute('href', `/transactions?memberId=${members[1].userId}`);
    expect(screen.getByRole('link', { name: 'Edit profile' })).toHaveAttribute('href', '/profile');
  });

  it('shows a retry action when the member endpoint fails', async () => {
    vi.mocked(api.getMembers).mockRejectedValueOnce(new Error('Members unavailable'));
    const user = userEvent.setup();
    mount('admin');
    expect(await screen.findByRole('alert')).toHaveTextContent('Members unavailable');
    expect(api.getMembers).toHaveBeenCalledTimes(1);
    // The retry button is enabled after the failed request and refetches the member list.
    vi.mocked(api.getMembers).mockResolvedValue(members);
    await user.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText('Taylor')).toBeInTheDocument();
    expect(api.getMembers).toHaveBeenCalledTimes(2);
  });
});

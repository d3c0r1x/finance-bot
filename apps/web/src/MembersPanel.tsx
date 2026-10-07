import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, type TenantMembership } from './api';

type Language = 'ru' | 'en';
type Role = TenantMembership['role'];

const copy = {
  ru: {
    title: 'Пользователи', subtitle: 'Участники пространства и доступные действия.',
    loading: 'Загрузка участников…', error: 'Не удалось загрузить участников.', retry: 'Повторить',
    empty: 'В пространстве пока нет участников.', transactions: 'Показать операции', profile: 'Изменить профиль',
    roles: { owner: 'Владелец', admin: 'Администратор', member: 'Участник', viewer: 'Наблюдатель' },
  },
  en: {
    title: 'Members', subtitle: 'Workspace members and available actions.',
    loading: 'Loading members…', error: 'Could not load members.', retry: 'Retry',
    empty: 'No workspace members yet.', transactions: 'View transactions', profile: 'Edit profile',
    roles: { owner: 'Owner', admin: 'Admin', member: 'Member', viewer: 'Viewer' },
  },
} as const;

export function MembersPanel({ tenantId, role, currentUserId, language }: {
  tenantId: string; role: Role; currentUserId: string; language: Language;
}) {
  const t = copy[language];
  const members = useQuery({ queryKey: ['members', tenantId], queryFn: () => api.getMembers(tenantId), retry: false });

  if (members.isPending) return <section className="members-panel panel" aria-label={t.title}>
    <p className="empty-state" role="status">{t.loading}</p>
  </section>;
  if (members.isError) return <section className="members-panel panel" aria-label={t.title}>
    <div className="empty-state"><p role="alert">{members.error.message || t.error}</p>
      <button className="button button-quiet" onClick={() => void members.refetch()}>{t.retry}</button></div>
  </section>;

  const manager = role === 'owner' || role === 'admin';
  return <section className="members-panel panel" aria-label={t.title}>
    <div className="panel-heading"><div><span className="eyebrow">F53 · {t.subtitle}</span><h2>{t.title}</h2></div></div>
    {members.data.length === 0 ? <p className="empty-state">{t.empty}</p> : <ul className="member-list">
      {members.data.map((member) => <li className="member-card" key={member.userId}>
        <div className="member-identity"><h3>{member.displayName}</h3>
          <span>{t.roles[member.role]}</span></div>
        <div className="member-actions">
          <Link className="button button-quiet" aria-label={`${t.transactions}: ${member.displayName}`}
            to={manager ? `/transactions?memberId=${encodeURIComponent(member.userId)}` : '/transactions'}>
            {t.transactions}
          </Link>
          {member.userId === currentUserId && <Link className="button button-quiet" to="/profile">{t.profile}</Link>}
        </div>
      </li>)}
    </ul>}
  </section>;
}

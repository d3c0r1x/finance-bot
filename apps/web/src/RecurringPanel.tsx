import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type RecurringSeries } from './api';

const copy = {
  ru: {
    title: 'Регулярные платежи и доходы', loading: 'Загрузка…', error: 'Серии временно недоступны.',
    empty: 'Пока не найдено регулярных операций.', enough: 'Нужны минимум 3 похожие операции с недельным или месячным интервалом.',
    dueSoon: 'Скоро', expenses: 'Расходы', income: 'Доходы', overdue: 'Просрочено', monthly: 'В месяц на расходы',
    range: 'сумма', cadence: 'интервал', next: 'ожидается', last: 'последняя операция', days: 'дн.',
    muted: 'Отключённые напоминания', mute: 'Отключить', restore: 'Восстановить', actionError: 'Не удалось изменить напоминание.',
  },
  en: {
    title: 'Recurring expenses and income', loading: 'Loading…', error: 'Recurring series are temporarily unavailable.',
    empty: 'No recurring transactions found yet.', enough: 'At least 3 similar transactions with a weekly or monthly interval are needed.',
    dueSoon: 'Due soon', expenses: 'Expenses', income: 'Income', overdue: 'Overdue', monthly: 'Monthly expenses',
    range: 'amount', cadence: 'interval', next: 'expected', last: 'last transaction', days: 'days',
    muted: 'Muted reminders', mute: 'Mute', restore: 'Restore', actionError: 'Could not update reminder.',
  },
} as const;

export function RecurringPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = copy[language];
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const queryClient = useQueryClient();
  const queryKey = ['recurring-projection', tenantId];
  const projection = useQuery({
    queryKey, queryFn: () => api.getRecurringProjection(tenantId), retry: false,
  });
  const decision = useMutation({
    mutationFn: ({ seriesId, muted }: { seriesId: string; muted: boolean }) => muted
      ? api.muteRecurringSeries(tenantId, seriesId) : api.unmuteRecurringSeries(tenantId, seriesId),
    onSuccess: (next) => queryClient.setQueryData(queryKey, next),
  });
  const money = (value: string) => new Intl.NumberFormat(locale, { style: 'currency', currency: 'RUB', maximumFractionDigits: 2 }).format(Number(value));
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${value}T12:00:00Z`));

  return <section className="panel recurring-panel" aria-labelledby="recurring-title" aria-busy={projection.isPending}>
    <div className="panel-heading"><h2 id="recurring-title">{t.title}</h2></div>
    {projection.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : projection.isError ? <p className="empty-state" role="alert">{t.error}</p>
        : !projection.data.expenseSeries.length && !projection.data.incomeSeries.length && !projection.data.mutedSeries.length
          ? <div className="empty-state"><p>{t.empty}</p><p>{t.enough}</p></div>
          : <>
            {decision.isError && <p role="alert">{t.actionError}</p>}
            {projection.data.monthlyExpenseEstimate !== null && <p className="recurring-total"><strong>{t.monthly}:</strong> {money(projection.data.monthlyExpenseEstimate)}</p>}
            <RecurringGroup title={t.dueSoon} items={projection.data.dueSoon} money={money} date={date} labels={t} />
            <RecurringGroup title={t.expenses} items={projection.data.expenseSeries} money={money} date={date} labels={t}
              actionLabel={t.mute} onAction={(item) => decision.mutate({ seriesId: item.id, muted: true })}
              pending={decision.isPending} />
            <RecurringGroup title={t.income} items={projection.data.incomeSeries} money={money} date={date} labels={t}
              actionLabel={t.mute} onAction={(item) => decision.mutate({ seriesId: item.id, muted: true })}
              pending={decision.isPending} />
            <RecurringGroup title={t.overdue} items={projection.data.overdue} money={money} date={date} labels={t} />
            <RecurringGroup title={t.muted} items={projection.data.mutedSeries} money={money} date={date} labels={t}
              actionLabel={t.restore} onAction={(item) => decision.mutate({ seriesId: item.id, muted: false })}
              pending={decision.isPending} />
          </>}
  </section>;
}

function RecurringGroup({ title, items, money, date, labels, actionLabel, onAction, pending }: {
  title: string; items: RecurringSeries[]; money: (value: string) => string;
  date: (value: string) => string; labels: typeof copy['ru'] | typeof copy['en'];
  actionLabel?: string; onAction?: (item: RecurringSeries) => void; pending?: boolean;
}) {
  if (!items.length) return null;
  return <section className="recurring-group" aria-label={title}>
    <h3>{title}</h3>
    <div className="recurring-list">{items.map((item) => <article className="recurring-item" key={item.id}>
      <div><h4>{item.name}</h4>
        <p>{labels.range}: {money(item.minAmount)}–{money(item.maxAmount)} · {labels.cadence}: {item.minIntervalDays}–{item.maxIntervalDays} {labels.days}</p>
        <p>{labels.last}: {date(item.lastDate)} · {labels.next}: {date(item.nextDate)}</p></div>
      <div><strong>{money(item.amount)}</strong>
        {actionLabel && onAction && <button type="button" aria-label={`${actionLabel} ${item.name}`}
          disabled={pending} onClick={() => onAction(item)}>{actionLabel}</button>}
      </div>
    </article>)}</div>
  </section>;
}

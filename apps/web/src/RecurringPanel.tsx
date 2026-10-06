import { useQuery } from '@tanstack/react-query';
import { api, type RecurringSeries } from './api';

const copy = {
  ru: {
    title: 'Регулярные платежи и доходы', loading: 'Загрузка…', error: 'Серии временно недоступны.',
    empty: 'Пока не найдено регулярных операций.', enough: 'Нужны минимум 3 похожие операции с недельным или месячным интервалом.',
    dueSoon: 'Скоро', expenses: 'Расходы', income: 'Доходы', overdue: 'Просрочено', monthly: 'В месяц на расходы',
    range: 'сумма', cadence: 'интервал', next: 'ожидается', last: 'последняя операция', days: 'дн.',
  },
  en: {
    title: 'Recurring expenses and income', loading: 'Loading…', error: 'Recurring series are temporarily unavailable.',
    empty: 'No recurring transactions found yet.', enough: 'At least 3 similar transactions with a weekly or monthly interval are needed.',
    dueSoon: 'Due soon', expenses: 'Expenses', income: 'Income', overdue: 'Overdue', monthly: 'Monthly expenses',
    range: 'amount', cadence: 'interval', next: 'expected', last: 'last transaction', days: 'days',
  },
} as const;

export function RecurringPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = copy[language];
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const projection = useQuery({
    queryKey: ['recurring-projection', tenantId], queryFn: () => api.getRecurringProjection(tenantId), retry: false,
  });
  const money = (value: string) => new Intl.NumberFormat(locale, { style: 'currency', currency: 'RUB', maximumFractionDigits: 2 }).format(Number(value));
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${value}T12:00:00Z`));

  return <section className="panel recurring-panel" aria-labelledby="recurring-title" aria-busy={projection.isPending}>
    <div className="panel-heading"><h2 id="recurring-title">{t.title}</h2></div>
    {projection.isPending ? <p className="empty-state" aria-live="polite">{t.loading}</p>
      : projection.isError ? <p className="empty-state" role="alert">{t.error}</p>
        : !projection.data.expenseSeries.length && !projection.data.incomeSeries.length
          ? <div className="empty-state"><p>{t.empty}</p><p>{t.enough}</p></div>
          : <>
            {projection.data.monthlyExpenseEstimate !== null && <p className="recurring-total"><strong>{t.monthly}:</strong> {money(projection.data.monthlyExpenseEstimate)}</p>}
            <RecurringGroup title={t.dueSoon} items={projection.data.dueSoon} money={money} date={date} labels={t} />
            <RecurringGroup title={t.expenses} items={projection.data.expenseSeries} money={money} date={date} labels={t} />
            <RecurringGroup title={t.income} items={projection.data.incomeSeries} money={money} date={date} labels={t} />
            <RecurringGroup title={t.overdue} items={projection.data.overdue} money={money} date={date} labels={t} />
          </>}
  </section>;
}

function RecurringGroup({ title, items, money, date, labels }: {
  title: string; items: RecurringSeries[]; money: (value: string) => string;
  date: (value: string) => string; labels: typeof copy['ru'] | typeof copy['en'];
}) {
  if (!items.length) return null;
  return <section className="recurring-group" aria-label={title}>
    <h3>{title}</h3>
    <div className="recurring-list">{items.map((item) => <article className="recurring-item" key={item.id}>
      <div><h4>{item.name}</h4>
        <p>{labels.range}: {money(item.minAmount)}–{money(item.maxAmount)} · {labels.cadence}: {item.minIntervalDays}–{item.maxIntervalDays} {labels.days}</p>
        <p>{labels.last}: {date(item.lastDate)} · {labels.next}: {date(item.nextDate)}</p></div>
      <strong>{money(item.amount)}</strong>
    </article>)}</div>
  </section>;
}

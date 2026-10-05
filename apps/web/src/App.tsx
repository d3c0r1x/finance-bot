import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type BudgetAlert, type BudgetOverview, type BudgetProposal, type CreateTransaction, type UpdateTransaction, type DashboardSummary as Summary,
  type FinanceReport, type MemberProfile, type NotificationPreferences, type TelegramLinkCode, type TenantMember,
  type Transaction, type TransactionDraft, type UpdateNotificationPreferences, type UpdateTransactionDraft } from './api';
import { ReceiptsPanel } from './ReceiptsPanel';
import { ProductCatalogPanel } from './ProductCatalogPanel';
import { ShoppingPanel } from './ShoppingPanel';
import { PersonalInflationPanel } from './PersonalInflationPanel';
import { ImportsPanel } from './ImportsPanel';
import './styles.css';

const copy = {
  ru: {
    brand: 'Finance', login: 'Войти', loginTitle: 'Финансы без шума', loginText: 'Один понятный обзор личных и семейных денег.',
    workspace: 'Пространство', operations: 'Операции', receipts: 'Чеки', products: 'Товары', shopping: 'Покупки', inflation: 'Динамика цен', imports: 'Выписки', logout: 'Выйти', onboarding: 'Создать пространство',
    repeatSetup: 'Пройти настройку заново',
    onboardingText: 'Начните с личного пространства. Семью можно добавить позже.', name: 'Название пространства',
    timezone: 'Часовой пояс', create: 'Продолжить', startSetup: 'Начать настройку', next: 'Далее', back: 'Назад',
    createWorkspace: 'Создать пространство', incomeStepText: 'Укажите ожидаемый доход, чтобы получить предложение бюджета. Это необязательно.',
    budgetSetupText: 'Предложение не меняет лимиты. Примените его только если суммы вам подходят.',
    keepCurrentBudget: 'Оставить текущие лимиты', transactions: 'Операции', transactionText: 'Доходы и расходы в одном журнале.',
    draftTextLabel: 'Операция текстом', draftTextHint: 'Например: Такси 2 тыс или Зарплата 80 000', parseText: 'Разобрать текст',
    draftReview: 'Проверьте черновик', draftType: 'Тип черновика', draftAmount: 'Сумма черновика',
    draftDescription: 'Описание черновика', draftCategory: 'Категория черновика', draftSubcategory: 'Подкатегория черновика',
    draftDate: 'Дата черновика', debtChoice: 'Долг для платежа', quickAmount: 'Быстрая сумма',
    confirmDraft: 'Подтвердить и записать', cancelDraft: 'Отменить черновик', draftModel: 'Черновик предложен моделью',
    amount: 'Сумма, ₽', description: 'Описание', category: 'Категория', subcategory: 'Подкатегория', source: 'Источник', date: 'Дата', type: 'Тип операции',
    expense: 'Расход', income: 'Доход', refund: 'Возврат', transfer: 'Перевод', save: 'Сохранить', search: 'Поиск по операциям',
    filterType: 'Все типы', filterByMember: 'Фильтр по участнику', memberFilter: 'Участник операции', allMembers: 'Все участники',
    transactionOwner: 'Пользователь операции', assignMember: 'Назначить участника',
    from: 'С даты', to: 'По дату', loading: 'Загрузка…', retry: 'Повторить', empty: 'Операций пока нет',
    loadMore: 'Загрузить ещё', status: 'Статус', posted: 'Проведена', error: 'Не удалось загрузить данные',
    profile: 'Мой профиль', memberName: 'Ваше имя', plannedIncome: 'Плановый доход в месяц, ₽', saveProfile: 'Сохранить профиль',
    digestSettings: 'Сводки в Telegram', digestLanguage: 'Язык дайджеста', dailyDigest: 'Ежедневная сводка',
    dailyDigestTime: 'Время ежедневной сводки', weeklyDigest: 'Недельная сводка', weeklyDigestDay: 'День недельного дайджеста',
    weeklyDigestTime: 'Время недельной сводки', quietHoursStart: 'Начало тихих часов', quietHoursEnd: 'Конец тихих часов',
    saveDigestSettings: 'Сохранить расписание', telegramLinked: 'Telegram подключён', telegramNotLinked: 'Сначала подключите Telegram, чтобы получать сводки.',
    quietHoursPair: 'Укажите обе границы тихих часов или оставьте обе пустыми.', monday: 'Понедельник', tuesday: 'Вторник',
    wednesday: 'Среда', thursday: 'Четверг', friday: 'Пятница', saturday: 'Суббота', sunday: 'Воскресенье',
    connectTelegram: 'Подключить Telegram', telegramLinkHelp: 'Введите в личном чате с ботом /link и этот код. Код действует 10 минут. Новый код отменит предыдущий.',
    telegramLinkExpires: 'Действует до',
    skipIncome: 'Пропустить доход сейчас',
    edit: 'Изменить', repeat: 'Повторить', void: 'Отменить', update: 'Обновить', cancelEdit: 'Не менять',
    dashboard: 'Обзор месяца', incomeTotal: 'Доходы за месяц', expenseTotal: 'Расходы за месяц', transactionCount: 'Операций за месяц',
    budgets: 'Бюджеты', familyScope: 'Семейный', personalScope: 'Личный', budgetTotal: 'Общий лимит', saveLimit: 'Сохранить лимит', resetPersonal: 'Сбросить личные лимиты',
    spentThisMonth: 'Потрачено за месяц', statusNormal: 'В пределах лимита', statusNear: 'Почти достигнут', statusExceeded: 'Лимит превышен', statusDisabled: 'Лимит выключен',
    activeLimit: 'Действующий лимит',
    budgetScopeLabel: 'Область бюджета', budgetLimitLabel: 'Лимит',
    rollingFood: 'Еда за последние 7 дней', rollingSpent: 'Потрачено за 7 дней',
    foodWindow: 'Окно расходов', foodLimit: 'Лимит на еду', foodRemaining: 'Остаток лимита',
    usualFoodPace: 'Обычные расходы на еду', foodPaceOver: 'Быстрее обычного',
    foodPaceUnder: 'Медленнее обычного', foodPaceNormal: 'Обычный темп',
    insufficientFoodHistory: 'Истории мало для сравнения; лимит за 7 дней действует.',
    proposeBudget: 'Предложить лимиты по доходу', proposeHistoryBudget: 'Предложить ИИ-лимиты по истории',
    historyProposalDetails: 'История расходов: {days} дн. Модель: {model}.', applyProposal: 'Применить предложение',
    proposedTotal: 'Предложенный общий лимит', incomeNeeded: 'Укажите плановый доход в профиле для расчёта.',
    debts: 'Долги', debtName: 'Название долга', openingBalance: 'Начальный остаток, ₽', interestRate: 'Ставка, %',
    minimumPayment: 'Минимальный платёж, ₽', createDebt: 'Создать долг', currentBalance: 'Текущий остаток',
    debtPayment: 'Платёж', recordPayment: 'Записать платёж', adjustBalance: 'Остаток', saveBalance: 'Сохранить остаток',
    forecast: 'Прогноз', months: 'мес.', noDebts: 'Долгов пока нет', debtOpen: 'Открыт', debtClosed: 'Закрыт',
    dailyPace: 'Средний темп расходов в день', monthProjection: 'Прогноз расходов к концу месяца',
    safeSpendPerDay: 'Безопасно тратить в день', safeSpendTotal: 'Свободно до горизонта',
    safeSpendHorizon: 'Горизонт до', safeSpendReserve: 'Резерв 10%', promisedPayments: 'Обязательные списания',
    safeSpendDays: 'дн.', plannedIncomeBasis: 'плановый доход', actualIncomeBasis: 'доход этого месяца',
    currentBudgetRemaining: 'Остаток месячного лимита',
    projectedReserve: 'Ожидаемый остаток лимита', projectedOverrun: 'Ожидаемый перерасход лимита',
    reports: 'Отчёты', reportPeriod: 'Период отчёта', reportScope: 'Область отчёта', periodMonth: 'Месяц',
    periodWeek: 'Неделя', period90d: '90 дней', periodCustom: 'Произвольный период', scopePersonal: 'Личные',
    scopeFamily: 'Вся семья', reportFrom: 'С даты отчёта', reportTo: 'По дату отчёта', reportIncome: 'Доходы',
    reportExpense: 'Расходы', reportDebtPayments: 'Платежи по долгам', reportRefunds: 'Возвраты',
    reportCount: 'Операций', weekendShare: 'Расходы в выходные', expenseCategories: 'Расходы по категориям',
    monthlyFamilyBudget: 'Семейный месячный лимит', monthlyPersonalBudget: 'Личный месячный лимит',
    reportWindow: 'Даты и часовой пояс',
    dailyExpenses: 'Расходы по дням', budgetUsage: 'Использование месячных лимитов',
  },
  en: {
    brand: 'Finance', login: 'Sign in', loginTitle: 'Money, clearly', loginText: 'One clear view of your personal and family finances.',
    workspace: 'Workspace', operations: 'Transactions', receipts: 'Receipts', products: 'Products', shopping: 'Shopping', inflation: 'Price trend', imports: 'Statements', logout: 'Sign out', onboarding: 'Create a workspace',
    repeatSetup: 'Run setup again',
    onboardingText: 'Start with a personal workspace. Add family later.', name: 'Workspace name',
    timezone: 'Time zone', create: 'Continue', startSetup: 'Start setup', next: 'Next', back: 'Back',
    createWorkspace: 'Create workspace', incomeStepText: 'Add expected income to get a budget suggestion. This is optional.',
    budgetSetupText: 'The suggestion does not change limits. Apply it only if the amounts work for you.',
    keepCurrentBudget: 'Keep current limits', transactions: 'Transactions', transactionText: 'Income and expenses in one ledger.',
    draftTextLabel: 'Describe a transaction', draftTextHint: 'For example: Taxi 2,000 or Salary 80,000', parseText: 'Extract draft',
    draftReview: 'Review draft', draftType: 'Draft type', draftAmount: 'Draft amount',
    draftDescription: 'Draft description', draftCategory: 'Draft category', draftSubcategory: 'Draft subcategory',
    draftDate: 'Draft date', debtChoice: 'Debt for payment', quickAmount: 'Quick amount',
    confirmDraft: 'Confirm and record', cancelDraft: 'Cancel draft', draftModel: 'Draft suggested by model',
    amount: 'Amount, RUB', description: 'Description', category: 'Category', subcategory: 'Subcategory', source: 'Source', date: 'Date', type: 'Transaction type',
    expense: 'Expense', income: 'Income', refund: 'Refund', transfer: 'Transfer', save: 'Save', search: 'Search transactions',
    filterType: 'All types', filterByMember: 'Filter by member', memberFilter: 'Transaction member', allMembers: 'All members',
    transactionOwner: 'Transaction member', assignMember: 'Assign to member',
    from: 'From', to: 'To', loading: 'Loading…', retry: 'Retry', empty: 'No transactions yet',
    loadMore: 'Load more', status: 'Status', posted: 'Posted', error: 'Could not load data',
    profile: 'My profile', memberName: 'Your name', plannedIncome: 'Planned monthly income, RUB', saveProfile: 'Save profile',
    digestSettings: 'Telegram digests', digestLanguage: 'Digest language', dailyDigest: 'Daily digest',
    dailyDigestTime: 'Daily digest time', weeklyDigest: 'Weekly digest', weeklyDigestDay: 'Weekly digest day',
    weeklyDigestTime: 'Weekly digest time', quietHoursStart: 'Quiet hours start', quietHoursEnd: 'Quiet hours end',
    saveDigestSettings: 'Save schedule', telegramLinked: 'Telegram is connected', telegramNotLinked: 'Connect Telegram to receive digests.',
    quietHoursPair: 'Set both quiet-hours times or leave both empty.', monday: 'Monday', tuesday: 'Tuesday',
    wednesday: 'Wednesday', thursday: 'Thursday', friday: 'Friday', saturday: 'Saturday', sunday: 'Sunday',
    connectTelegram: 'Connect Telegram', telegramLinkHelp: 'In a private chat with the bot, send /link followed by this code. The code expires in 10 minutes. A new code replaces the previous one.',
    telegramLinkExpires: 'Expires at',
    skipIncome: 'Skip income for now',
    edit: 'Edit', repeat: 'Repeat', void: 'Void', update: 'Update', cancelEdit: 'Cancel edit',
    dashboard: 'Monthly overview', incomeTotal: 'Income this month', expenseTotal: 'Expenses this month', transactionCount: 'Transactions this month',
    budgets: 'Budgets', familyScope: 'Family', personalScope: 'Personal', budgetTotal: 'Total limit', saveLimit: 'Save limit', resetPersonal: 'Reset personal limits',
    spentThisMonth: 'Spent this month', statusNormal: 'Within limit', statusNear: 'Near limit', statusExceeded: 'Over limit', statusDisabled: 'Limit disabled',
    activeLimit: 'Current limit',
    budgetScopeLabel: 'Budget scope', budgetLimitLabel: 'Limit',
    rollingFood: 'Food over the last 7 days', rollingSpent: 'Spent over 7 days',
    foodWindow: 'Spending window', foodLimit: 'Food limit', foodRemaining: 'Limit remaining',
    usualFoodPace: 'Usual food spending', foodPaceOver: 'Faster than usual',
    foodPaceUnder: 'Slower than usual', foodPaceNormal: 'Usual pace',
    insufficientFoodHistory: 'Not enough history to compare; seven-day limit still applies.',
    proposeBudget: 'Suggest limits from income', proposeHistoryBudget: 'Suggest AI limits from history',
    historyProposalDetails: 'Expense history: {days} days. Model: {model}.', applyProposal: 'Apply proposal',
    proposedTotal: 'Proposed total limit', incomeNeeded: 'Add planned income to your profile to calculate a proposal.',
    debts: 'Debts', debtName: 'Debt name', openingBalance: 'Opening balance, RUB', interestRate: 'Interest rate, %',
    minimumPayment: 'Minimum payment, RUB', createDebt: 'Create debt', currentBalance: 'Current balance',
    debtPayment: 'Payment', recordPayment: 'Record payment', adjustBalance: 'Balance', saveBalance: 'Save balance',
    forecast: 'Forecast', months: 'months', noDebts: 'No debts yet', debtOpen: 'Open', debtClosed: 'Closed',
    dailyPace: 'Average daily spending pace', monthProjection: 'Projected spending by month end',
    safeSpendPerDay: 'Safe to spend per day', safeSpendTotal: 'Available through horizon',
    safeSpendHorizon: 'Horizon through', safeSpendReserve: '10% reserve', promisedPayments: 'Committed payments',
    safeSpendDays: 'days', plannedIncomeBasis: 'planned income', actualIncomeBasis: 'this month income',
    currentBudgetRemaining: 'Monthly budget remaining',
    projectedReserve: 'Projected budget remaining', projectedOverrun: 'Projected budget overrun',
    reports: 'Reports', reportPeriod: 'Report period', reportScope: 'Report scope', periodMonth: 'Month',
    periodWeek: 'Week', period90d: '90 days', periodCustom: 'Custom period', scopePersonal: 'Personal',
    scopeFamily: 'Whole family', reportFrom: 'From report date', reportTo: 'To report date', reportIncome: 'Income',
    reportExpense: 'Expenses', reportDebtPayments: 'Debt payments', reportRefunds: 'Refunds',
    reportCount: 'Transactions', weekendShare: 'Weekend expenses', expenseCategories: 'Expenses by category',
    monthlyFamilyBudget: 'Family monthly limit', monthlyPersonalBudget: 'Personal monthly limit',
    reportWindow: 'Dates and time zone',
    dailyExpenses: 'Daily expenses', budgetUsage: 'Monthly budget usage',
  },
} as const;

type Language = keyof typeof copy;
type Translations = (typeof copy)[Language];

function todayInput(timezone?: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

type DraftForm = Omit<UpdateTransactionDraft, 'occurredAt'> & { occurredOn: string };

function dateInZone(value: string, timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(value));
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function noonInZone(date: string, timezone: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const target = Date.UTC(year, month - 1, day, 12);
  let instant = target;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(instant));
    const part = (type: string) => Number(parts.find((item) => item.type === type)?.value ?? 0);
    const shownAsUtc = Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
    instant = target - (shownAsUtc - instant);
  }
  return new Date(instant).toISOString();
}

function draftForm(draft: TransactionDraft, timezone: string): DraftForm {
  return {
    type: draft.type, amount: draft.amount, currency: draft.currency, categoryCode: draft.categoryCode,
    subcategoryCode: draft.subcategoryCode, description: draft.description, debtId: draft.debtId,
    occurredOn: dateInZone(draft.occurredAt, timezone),
  };
}

function draftRequest(fields: DraftForm, draft: TransactionDraft, timezone: string): UpdateTransactionDraft {
  const originalDate = dateInZone(draft.occurredAt, timezone);
  return {
    type: fields.type, amount: fields.amount.trim().replace(',', '.'), currency: 'RUB',
    categoryCode: fields.categoryCode.trim(), subcategoryCode: fields.subcategoryCode,
    description: fields.description.trim(), debtId: fields.debtId,
    occurredAt: fields.occurredOn === originalDate ? draft.occurredAt : noonInZone(fields.occurredOn, timezone),
  };
}

export function App() {
  const [language, setLanguage] = useState<Language>('ru');
  const t = copy[language];
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [tenantId, setTenantId] = useState('');
  const [search, setSearch] = useState('');
  const [filterType, setFilterType] = useState('all');
  const [memberFilter, setMemberFilter] = useState('all');
  const [showMemberFilter, setShowMemberFilter] = useState(false);
  const [showOwnerSelector, setShowOwnerSelector] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('other');
  const [subcategory, setSubcategory] = useState('');
  const [source, setSource] = useState('manual');
  const [type, setType] = useState<CreateTransaction['type']>('expense');
  const [transactionBudgetAlerts, setTransactionBudgetAlerts] = useState<BudgetAlert[]>([]);
  const [debtId, setDebtId] = useState<string | null>(null);
  const [ownerUserId, setOwnerUserId] = useState('');
  const [occurredOn, setOccurredOn] = useState(todayInput);
  const [dateTouched, setDateTouched] = useState(false);
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [draftText, setDraftText] = useState('');
  const [transactionDraft, setTransactionDraft] = useState<TransactionDraft | null>(null);
  const [draftFields, setDraftFields] = useState<DraftForm | null>(null);
  const [draftConfirmKey, setDraftConfirmKey] = useState('');
  const [budgetProposal, setBudgetProposal] = useState<{ tenantId: string; proposal: BudgetProposal } | null>(null);
  const [budgetSetupError, setBudgetSetupError] = useState<string>();
  const [showBudgetSetup, setShowBudgetSetup] = useState(false);
  const [budgetSetupPending, setBudgetSetupPending] = useState(false);
  const [telegramLinkCode, setTelegramLinkCode] = useState<TelegramLinkCode | null>(null);
  const [repeatSetup, setRepeatSetup] = useState(false);

  const offerIncomeBudget = async (targetTenantId: string, income: number) => {
    setShowBudgetSetup(true);
    setBudgetSetupPending(true);
    setBudgetSetupError(undefined);
    try {
      const proposal = await api.createBudgetProposal(targetTenantId, income.toFixed(2));
      setBudgetProposal({ tenantId: targetTenantId, proposal });
    } catch {
      setBudgetSetupError(t.error);
    } finally {
      setBudgetSetupPending(false);
    }
  };

  const session = useQuery({ queryKey: ['session'], queryFn: api.getSession, retry: false });
  const tenants = useQuery({
    queryKey: ['tenants'], queryFn: api.getTenants, enabled: session.data?.authenticated === true,
  });
  const activeTenant = tenants.data?.find((item) => item.tenantId === tenantId) ?? tenants.data?.[0];
  const canWriteTransactions = activeTenant?.role !== 'viewer';
  const canManageMembers = activeTenant?.role === 'owner' || activeTenant?.role === 'admin';
  useEffect(() => {
    if (!dateTouched && activeTenant) setOccurredOn(todayInput(activeTenant.timezone));
  }, [activeTenant?.tenantId, activeTenant?.timezone, dateTouched]);
  const members = useQuery({
    queryKey: ['members', activeTenant?.tenantId],
    queryFn: () => api.getMembers(activeTenant!.tenantId),
    enabled: Boolean(activeTenant && canManageMembers && (showMemberFilter || showOwnerSelector || editing)),
  });
  const profile = useQuery({ queryKey: ['profile', activeTenant?.tenantId], queryFn: () => api.getProfile(activeTenant!.tenantId), enabled: Boolean(activeTenant) });
  const notificationPreferences = useQuery({ queryKey: ['notification-preferences', activeTenant?.tenantId],
    queryFn: () => api.getNotificationPreferences(activeTenant!.tenantId), enabled: Boolean(activeTenant) });
  const summary = useQuery({ queryKey: ['summary', activeTenant?.tenantId], queryFn: () => api.getSummary(activeTenant!.tenantId), enabled: Boolean(activeTenant) });
  const budgets = useQuery({ queryKey: ['budgets', activeTenant?.tenantId], queryFn: () => api.getBudgets(activeTenant!.tenantId), enabled: Boolean(activeTenant) });
  const debts = useQuery({ queryKey: ['debts', activeTenant?.tenantId], queryFn: () => api.getDebts(activeTenant!.tenantId), enabled: Boolean(activeTenant) });
  const transactions = useInfiniteQuery({
    queryKey: ['transactions', activeTenant?.tenantId, filterType, search, from, to,
      canManageMembers ? memberFilter : undefined],
    queryFn: ({ pageParam }) => api.getTransactions(activeTenant!.tenantId, {
      cursor: pageParam, type: filterType === 'all' ? undefined : filterType,
      search: search.trim() || undefined, from: from || undefined, to: to || undefined,
      memberId: canManageMembers ? memberFilter : undefined,
    }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(activeTenant),
  });
  const createTenant = useMutation({
    mutationFn: ({ name, memberName, timezone, income }: { name: string; memberName: string; timezone: string; income: number | null }) =>
      api.createTenant(name, timezone, memberName, income),
    onSuccess: async (created, variables) => {
      setTenantId(created.tenantId);
      await queryClient.invalidateQueries({ queryKey: ['tenants'] });
      if (variables.income !== null) await offerIncomeBudget(created.tenantId, variables.income);
    },
  });
  const createTransaction = useMutation({
    mutationFn: (request: CreateTransaction) => api.createTransaction(activeTenant!.tenantId, request),
    onSuccess: async (created) => {
      setTransactionBudgetAlerts(created.budgetAlerts ?? []);
      setAmount('');
      setDescription('');
      setSource('manual');
      setOwnerUserId('');
      setShowOwnerSelector(false);
      setDateTouched(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['transactions', activeTenant?.tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['summary', activeTenant?.tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['budgets', activeTenant?.tenantId] }),
      ]);
    },
  });
  const createTransactionDraft = useMutation({
    mutationFn: (text: string) => api.createTransactionDraft(activeTenant!.tenantId, text),
    onSuccess: (draft) => {
      setTransactionDraft(draft);
      setDraftFields(draftForm(draft, activeTenant?.timezone ?? 'UTC'));
      setDraftConfirmKey(crypto.randomUUID());
      setDraftText('');
    },
  });
  const confirmTransactionDraft = useMutation({
    mutationFn: async () => {
      if (!transactionDraft || !draftFields || !activeTenant) throw new Error(t.error);
      let current = transactionDraft;
      const request = draftRequest(draftFields, current, activeTenant.timezone);
      const unchanged = request.type === current.type && request.amount === current.amount
        && request.currency === current.currency && request.categoryCode === current.categoryCode
        && request.subcategoryCode === current.subcategoryCode && request.description === current.description
        && request.occurredAt === current.occurredAt && request.debtId === current.debtId;
      if (!unchanged) {
        current = await api.updateTransactionDraft(activeTenant.tenantId, current.id, current.version, request);
        setTransactionDraft(current);
      }
      return api.confirmTransactionDraft(activeTenant.tenantId, current.id, current.version, draftConfirmKey);
    },
    onSuccess: async (created) => {
      setTransactionBudgetAlerts(created.budgetAlerts ?? []);
      setTransactionDraft(null);
      setDraftFields(null);
      setDraftConfirmKey('');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['transactions', activeTenant?.tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['summary', activeTenant?.tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['budgets', activeTenant?.tenantId] }),
      ]);
    },
  });
  const cancelTransactionDraft = useMutation({
    mutationFn: () => {
      if (!transactionDraft) throw new Error(t.error);
      return api.cancelTransactionDraft(activeTenant!.tenantId, transactionDraft.id, transactionDraft.version);
    },
    onSuccess: () => {
      setTransactionDraft(null);
      setDraftFields(null);
      setDraftConfirmKey('');
    },
  });
  const updateTransaction = useMutation({
    mutationFn: (request: UpdateTransaction) => api.updateTransaction(activeTenant!.tenantId, editing!.id, editing!.version, request),
    onSuccess: async () => {
      setEditing(null);
      setAmount('');
      setDescription('');
      setOwnerUserId('');
      setDebtId(null);
      setType('expense');
      setSource('manual');
      setDateTouched(false);
      await queryClient.invalidateQueries({ queryKey: ['transactions', activeTenant?.tenantId] });
    },
  });
  const voidTransaction = useMutation({
    mutationFn: (item: Transaction) => api.voidTransaction(activeTenant!.tenantId, item.id, item.version),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['transactions', activeTenant?.tenantId] }),
  });
  const updateBudget = useMutation({
    mutationFn: ({ budgetKey, scope, amount, period }: { budgetKey: string; scope: 'family' | 'personal'; amount: string; period: 'monthly' | 'rolling7' }) => {
      const overview = budgets.data!;
      const version = period === 'rolling7'
        ? (scope === 'family' ? overview.familyRolling7FoodVersion : overview.personalRolling7FoodVersion)
        : budgetKey === '__total__'
        ? (scope === 'family' ? overview.familyTotalVersion : overview.personalTotalVersion)
        : (scope === 'family' ? overview.familyVersions[budgetKey] : overview.personalVersions[budgetKey]) ?? 0;
      return api.updateBudget(activeTenant!.tenantId, budgetKey, scope, amount, version, period);
    },
    onSuccess: (value) => queryClient.setQueryData(['budgets', activeTenant?.tenantId], value),
  });
  const resetPersonalBudgets = useMutation({
    mutationFn: () => api.resetPersonalBudgets(activeTenant!.tenantId),
    onSuccess: (value) => queryClient.setQueryData(['budgets', activeTenant?.tenantId], value),
  });
  const createBudgetProposal = useMutation({
    mutationFn: () => api.createBudgetProposal(activeTenant!.tenantId, Number(profile.data!.plannedIncome).toFixed(2)),
    onSuccess: (proposal) => setBudgetProposal({ tenantId: activeTenant!.tenantId, proposal }),
  });
  const createHistoryBudgetProposal = useMutation({
    mutationFn: () => api.createHistoryBudgetProposal(activeTenant!.tenantId),
    onSuccess: (proposal) => setBudgetProposal({ tenantId: activeTenant!.tenantId, proposal }),
  });
  const applyBudgetProposal = useMutation({
    mutationFn: () => api.applyBudgetProposal(activeTenant!.tenantId, budgetProposal!.proposal.id),
    onSuccess: (value) => {
      queryClient.setQueryData(['budgets', activeTenant?.tenantId], value);
      setBudgetProposal(null);
      setShowBudgetSetup(false);
    },
  });
  const createDebt = useMutation({
    mutationFn: (value: { name: string; openingBalance: string; interestRate: string; minimumPayment: string }) => api.createDebt(activeTenant!.tenantId, value),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['debts', activeTenant?.tenantId] }),
  });
  const payDebt = useMutation({
    mutationFn: ({ debtId, version, amount }: { debtId: string; version: number; amount: string }) => api.payDebt(activeTenant!.tenantId, debtId, version, amount),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ['debts', activeTenant?.tenantId] }),
      queryClient.invalidateQueries({ queryKey: ['transactions', activeTenant?.tenantId] }),
      queryClient.invalidateQueries({ queryKey: ['summary', activeTenant?.tenantId] }),
    ]),
  });
  const adjustDebt = useMutation({
    mutationFn: ({ debtId, version, balance }: { debtId: string; version: number; balance: string }) => api.adjustDebtBalance(activeTenant!.tenantId, debtId, version, balance),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['debts', activeTenant?.tenantId] }),
  });
  const updateProfile = useMutation({
    mutationFn: (value: Pick<MemberProfile, 'displayName' | 'plannedIncome' | 'onboardingState'>) =>
      api.updateProfile(activeTenant!.tenantId, value),
    onSuccess: async (value) => {
      queryClient.setQueryData(['profile', activeTenant?.tenantId], value);
      if (repeatSetup) {
        setRepeatSetup(false);
        if (value.plannedIncome !== null && activeTenant) {
          await offerIncomeBudget(activeTenant.tenantId, value.plannedIncome);
          navigate('/');
        }
      }
    },
  });
  const updateNotificationPreferences = useMutation({
    mutationFn: ({ version, ...preferences }: UpdateNotificationPreferences & { version: number }) =>
      api.updateNotificationPreferences(activeTenant!.tenantId, version, preferences),
    onSuccess: (value) => queryClient.setQueryData(['notification-preferences', activeTenant?.tenantId], value),
  });
  const createTelegramLink = useMutation({
    mutationFn: api.createTelegramLinkCode,
    onMutate: () => setTelegramLinkCode(null),
    onSuccess: setTelegramLinkCode,
  });

  const allTransactions = useMemo(
    () => transactions.data?.pages.flatMap((page) => page.items) ?? [],
    [transactions.data],
  );
  const visibleTransactions = allTransactions;

  if (session.isPending) return <main className="center-state" aria-live="polite">{t.loading}</main>;
  if (session.isError || tenants.isError) {
    return <main className="center-state"><p role="alert">{t.error}</p>
      <button className="button button-primary" onClick={() => void queryClient.invalidateQueries()}>{t.retry}</button>
    </main>;
  }
  if (!session.data?.authenticated) {
    return <main className="login-page">
      <div className="login-card">
        <Brand t={t} language={language} setLanguage={setLanguage} />
        <div className="login-copy"><span className="eyebrow">PERSONAL FINANCE</span>
          <h1>{t.loginTitle}</h1><p>{t.loginText}</p></div>
        <a className="button button-primary login-button" href="/oauth2/authorization/keycloak">{t.login}</a>
      </div>
    </main>;
  }
  if (tenants.isPending) return <main className="center-state" aria-live="polite">{t.loading}</main>;

  return <div className="app-shell">
    <aside className="sidebar">
      <Brand t={t} language={language} setLanguage={setLanguage} />
      <nav aria-label={t.workspace}>
        <Link className="nav-link active" to="/dashboard">{t.workspace}</Link>
        <Link className="nav-link" to="/transactions">{t.operations}</Link>
        <Link className="nav-link" to="/receipts">{t.receipts}</Link>
        <Link className="nav-link" to="/products">{t.products}</Link>
        <Link className="nav-link" to="/shopping">{t.shopping}</Link>
        <Link className="nav-link" to="/inflation">{t.inflation}</Link>
        <Link className="nav-link" to="/imports">{t.imports}</Link>
        <Link className="nav-link" to="/budgets">{t.budgets}</Link>
        <Link className="nav-link" to="/debts">{t.debts}</Link>
        <Link className="nav-link" to="/reports">{t.reports}</Link>
        <Link className="nav-link" to="/profile">{t.profile}</Link>
      </nav>
      <div className="sidebar-footer">
        <span className="user-avatar" aria-hidden="true">{session.data.displayName.slice(0, 1) || 'F'}</span>
        <span className="user-name">{session.data.displayName || 'Finance'}</span>
        <button className="button button-quiet" onClick={() => void api.logout().then(() => window.location.assign('/'))}>{t.logout}</button>
      </div>
    </aside>
    <main className="main-content">
      {!activeTenant ? <Onboarding t={t} onCreate={(name, memberName, timezone, income) => createTenant.mutate({ name, memberName, timezone, income })}
        pending={createTenant.isPending} error={createTenant.error?.message} /> : <>
        <header className="page-header">
          <div><span className="eyebrow">{t.workspace}</span><h1>{activeTenant.displayName}</h1>
            <p>{t.transactionText}</p></div>
          {tenants.data && tenants.data.length > 1 && <label className="tenant-select">{t.workspace}
            <select value={activeTenant.tenantId} onChange={(event) => setTenantId(event.target.value)}>
              {tenants.data.map((tenant) => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.displayName}</option>)}
            </select>
          </label>}
        </header>
        <Routes>
          <Route path="/profile" element={repeatSetup
            ? <Onboarding t={t} mode="repeat" initialMemberName={profile.data?.displayName}
              initialIncome={profile.data?.plannedIncome} pending={updateProfile.isPending}
              error={updateProfile.error?.message}
              onSaveProfile={(displayName, plannedIncome) => updateProfile.mutate({
                displayName, plannedIncome, onboardingState: 'complete',
              })}
              onCancel={() => setRepeatSetup(false)} />
            : <ProfilePanel t={t} profile={profile.data}
              notificationPreferences={notificationPreferences.data}
              pending={profile.isPending || notificationPreferences.isPending || updateProfile.isPending ||
                updateNotificationPreferences.isPending || createTelegramLink.isPending}
              error={profile.error?.message ?? notificationPreferences.error?.message ?? updateProfile.error?.message ??
                updateNotificationPreferences.error?.message ?? createTelegramLink.error?.message}
              telegramLinkCode={telegramLinkCode} onCreateTelegramLink={() => createTelegramLink.mutate()}
              onRetry={() => { void profile.refetch(); void notificationPreferences.refetch(); }}
              onSave={(value) => updateProfile.mutate(value)}
              onSaveNotificationPreferences={(value) => updateNotificationPreferences.mutate(value)}
              onRepeatSetup={() => setRepeatSetup(true)} />} />
          <Route path="/budgets" element={<BudgetPanel t={t} language={language} budgets={budgets.data} pending={budgets.isPending}
            error={budgetSetupError ?? budgets.error?.message ?? updateBudget.error?.message ?? resetPersonalBudgets.error?.message
              ?? createBudgetProposal.error?.message ?? createHistoryBudgetProposal.error?.message ?? applyBudgetProposal.error?.message}
            canEditFamily={['owner', 'admin'].includes(activeTenant.role)}
            canEdit={activeTenant.role !== 'viewer'}
            plannedIncome={profile.data?.plannedIncome ?? null}
            proposal={budgetProposal?.tenantId === activeTenant.tenantId ? budgetProposal.proposal : undefined}
            proposalPending={createBudgetProposal.isPending || createHistoryBudgetProposal.isPending || applyBudgetProposal.isPending}
            onRetry={() => void budgets.refetch()}
            onSave={(budgetKey, scope, amount, period = 'monthly') => updateBudget.mutate({ budgetKey, scope, amount, period })}
            onPropose={() => createBudgetProposal.mutate()}
            onProposeHistory={() => createHistoryBudgetProposal.mutate()}
            onApplyProposal={() => applyBudgetProposal.mutate()}
            onDismissProposal={() => { setBudgetProposal(null); setBudgetSetupError(undefined); }}
            onReset={() => resetPersonalBudgets.mutate()} />} />
          <Route path="/debts" element={<DebtPanel t={t} debts={debts.data?.items} pending={debts.isPending}
            error={debts.error?.message ?? createDebt.error?.message ?? payDebt.error?.message ?? adjustDebt.error?.message}
            canManage={activeTenant.role !== 'viewer'} onRetry={() => void debts.refetch()}
            onCreate={(value) => createDebt.mutate(value)}
            onPay={(debtId, version, amount) => payDebt.mutate({ debtId, version, amount })}
            onAdjust={(debtId, version, balance) => adjustDebt.mutate({ debtId, version, balance })}
            onForecast={(debtId) => api.getDebtForecast(activeTenant.tenantId, debtId)} />} />
          <Route path="/reports" element={<ReportPanel t={t} tenantId={activeTenant.tenantId} language={language} />} />
          <Route path="/receipts" element={<ReceiptsPanel tenantId={activeTenant.tenantId}
            language={language} canWrite={activeTenant.role !== 'viewer'} />} />
          <Route path="/products" element={<ProductCatalogPanel tenantId={activeTenant.tenantId} language={language} />} />
          <Route path="/shopping" element={<ShoppingPanel tenantId={activeTenant.tenantId} language={language} />} />
          <Route path="/inflation" element={<PersonalInflationPanel tenantId={activeTenant.tenantId} language={language} />} />
          <Route path="/imports" element={<ImportsPanel tenantId={activeTenant.tenantId} language={language} />} />
          <Route path="/" element={showBudgetSetup ? <OnboardingBudgetChoice t={t}
            proposal={budgetProposal?.tenantId === activeTenant.tenantId ? budgetProposal.proposal : undefined}
            pending={budgetSetupPending || applyBudgetProposal.isPending}
            error={budgetSetupError ?? applyBudgetProposal.error?.message}
            onApply={() => applyBudgetProposal.mutate()}
            onKeepCurrent={() => { setShowBudgetSetup(false); setBudgetProposal(null); setBudgetSetupError(undefined); }} />
            : <Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard t={t} summary={summary.data} error={summary.error?.message}
            budgets={budgets.data} pending={summary.isPending} onRetry={() => void summary.refetch()} />} />
          <Route path="/transactions" element={<section className="transactions-layout">
            <form className="transaction-form panel" onSubmit={(event) => {
              event.preventDefault();
              const normalized = amount.trim().replace(',', '.');
              if (!/^(?:0\.(?:0?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\.[0-9]{1,2})?)$/.test(normalized)) return;
              const request: UpdateTransaction = {
                type, amount: normalized, currency: 'RUB', categoryCode: category.trim() || 'other',
                subcategoryCode: subcategory.trim() || null,
                description: description.trim(), source: source.trim() || 'manual',
                ownerUserId: canManageMembers && (editing || showOwnerSelector)
                  ? ownerUserId || activeTenant?.userId : undefined,
                ...(editing && type === 'debt_payment' ? { debtId } : {}),
                occurredAt: noonInZone(occurredOn, activeTenant?.timezone ?? 'UTC'),
              };
              if (editing) updateTransaction.mutate(request);
              else createTransaction.mutate(request);
            }}>
              <fieldset className="transaction-fields" disabled={!canWriteTransactions}>
              <div className="panel-heading"><div><span className="eyebrow">{t.operations}</span><h2>{editing ? t.update : t.save}</h2></div></div>
              <label>{t.draftTextLabel}<textarea maxLength={500} rows={2} value={draftText}
                placeholder={t.draftTextHint} onChange={(event) => setDraftText(event.target.value)} /></label>
              {(createTransactionDraft.error || confirmTransactionDraft.error || cancelTransactionDraft.error)
                && <p className="form-error" role="alert">{(createTransactionDraft.error ?? confirmTransactionDraft.error ?? cancelTransactionDraft.error)?.message}</p>}
              <button className="button button-quiet" type="button" disabled={!draftText.trim() || createTransactionDraft.isPending}
                onClick={() => createTransactionDraft.mutate(draftText.trim())}>
                {createTransactionDraft.isError ? t.retry : createTransactionDraft.isPending ? t.loading : t.parseText}
              </button>
              {transactionDraft && draftFields && <section className="draft-review" aria-label={t.draftReview}>
                <h3>{t.draftReview}</h3>
                <small>{t.draftModel}: {transactionDraft.provider} · {transactionDraft.modelVersion} · {transactionDraft.promptVersion}</small>
                <label>{t.draftType}<select value={draftFields.type} onChange={(event) => {
                  const nextType = event.target.value as DraftForm['type'];
                  setDraftFields({ ...draftFields, type: nextType, debtId: nextType === 'debt_payment' ? draftFields.debtId : null,
                    categoryCode: nextType === 'debt_payment' ? 'долги' : draftFields.categoryCode });
                }}>
                  <option value="expense">{t.expense}</option><option value="income">{t.income}</option>
                  <option value="debt_payment">{t.debtPayment}</option>
                </select></label>
                <label>{t.draftAmount}<input inputMode="decimal" value={draftFields.amount}
                  onChange={(event) => setDraftFields({ ...draftFields, amount: event.target.value })} /></label>
                <div className="quick-amounts" role="group" aria-label={t.quickAmount}>
                  {[100, 500, 1000, 2000].map((value) => <button key={value} className="button button-quiet"
                    type="button" aria-label={`${t.quickAmount} ${value}`} onClick={() => setDraftFields({ ...draftFields, amount: `${value}.00` })}>
                    {value}
                  </button>)}
                </div>
                <label>{t.draftDescription}<input maxLength={500} value={draftFields.description}
                  onChange={(event) => setDraftFields({ ...draftFields, description: event.target.value })} /></label>
                <label>{t.draftCategory}<input maxLength={64} value={draftFields.categoryCode}
                  onChange={(event) => setDraftFields({ ...draftFields, categoryCode: event.target.value })} /></label>
                <label>{t.draftSubcategory}<input maxLength={64} value={draftFields.subcategoryCode ?? ''}
                  onChange={(event) => setDraftFields({ ...draftFields, subcategoryCode: event.target.value || null })} /></label>
                <label>{t.draftDate}<input type="date" value={draftFields.occurredOn}
                  onChange={(event) => setDraftFields({ ...draftFields, occurredOn: event.target.value })} /></label>
                {draftFields.type === 'debt_payment' && <label>{t.debtChoice}<select value={draftFields.debtId ?? ''}
                  onChange={(event) => setDraftFields({ ...draftFields, debtId: event.target.value || null })}>
                  <option value="">—</option>
                  {debts.data?.items.filter((debt) => debt.status === 'open').map((debt) => <option key={debt.id} value={debt.id}>{debt.name}</option>)}
                </select></label>}
                <button className="button button-primary" type="button" disabled={confirmTransactionDraft.isPending
                  || draftFields.type === 'debt_payment' && !draftFields.debtId}
                  onClick={() => confirmTransactionDraft.mutate()}>
                  {confirmTransactionDraft.isPending ? t.loading : t.confirmDraft}
                </button>
                <button className="button button-quiet" type="button" disabled={cancelTransactionDraft.isPending}
                  onClick={() => cancelTransactionDraft.mutate()}>{t.cancelDraft}</button>
              </section>}
              <label>{t.type}<select value={type} onChange={(event) => setType(event.target.value as CreateTransaction['type'])}>
                <option value="expense">{t.expense}</option><option value="income">{t.income}</option>
                <option value="refund">{t.refund}</option><option value="transfer">{t.transfer}</option>
                {editing && <option value="debt_payment">{t.debtPayment}</option>}
              </select></label>
              {editing && type === 'debt_payment' && <label>{t.debtChoice}<select required value={debtId ?? ''}
                onChange={(event) => setDebtId(event.target.value || null)}>
                <option value="">—</option>
                {debts.data?.items.filter((debt) => debt.status === 'open' || debt.id === editing.debtId)
                  .map((debt) => <option key={debt.id} value={debt.id}>{debt.name}</option>)}
              </select></label>}
              {canManageMembers && (editing || showOwnerSelector) && <label>{t.transactionOwner}<select
                value={ownerUserId || activeTenant?.userId || ''} onChange={(event) => setOwnerUserId(event.target.value)}>
                {(members.data ?? []).map((member: TenantMember) =>
                  <option key={member.userId} value={member.userId}>{member.displayName}</option>)}
              </select></label>}
              {canManageMembers && !editing && <button className="button button-quiet" type="button"
                aria-expanded={showOwnerSelector} onClick={() => setShowOwnerSelector((visible) => !visible)}>
                {t.assignMember}
              </button>}
              <label>{t.amount}<input required inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
              <label>{t.description}<input required maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
              <label>{t.category}<input required maxLength={64} value={category} onChange={(event) => setCategory(event.target.value)} /></label>
              <label>{t.subcategory}<input maxLength={64} value={subcategory} onChange={(event) => setSubcategory(event.target.value)} /></label>
              <label>{t.source}<input maxLength={64} value={source} onChange={(event) => setSource(event.target.value)} /></label>
              <label>{t.date}<input required type="date" value={occurredOn} onChange={(event) => {
                setOccurredOn(event.target.value);
                setDateTouched(true);
              }} /></label>
              {(createTransaction.error || updateTransaction.error) && <p className="form-error" role="alert">{(createTransaction.error ?? updateTransaction.error)?.message}</p>}
              <button className="button button-primary" disabled={createTransaction.isPending || updateTransaction.isPending}>{editing ? t.update : t.save}</button>
              {transactionBudgetAlerts.length > 0 && <div className="budget-alert-notice" role="status" aria-live="polite">
                {transactionBudgetAlerts.map((alert) => {
                  const label = alert.budgetKey === '__total__' ? t.budgetTotal : alert.budgetKey;
                  const number = (value: string) => Number(value).toLocaleString(language === 'ru' ? 'ru-RU' : 'en-US',
                    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                  return <p key={`${alert.budgetKey}-${alert.threshold}`}>
                    {budgetStatusLabel(t, alert.threshold)}: <strong>{label}</strong> · {number(alert.spent)} / {number(alert.limit)} RUB
                  </p>;
                })}
              </div>}
              {editing && <button className="button button-quiet" type="button" onClick={() => {
                setEditing(null);
                setDebtId(null);
                setOwnerUserId('');
                setType('expense');
                setDateTouched(false);
              }}>{t.cancelEdit}</button>}
              </fieldset>
            </form>
            <div className="history-panel panel">
              <div className="panel-heading"><div><span className="eyebrow">{t.workspace}</span><h2>{t.transactions}</h2></div>
                <span className="record-count">{visibleTransactions.length}</span></div>
              <div className="filters">
                <label className="search-field"><span className="sr-only">{t.search}</span>
                  <input aria-label={t.search} placeholder={t.search} value={search} onChange={(event) => setSearch(event.target.value)} />
                </label>
                <label><span className="sr-only">{t.filterType}</span><select aria-label={t.filterType} value={filterType}
                  onChange={(event) => setFilterType(event.target.value)}>
                  <option value="all">{t.filterType}</option><option value="expense">{t.expense}</option><option value="income">{t.income}</option>
                  <option value="refund">{t.refund}</option><option value="debt_payment">{t.debtPayment}</option><option value="transfer">{t.transfer}</option>
                </select></label>
                <label><span className="sr-only">{t.from}</span><input aria-label={t.from} type="date" value={from}
                  onChange={(event) => setFrom(event.target.value)} /></label>
                <label><span className="sr-only">{t.to}</span><input aria-label={t.to} type="date" value={to}
                  onChange={(event) => setTo(event.target.value)} /></label>
                {canManageMembers && <>
                  <button className="button button-quiet" type="button" aria-expanded={showMemberFilter}
                    onClick={() => setShowMemberFilter((visible) => !visible)}>{t.filterByMember}</button>
                  {showMemberFilter && <label><span className="sr-only">{t.memberFilter}</span>
                    <select aria-label={t.memberFilter} value={memberFilter} onChange={(event) => setMemberFilter(event.target.value)}>
                      <option value="all">{t.allMembers}</option>
                      {(members.data ?? []).map((member: TenantMember) => <option key={member.userId} value={member.userId}>{member.displayName}</option>)}
                    </select>
                  </label>}
                </>}
              </div>
              {voidTransaction.error && <p className="form-error" role="alert">{voidTransaction.error.message}</p>}
              {transactions.isPending ? <p className="empty-state">{t.loading}</p> : transactions.isError
                ? <div className="empty-state"><p role="alert">{t.error}</p><button className="button button-quiet" onClick={() => void transactions.refetch()}>{t.retry}</button></div>
                : visibleTransactions.length === 0 ? <p className="empty-state">{t.empty}</p>
                  : <div className="transaction-list" aria-label={t.transactions}>
                    {visibleTransactions.map((item) => <TransactionRow key={item.id} item={item} language={language} t={t}
                      canWrite={canWriteTransactions}
                      onEdit={() => {
                        setEditing(item);
                        setAmount(item.amount);
                        setDescription(item.description);
                        setCategory(item.categoryCode);
                        setSubcategory(item.subcategoryCode ?? '');
                        setSource(item.source);
                        setType(item.type as CreateTransaction['type']);
                        setDebtId(item.debtId ?? null);
                        setOwnerUserId(item.ownerUserId ?? '');
                        setOccurredOn(dateInZone(item.occurredAt, activeTenant?.timezone ?? 'UTC'));
                        setDateTouched(true);
                      }}
                      onRepeat={() => createTransaction.mutate({
                        type: item.type as CreateTransaction['type'], amount: item.amount, currency: item.currency,
                        categoryCode: item.categoryCode, subcategoryCode: item.subcategoryCode,
                        description: item.description, source: item.source,
                        occurredAt: noonInZone(todayInput(activeTenant?.timezone), activeTenant?.timezone ?? 'UTC'),
                      })}
                      onVoid={() => voidTransaction.mutate(item)} />)}
                  </div>}
              {transactions.hasNextPage && <button className="button button-quiet load-more" disabled={transactions.isFetchingNextPage}
                onClick={() => void transactions.fetchNextPage()}>{t.loadMore}</button>}
            </div>
          </section>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </>}
    </main>
  </div>;
}

function Brand({ t, language, setLanguage }: { t: Translations; language: Language; setLanguage: (language: Language) => void }) {
  return <div className="brand-row"><a className="brand" href="/" aria-label={t.brand}><span className="brand-mark">F</span>{t.brand}</a>
    <div className="language-switch" aria-label="Language">
      <button aria-pressed={language === 'ru'} onClick={() => setLanguage('ru')}>RU</button>
      <button aria-pressed={language === 'en'} onClick={() => setLanguage('en')}>EN</button>
    </div>
  </div>;
}

function Dashboard({ t, summary, budgets, error, pending, onRetry }: {
  t: Translations; summary?: Summary; budgets?: BudgetOverview; error?: string; pending: boolean; onRetry: () => void;
}) {
  if (pending) return <p className="empty-state">{t.loading}</p>;
  if (error || !summary) return <div className="empty-state"><p role="alert">{error || t.error}</p>
    <button className="button button-quiet" onClick={onRetry}>{t.retry}</button></div>;
  const format = (value: string) => new Intl.NumberFormat('ru-RU', {
    style: 'currency', currency: summary.currency, maximumFractionDigits: 2,
  }).format(Number(value));
  return <section className="dashboard-panel">
    <div className="panel-heading"><div><span className="eyebrow">{summary.month}</span><h2>{t.dashboard}</h2></div></div>
    <div className="summary-grid">
      <article className="summary-card"><span>{t.incomeTotal}</span><strong className="income">{format(summary.incomeTotal)}</strong></article>
      <article className="summary-card"><span>{t.expenseTotal}</span><strong className="expense">{format(summary.expenseTotal)}</strong></article>
      <article className="summary-card"><span>{t.transactionCount}</span><strong>{summary.transactionCount}</strong></article>
    </div>
    {summary.daysElapsed > 0 && summary.dailyExpensePace && <div className="monthly-pace">
      <span>{t.dailyPace}: <strong>{format(summary.dailyExpensePace)}</strong></span>
      {budgets && <span>{t.status}: <strong>{budgetStatusLabel(t, budgets.totalLimitStatus)}</strong></span>}
      {budgets && budgets.totalLimitStatus !== 'disabled' && <span>{t.currentBudgetRemaining}: <strong>
        {format((Number(budgets.effectiveTotalLimit) - Number(budgets.totalMonthlySpent)).toFixed(2))}
      </strong></span>}
      {summary.projectedExpenseTotal && <span>{t.monthProjection}: <strong>{format(summary.projectedExpenseTotal)}</strong></span>}
      {summary.projectedExpenseTotal && budgets && Number(budgets.effectiveTotalLimit) > 0 && (() => {
        const delta = Number(budgets.effectiveTotalLimit) - Number(summary.projectedExpenseTotal);
        return <span>{delta >= 0 ? t.projectedReserve : t.projectedOverrun}: <strong>{format(Math.abs(delta).toFixed(2))}</strong></span>;
      })()}
    </div>}
    {summary.safeToSpend && <div className="monthly-pace safe-to-spend">
      <strong>{t.safeSpendPerDay}: {format(summary.safeToSpend.safePerDay)}</strong>
      <span>{t.safeSpendTotal}: <strong>{format(summary.safeToSpend.safeTotal)}</strong></span>
      <span>{t.safeSpendHorizon} {summary.safeToSpend.horizonDate} · {summary.safeToSpend.daysRemaining} {t.safeSpendDays}</span>
      <span>{t.safeSpendReserve}: <strong>{format(summary.safeToSpend.reserve)}</strong></span>
      <span>{t.promisedPayments}: <strong>{format(summary.safeToSpend.promisedPayments)}</strong></span>
      <span>{summary.safeToSpend.incomeBasis === 'actual_income' ? t.actualIncomeBasis : t.plannedIncomeBasis}: <strong>{format(summary.safeToSpend.incomeBase)}</strong></span>
    </div>}
    <RollingFoodSummary t={t} status={summary.rolling7FoodStatus} format={format} />
    <Link className="button button-primary" to="/transactions">{t.operations}</Link>
  </section>;
}

function RollingFoodSummary({ t, status, format }: {
  t: Translations; status: import('./api').RollingFoodStatus; format: (amount: string) => string;
}) {
  const paceLabel = status.paceStatus === 'over' ? t.foodPaceOver
    : status.paceStatus === 'under' ? t.foodPaceUnder
    : status.paceStatus === 'normal' ? t.foodPaceNormal : t.insufficientFoodHistory;
  return <aside className="rolling-food-summary" aria-label={t.rollingFood}>
    <strong>{t.rollingFood}</strong>
    <span>{t.foodWindow}: {status.fromDate} — {status.toDate}</span>
    <span>{t.rollingSpent}: <strong>{format(status.spent)}</strong></span>
    <span>{t.foodLimit}: <strong>{format(status.limit)}</strong> · {budgetStatusLabel(t, status.limitStatus)}</span>
    {status.remaining !== null && <span>{t.foodRemaining}: <strong>{format(status.remaining)}</strong></span>}
    {status.usualWeeklySpend !== null && <span>{t.usualFoodPace}: <strong>{format(status.usualWeeklySpend)}</strong> · {paceLabel}</span>}
    {status.usualWeeklySpend === null && <span>{paceLabel}</span>}
  </aside>;
}

function budgetStatusLabel(t: Translations, status: string): string {
  switch (status) {
    case 'near': return t.statusNear;
    case 'exceeded': return t.statusExceeded;
    case 'disabled': return t.statusDisabled;
    default: return t.statusNormal;
  }
}

function ReportPanel({ t, tenantId, language }: { t: Translations; tenantId: string; language: Language }) {
  const [period, setPeriod] = useState<FinanceReport['period']>('month');
  const [scope, setScope] = useState<FinanceReport['scope']>('personal');
  const [month, setMonth] = useState(() => todayInput().slice(0, 7));
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const customReady = period !== 'custom' || Boolean(from && to && from <= to);
  const report = useQuery({
    queryKey: ['report', tenantId, period, scope, month, from, to],
    queryFn: () => api.getReport(tenantId, period, scope, month, from, to),
    enabled: customReady,
    retry: false,
  });
  const format = (amount: string) => new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(amount));

  return <section className="report-panel panel">
    <div className="panel-heading"><div><span className="eyebrow">{report.data?.timezone ?? 'RUB'}</span><h2>{t.reports}</h2></div></div>
    <div className="report-controls">
      <label>{t.reportPeriod}<select aria-label={t.reportPeriod} value={period}
        onChange={(event) => setPeriod(event.target.value as FinanceReport['period'])}>
        <option value="month">{t.periodMonth}</option><option value="week">{t.periodWeek}</option>
        <option value="90d">{t.period90d}</option><option value="custom">{t.periodCustom}</option>
      </select></label>
      <label>{t.reportScope}<select aria-label={t.reportScope} value={scope}
        onChange={(event) => setScope(event.target.value as FinanceReport['scope'])}>
        <option value="personal">{t.scopePersonal}</option><option value="family">{t.scopeFamily}</option>
      </select></label>
      {period === 'month' && <label>{t.periodMonth}<input aria-label={t.periodMonth} type="month" value={month}
        onChange={(event) => setMonth(event.target.value)} /></label>}
      {period === 'custom' && <>
        <label>{t.reportFrom}<input aria-label={t.reportFrom} type="date" value={from}
          onChange={(event) => setFrom(event.target.value)} /></label>
        <label>{t.reportTo}<input aria-label={t.reportTo} type="date" value={to}
          onChange={(event) => setTo(event.target.value)} /></label>
      </>}
    </div>
    {!customReady ? <p className="empty-state">{t.reportFrom}</p>
      : report.isPending ? <p className="empty-state">{t.loading}</p>
      : report.isError || !report.data ? <div className="empty-state"><p role="alert">{report.error?.message ?? t.error}</p>
        <button className="button button-quiet" onClick={() => void report.refetch()}>{t.retry}</button></div>
      : <>
        <p className="report-window">{t.reportWindow}: {report.data.fromDate} — {report.data.toDate} · {report.data.timezone}</p>
        <div className="report-summary">
          <article className="summary-card"><span>{t.reportIncome}</span><strong className="income">{format(report.data.incomeTotal)}</strong></article>
          <article className="summary-card"><span>{t.reportExpense}</span><strong className="expense">{format(report.data.expenseTotal)}</strong></article>
          <article className="summary-card"><span>{t.reportDebtPayments}</span><strong>{format(report.data.debtPaymentTotal)}</strong></article>
          <article className="summary-card"><span>{t.reportRefunds}</span><strong>{format(report.data.refundTotal)}</strong></article>
          <article className="summary-card"><span>{t.reportCount}</span><strong>{report.data.transactionCount}</strong></article>
          {report.data.weekendSharePercent !== null && <article className="summary-card">
            <span>{t.weekendShare}</span><strong>{report.data.weekendSharePercent}%</strong>
          </article>}
        </div>
        <RollingFoodSummary t={t} status={report.data.rolling7FoodStatus} format={format} />
        {report.data.monthlyBudgetLimit !== null && report.data.monthlyBudgetRemaining !== null && <p className="report-budget">
          {scope === 'family' ? t.monthlyFamilyBudget : t.monthlyPersonalBudget}: <strong>{format(report.data.monthlyBudgetLimit)}</strong>
          {' · '}{t.currentBudgetRemaining}: <strong>{format(report.data.monthlyBudgetRemaining)}</strong>
        </p>}
        <div className="report-chart-grid">
          {report.data.monthlyBudgetLimit !== null && report.data.monthlyBudgetRemaining !== null
            && Number(report.data.monthlyBudgetLimit) > 0 && <ReportBudgetUsageChart t={t} scope={scope}
              limit={report.data.monthlyBudgetLimit} remaining={report.data.monthlyBudgetRemaining}
              month={report.data.toDate.slice(0, 7)} format={format} />}
          <ReportBarChart label={t.expenseCategories} values={Object.entries(report.data.expenseByCategory)} format={format} empty={t.empty} />
          <ReportBarChart label={t.dailyExpenses} values={Object.entries(report.data.expenseByDay)} format={format} empty={t.empty} />
        </div>
      </>}
  </section>;
}

const budgetRows = [
  ['__total__', 'Общий лимит', 'Total limit'], ['еда', 'Еда', 'Food'], ['транспорт', 'Транспорт', 'Transport'],
  ['жилье', 'Жильё', 'Housing'], ['досуг', 'Досуг', 'Leisure'], ['одежда', 'Одежда', 'Clothing'],
  ['здоровье', 'Здоровье', 'Health'], ['работа', 'Работа', 'Work'], ['техника', 'Техника', 'Electronics'],
  ['долги', 'Долги', 'Debt payments'], ['прочее', 'Прочее', 'Other'],
] as const;

function ReportBarChart({ label, values, format, empty }: {
  label: string; values: Array<[string, string]>; format: (amount: string) => string; empty: string;
}) {
  const max = Math.max(0, ...values.map(([, amount]) => Number(amount)));
  return <figure className="report-chart" role="figure" aria-label={label}>
    <figcaption>{label}</figcaption>
    {values.length === 0 ? <p className="empty-state">{empty}</p> : <ul>
      {values.map(([key, amount]) => <li key={key}>
        <span className="chart-label">{key}</span>
        <span className="chart-track" aria-hidden="true">
          <span className="chart-bar" style={{ width: `${max > 0 ? Math.max(0, Number(amount) / max * 100) : 0}%` }} />
        </span>
        <strong>{format(amount)}</strong>
      </li>)}
    </ul>}
  </figure>;
}

function ReportBudgetUsageChart({ t, scope, limit, remaining, month, format }: {
  t: Translations; scope: FinanceReport['scope']; limit: string; remaining: string; month: string;
  format: (amount: string) => string;
}) {
  const limitAmount = Number(limit);
  const remainingAmount = Number(remaining);
  const spent = Math.max(0, limitAmount - remainingAmount);
  const usage = Math.min(100, spent / limitAmount * 100);
  return <figure className="budget-usage-chart" role="figure" aria-label={t.budgetUsage}>
    <figcaption>{t.budgetUsage} · {month}</figcaption>
    <ul><li>
      <span className="chart-label">{scope === 'family' ? t.monthlyFamilyBudget : t.monthlyPersonalBudget}</span>
      <span className="chart-track" aria-hidden="true"><span className="chart-bar" style={{ width: `${usage}%` }} /></span>
      <strong>{format(spent.toFixed(2))} / {format(limit)}</strong>
    </li></ul>
  </figure>;
}

function BudgetUsageChart({ t, language, budgets }: { t: Translations; language: Language; budgets: BudgetOverview }) {
  const rows = budgetRows.filter(([key]) => key !== '__total__').flatMap(([key, ru, en]) => {
    const limit = Number(budgets.effectiveLimits[key] ?? '0');
    if (limit <= 0) return [];
    const spent = Number(budgets.monthlySpent[key] ?? '0');
    return [{ key, name: language === 'ru' ? ru : en, spent, limit }];
  });
  const totalLimit = Number(budgets.effectiveTotalLimit);
  if (totalLimit > 0) rows.unshift({ key: '__total__', name: t.budgetTotal,
    spent: Number(budgets.totalMonthlySpent), limit: totalLimit });
  return <figure className="budget-usage-chart" role="figure" aria-label={t.budgetUsage}>
    <figcaption>{t.budgetUsage}</figcaption>
    {rows.length === 0 ? <p className="empty-state">{t.statusDisabled}</p> : <ul>
      {rows.map((row) => <li key={row.key}>
        <span className="chart-label">{row.name}</span>
        <span className="chart-track" aria-hidden="true">
          <span className="chart-bar" style={{ width: `${Math.min(100, Math.max(0, row.spent / row.limit * 100))}%` }} />
        </span>
        <strong>{row.spent.toLocaleString(language === 'ru' ? 'ru-RU' : 'en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          {' / '}{row.limit.toLocaleString(language === 'ru' ? 'ru-RU' : 'en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ₽</strong>
      </li>)}
    </ul>}
  </figure>;
}

function DebtPanel({ t, debts, pending, error, canManage, onRetry, onCreate, onPay, onAdjust, onForecast }: {
  t: Translations; debts?: import('./api').Debt[]; pending: boolean; error?: string; canManage: boolean;
  onRetry: () => void;
  onCreate: (value: { name: string; openingBalance: string; interestRate: string; minimumPayment: string }) => void;
  onPay: (debtId: string, version: number, amount: string) => void;
  onAdjust: (debtId: string, version: number, balance: string) => void;
  onForecast: (debtId: string) => Promise<import('./api').DebtForecast>;
}) {
  const [name, setName] = useState('');
  const [openingBalance, setOpeningBalance] = useState('');
  const [interestRate, setInterestRate] = useState('');
  const [minimumPayment, setMinimumPayment] = useState('');
  if (pending && !debts) return <p className="empty-state">{t.loading}</p>;
  if (!debts && error) return <div className="empty-state"><p role="alert">{error}</p>
    <button className="button button-quiet" onClick={onRetry}>{t.retry}</button></div>;
  if (!debts) return <p className="empty-state">{t.error}</p>;
  return <section className="debt-panel panel">
    <div className="panel-heading"><div><span className="eyebrow">RUB</span><h2>{t.debts}</h2></div></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {canManage && <form className="debt-create-form" onSubmit={(event) => {
      event.preventDefault();
      onCreate({ name: name.trim(), openingBalance: openingBalance.trim().replace(',', '.'),
        interestRate: interestRate.trim() || '0.00', minimumPayment: minimumPayment.trim().replace(',', '.') || '0.00' });
      setName(''); setOpeningBalance(''); setInterestRate(''); setMinimumPayment('');
    }}>
      <h3>{t.createDebt}</h3>
      <label>{t.debtName}<input required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} /></label>
      <label>{t.openingBalance}<input required inputMode="decimal" value={openingBalance} onChange={(event) => setOpeningBalance(event.target.value)} /></label>
      <label>{t.interestRate}<input inputMode="decimal" value={interestRate} onChange={(event) => setInterestRate(event.target.value)} /></label>
      <label>{t.minimumPayment}<input inputMode="decimal" value={minimumPayment} onChange={(event) => setMinimumPayment(event.target.value)} /></label>
      <button className="button button-primary">{t.createDebt}</button>
    </form>}
    {debts.length === 0 ? <p className="empty-state">{t.noDebts}</p> : <div className="debt-list">
      {debts.map((debt) => <DebtCard key={`${debt.id}-${debt.version}`} t={t} debt={debt} canManage={canManage}
        onPay={onPay} onAdjust={onAdjust} onForecast={() => onForecast(debt.id)} />)}
    </div>}
  </section>;
}

function DebtCard({ t, debt, canManage, onPay, onAdjust, onForecast }: {
  t: Translations; debt: import('./api').Debt; canManage: boolean;
  onPay: (debtId: string, version: number, amount: string) => void;
  onAdjust: (debtId: string, version: number, balance: string) => void;
  onForecast: () => Promise<import('./api').DebtForecast>;
}) {
  const [payment, setPayment] = useState('');
  const [balance, setBalance] = useState(debt.currentBalance);
  const [forecast, setForecast] = useState<import('./api').DebtForecast | null>(null);
  const [forecastError, setForecastError] = useState('');
  return <article className="debt-card">
    <div className="debt-title"><div><h3>{debt.name}</h3><span>{debt.status === 'open' ? t.debtOpen : t.debtClosed} · {t.currentBalance}: {debt.currentBalance} ₽</span></div>
      <span>{t.interestRate}: {debt.interestRate ?? '0.0000'}% · {t.minimumPayment}: {debt.minimumPayment} ₽</span></div>
    {canManage && debt.status === 'open' && <div className="debt-actions">
      <form onSubmit={(event) => { event.preventDefault(); onPay(debt.id, debt.version, payment.trim().replace(',', '.')); }}>
        <label>{t.debtPayment} {debt.name}<input required inputMode="decimal" value={payment}
          onChange={(event) => setPayment(event.target.value)} /></label>
        <button className="button button-quiet">{t.recordPayment} {debt.name}</button>
      </form>
      <form onSubmit={(event) => { event.preventDefault(); onAdjust(debt.id, debt.version, balance.trim().replace(',', '.')); }}>
        <label>{t.adjustBalance} {debt.name}<input required inputMode="decimal" value={balance}
          onChange={(event) => setBalance(event.target.value)} /></label>
        <button className="button button-quiet">{t.saveBalance} {debt.name}</button>
      </form>
    </div>}
    <button className="button button-quiet" onClick={() => void onForecast().then((value) => { setForecast(value); setForecastError(''); })
      .catch((reason: unknown) => setForecastError(reason instanceof Error ? reason.message : t.error))}>{t.forecast} {debt.name}</button>
    {forecastError && <p role="alert" className="form-error">{forecastError}</p>}
    {forecast && <p className="debt-forecast">{forecast.monthsToPayoff === null ? '—' : `${forecast.monthsToPayoff} ${t.months}`} · {forecast.estimateBasis}</p>}
  </article>;
}

function BudgetPanel({ t, language, budgets, pending, error, canEditFamily, canEdit, plannedIncome, proposal,
  proposalPending, onRetry, onSave, onPropose, onProposeHistory, onApplyProposal, onDismissProposal, onReset }: {
  t: Translations; language: Language; budgets?: BudgetOverview; pending: boolean; error?: string;
  canEditFamily: boolean; canEdit: boolean; plannedIncome: number | null; proposal?: BudgetProposal; proposalPending: boolean;
  onRetry: () => void;
  onSave: (key: string, scope: 'family' | 'personal', amount: string, period?: 'monthly' | 'rolling7') => void;
  onPropose: () => void; onProposeHistory: () => void; onApplyProposal: () => void;
  onDismissProposal: () => void; onReset: () => void;
}) {
  if (pending && !budgets) return <p className="empty-state">{t.loading}</p>;
  if (!budgets && error) return <div className="empty-state"><p role="alert">{error}</p>
    <button className="button button-quiet" onClick={onRetry}>{t.retry}</button></div>;
  if (!budgets) return <p className="empty-state">{t.error}</p>;
  return <section className="budget-panel panel">
    <div className="panel-heading"><div><span className="eyebrow">{budgets.currency}</span><h2>{t.budgets}</h2></div></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {canEditFamily && <div className="budget-proposal">
      <div className="panel-heading"><div><h3>{t.proposeBudget}</h3></div>
        {!proposal && <div className="button-row">
          <button className="button button-quiet" disabled={proposalPending || plannedIncome === null}
            onClick={onPropose}>{t.proposeBudget}</button>
          <button className="button button-quiet" disabled={proposalPending || plannedIncome === null}
            onClick={onProposeHistory}>{t.proposeHistoryBudget}</button>
        </div>}
      </div>
      {plannedIncome === null && !proposal && <p>{t.incomeNeeded}</p>}
      {proposal && <>
        <p>{t.proposedTotal}: <strong>{proposal.totalLimit} ₽</strong></p>
        {proposal.proposalSource === 'history_ai' && <p className="proposal-details">
          {t.historyProposalDetails.replace('{days}', String(proposal.historyDays))
            .replace('{model}', proposal.modelVersion ?? '—')}
        </p>}
        <ul>{Object.entries(proposal.limits).map(([key, value]) => <li key={key}>
          {budgetRows.find(([budgetKey]) => budgetKey === key)?.[language === 'ru' ? 1 : 2] ?? key}: {value} ₽
        </li>)}</ul>
        <div className="button-row">
          <button className="button button-primary" disabled={proposalPending} onClick={onApplyProposal}>{t.applyProposal}</button>
          <button className="button button-quiet" disabled={proposalPending} onClick={onDismissProposal}>{t.keepCurrentBudget}</button>
        </div>
      </>}
    </div>}
    <BudgetUsageChart t={t} language={language} budgets={budgets} />
    <div className="budget-list">
      {budgetRows.map(([key, ru, en]) => {
        const isTotal = key === '__total__';
        const family = isTotal ? budgets.familyTotalLimit : budgets.familyLimits[key];
        const personal = isTotal ? budgets.personalTotalOverride : budgets.personalOverrides[key];
        const effective = isTotal ? budgets.effectiveTotalLimit : budgets.effectiveLimits[key];
        const name = language === 'ru' ? ru : en;
        const spent = isTotal ? budgets.totalMonthlySpent : budgets.monthlySpent[key] ?? '0.00';
        const status = isTotal ? budgets.totalLimitStatus : budgets.limitStatus[key] ?? 'disabled';
        return <BudgetRow key={key} budgetKey={key} name={name} month={budgets.month} familyAmount={family ?? '0.00'}
          personalAmount={personal} effectiveAmount={effective ?? '0.00'} spent={spent} status={status} canEditFamily={canEditFamily}
          canEdit={canEdit} t={t} onSave={onSave} />;
      })}
      <RollingFoodRow budgets={budgets} canEditFamily={canEditFamily} canEdit={canEdit} t={t} onSave={onSave} />
    </div>
    {canEdit && <button className="button button-quiet" onClick={onReset}>{t.resetPersonal}</button>}
  </section>;
}

function BudgetRow({ budgetKey, name, month, familyAmount, personalAmount, effectiveAmount, spent, status, canEditFamily, canEdit, t, onSave }: {
  budgetKey: string; name: string; month: string; familyAmount: string; personalAmount: string | null | undefined;
  effectiveAmount: string; spent: string; status: string; canEditFamily: boolean; canEdit: boolean; t: Translations;
  onSave: (key: string, scope: 'family' | 'personal', amount: string, period?: 'monthly' | 'rolling7') => void;
}) {
  const [scope, setScope] = useState<'family' | 'personal'>(canEditFamily ? 'family' : 'personal');
  const [amount, setAmount] = useState(familyAmount);
  const label = `${t.budgetLimitLabel} ${name}`;
  const scopeLabel = `${t.budgetScopeLabel} ${name}`;
  return <div className="budget-row">
    <div><strong>{name}</strong><span>{t.spentThisMonth}: {spent} ₽ · {status === 'near' ? t.statusNear
      : status === 'exceeded' ? t.statusExceeded : status === 'disabled' ? t.statusDisabled : t.statusNormal}</span>
      <span>{t.activeLimit}: {effectiveAmount} ₽ · {month}</span></div>
    {canEditFamily && <label>{scopeLabel}<select value={scope} onChange={(event) => {
      const next = event.target.value as 'family' | 'personal';
      setScope(next);
      setAmount(next === 'personal' ? (personalAmount ?? effectiveAmount) : familyAmount);
    }}>
      <option value="family">{t.familyScope}</option><option value="personal">{t.personalScope}</option>
    </select></label>}
    <label>{label}<input inputMode="decimal" value={amount} disabled={!canEdit}
      onChange={(event) => setAmount(event.target.value)} /></label>
    <button className="button button-quiet" disabled={!canEdit} onClick={() => onSave(budgetKey, scope, amount)}>
      {t.saveLimit} {name}
    </button>
  </div>;
}

function RollingFoodRow({ budgets, canEditFamily, canEdit, t, onSave }: {
  budgets: BudgetOverview; canEditFamily: boolean; canEdit: boolean; t: Translations;
  onSave: (key: string, scope: 'family' | 'personal', amount: string, period?: 'monthly' | 'rolling7') => void;
}) {
  const [scope, setScope] = useState<'family' | 'personal'>(canEditFamily ? 'family' : 'personal');
  const [amount, setAmount] = useState(budgets.rolling7FoodLimit);
  const scopeLabel = `${t.budgetScopeLabel} ${t.rollingFood}`;
  return <div className="budget-row">
    <div><strong>{t.rollingFood}</strong><span>{t.rollingSpent}: {budgets.rolling7FoodSpent} ₽ · {budgets.rolling7FoodLimitStatus === 'near' ? t.statusNear
      : budgets.rolling7FoodLimitStatus === 'exceeded' ? t.statusExceeded
        : budgets.rolling7FoodLimitStatus === 'disabled' ? t.statusDisabled : t.statusNormal}</span>
      <span>{t.activeLimit}: {budgets.effectiveRolling7FoodLimit} ₽</span></div>
    {canEditFamily && <label>{scopeLabel}<select value={scope} onChange={(event) => {
      const next = event.target.value as 'family' | 'personal';
      setScope(next);
      setAmount(next === 'personal' ? (budgets.personalRolling7FoodOverride ?? budgets.effectiveRolling7FoodLimit) : budgets.rolling7FoodLimit);
    }}>
      <option value="family">{t.familyScope}</option><option value="personal">{t.personalScope}</option>
    </select></label>}
    <label>{t.budgetLimitLabel} {t.rollingFood}<input inputMode="decimal" value={amount} disabled={!canEdit}
      onChange={(event) => setAmount(event.target.value)} /></label>
    <button className="button button-quiet" disabled={!canEdit} onClick={() => onSave('еда', scope, amount, 'rolling7')}>
      {t.saveLimit} {t.rollingFood}
    </button>
  </div>;
}

function Onboarding({ t, onCreate, pending, error, mode = 'new', initialMemberName = '', initialIncome,
  onSaveProfile, onCancel }: {
  t: Translations; onCreate?: (name: string, memberName: string, timezone: string, income: number | null) => void;
  pending: boolean; error?: string; mode?: 'new' | 'repeat'; initialMemberName?: string;
  initialIncome?: number | null; onSaveProfile?: (memberName: string, income: number | null) => void;
  onCancel?: () => void;
}) {
  const isRepeat = mode === 'repeat';
  const [step, setStep] = useState(0);
  const [name, setName] = useState('');
  const [memberName, setMemberName] = useState(initialMemberName);
  const [income, setIncome] = useState(initialIncome == null ? '' : String(initialIncome));
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  if (step === 0) return <section className="onboarding-card panel">
    <span className="eyebrow">01 / 03</span><h2>{isRepeat ? t.repeatSetup : t.onboarding}</h2><p>{t.onboardingText}</p>
    {onCancel && <button className="button button-quiet" type="button" onClick={onCancel}>{t.back}</button>}
    <button className="button button-primary" type="button" onClick={() => setStep(1)}>{t.startSetup}</button>
  </section>;
  return <section className="onboarding-card panel">
    <span className="eyebrow">0{step + 1} / 03</span>
    <h2>{step === 1 ? t.onboarding : t.plannedIncome}</h2>
    <p>{step === 1 ? t.onboardingText : t.incomeStepText}</p>
    <form onSubmit={(event) => {
      event.preventDefault();
      if (step === 1) { setStep(2); return; }
      const normalized = income.trim().replace(',', '.');
      const plannedIncome = normalized ? Number(normalized) : null;
      if (plannedIncome !== null && (!Number.isFinite(plannedIncome) || plannedIncome <= 0 || !/^\d+(?:\.\d{1,2})?$/.test(normalized))) return;
      if (isRepeat) onSaveProfile?.(memberName.trim(), plannedIncome);
      else onCreate?.(name.trim(), memberName.trim(), timezone, plannedIncome);
    }}>
      {step === 1 ? <>
        {!isRepeat && <label>{t.name}<input autoFocus required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} /></label>}
        <label>{t.memberName}<input required maxLength={120} value={memberName} onChange={(event) => setMemberName(event.target.value)} /></label>
        {!isRepeat && <label>{t.timezone}<input value={timezone} readOnly /></label>}
      </> : <>
        <label>{t.plannedIncome}<input autoFocus inputMode="decimal" value={income} onChange={(event) => setIncome(event.target.value)} /></label>
        <button className="button button-quiet" type="button" onClick={() => setIncome('')}>{t.skipIncome}</button>
      </>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="button-row">
        <button className="button button-quiet" type="button" disabled={pending} onClick={() => setStep(step - 1)}>{t.back}</button>
        <button className="button button-primary" disabled={pending}>
          {step === 1 ? t.next : isRepeat ? t.saveProfile : t.createWorkspace}
        </button>
      </div>
    </form>
  </section>;
}

function OnboardingBudgetChoice({ t, proposal, pending, error, onApply, onKeepCurrent }: {
  t: Translations; proposal?: BudgetProposal; pending: boolean; error?: string;
  onApply: () => void; onKeepCurrent: () => void;
}) {
  return <section className="onboarding-card panel">
    <span className="eyebrow">03 / 03</span><h2>{t.budgets}</h2><p>{t.budgetSetupText}</p>
    {pending && <p aria-live="polite">{t.loading}</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    {proposal && <>
      <p>{t.proposedTotal}: <strong>{proposal.totalLimit} ₽</strong></p>
      <ul>{Object.entries(proposal.limits).map(([key, value]) => <li key={key}>{key}: {value} ₽</li>)}</ul>
      <button className="button button-primary" disabled={pending} onClick={onApply}>{t.applyProposal}</button>
    </>}
    <button className="button button-quiet" disabled={pending} onClick={onKeepCurrent}>{t.keepCurrentBudget}</button>
  </section>;
}

function ProfilePanel({ t, profile, notificationPreferences, pending, error, telegramLinkCode, onCreateTelegramLink,
  onRetry, onSave, onSaveNotificationPreferences, onRepeatSetup }: {
  t: Translations; profile?: MemberProfile; notificationPreferences?: NotificationPreferences; pending: boolean; error?: string;
  telegramLinkCode: TelegramLinkCode | null; onCreateTelegramLink: () => void;
  onRetry: () => void;
  onSave: (value: Pick<MemberProfile, 'displayName' | 'plannedIncome' | 'onboardingState'>) => void;
  onSaveNotificationPreferences: (value: UpdateNotificationPreferences & { version: number }) => void;
  onRepeatSetup: () => void;
}) {
  const [name, setName] = useState<string>();
  const [income, setIncome] = useState<string>();
  const [digestDraft, setDigestDraft] = useState<UpdateNotificationPreferences>();
  useEffect(() => {
    if (notificationPreferences) {
      setDigestDraft({ language: notificationPreferences.language, dailyEnabled: notificationPreferences.dailyEnabled,
        dailyLocalTime: notificationPreferences.dailyLocalTime, weeklyEnabled: notificationPreferences.weeklyEnabled,
        weeklyDayOfWeek: notificationPreferences.weeklyDayOfWeek, weeklyLocalTime: notificationPreferences.weeklyLocalTime,
        quietHoursStart: notificationPreferences.quietHoursStart, quietHoursEnd: notificationPreferences.quietHoursEnd });
    }
  }, [notificationPreferences]);
  if (!profile && error) return <div className="empty-state"><p role="alert">{error}</p>
    <button className="button button-quiet" onClick={onRetry}>{t.retry}</button></div>;
  if (!profile || pending && !profile) return <p className="empty-state">{t.loading}</p>;
  const displayName = name ?? profile.displayName;
  const plannedIncome = income ?? (profile.plannedIncome == null ? '' : String(profile.plannedIncome));
  const digest = digestDraft ?? (notificationPreferences && {
    language: notificationPreferences.language, dailyEnabled: notificationPreferences.dailyEnabled,
    dailyLocalTime: notificationPreferences.dailyLocalTime, weeklyEnabled: notificationPreferences.weeklyEnabled,
    weeklyDayOfWeek: notificationPreferences.weeklyDayOfWeek, weeklyLocalTime: notificationPreferences.weeklyLocalTime,
    quietHoursStart: notificationPreferences.quietHoursStart, quietHoursEnd: notificationPreferences.quietHoursEnd,
  });
  const quietHoursValid = Boolean(digest) && Boolean(digest?.quietHoursStart) === Boolean(digest?.quietHoursEnd);
  const setDigest = (update: Partial<UpdateNotificationPreferences>) => {
    if (digest) setDigestDraft({ ...digest, ...update });
  };
  return <section className="onboarding-card panel">
    <span className="eyebrow">{t.workspace}</span><h2>{t.profile}</h2>
    <button className="button button-quiet" type="button" disabled={pending} onClick={onRepeatSetup}>{t.repeatSetup}</button>
    <form onSubmit={(event) => {
      event.preventDefault();
      const normalized = plannedIncome.trim().replace(',', '.');
      const parsed = normalized ? Number(normalized) : null;
      if (parsed !== null && (!Number.isFinite(parsed) || parsed <= 0 || !/^\d+(?:\.\d{1,2})?$/.test(normalized))) return;
      onSave({ displayName: displayName.trim(), plannedIncome: parsed, onboardingState: 'complete' });
    }}>
      <label>{t.memberName}<input required maxLength={120} value={displayName} onChange={(event) => setName(event.target.value)} /></label>
      <label>{t.plannedIncome}<input inputMode="decimal" value={plannedIncome} onChange={(event) => setIncome(event.target.value)} /></label>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="button button-primary" disabled={pending}>{t.saveProfile}</button>
    </form>
    <div className="telegram-link panel">
      <h3>{t.connectTelegram}</h3>
      <p>{t.telegramLinkHelp}</p>
      {telegramLinkCode && <p className="telegram-link-code"><code>{telegramLinkCode.code}</code>
        <span>{t.telegramLinkExpires}: {new Date(telegramLinkCode.expiresAt).toLocaleString()}</span></p>}
      <button className="button button-quiet" type="button" disabled={pending} onClick={onCreateTelegramLink}>
        {t.connectTelegram}
      </button>
    </div>
    <section className="telegram-link panel" aria-labelledby="digest-settings-title">
      <h3 id="digest-settings-title">{t.digestSettings}</h3>
      {notificationPreferences && <>
        <p>{notificationPreferences.telegramLinked ? t.telegramLinked : t.telegramNotLinked}</p>
        <p>{t.timezone}: {notificationPreferences.timezone}</p>
        <form onSubmit={(event) => {
          event.preventDefault();
          if (digest && notificationPreferences && quietHoursValid) {
            onSaveNotificationPreferences({ ...digest, version: notificationPreferences.version });
          }
        }}>
          <label>{t.digestLanguage}<select value={digest?.language ?? notificationPreferences.language}
            disabled={pending} onChange={(event) => setDigest({ language: event.target.value as 'ru' | 'en' })}>
            <option value="ru">Русский</option><option value="en">English</option>
          </select></label>
          <label className="checkbox-label"><input type="checkbox" checked={digest?.dailyEnabled ?? false}
            disabled={pending} onChange={(event) => setDigest({ dailyEnabled: event.target.checked })} />{t.dailyDigest}</label>
          <label>{t.dailyDigestTime}<input type="time" required={digest?.dailyEnabled ?? false}
            disabled={pending} value={digest?.dailyLocalTime ?? ''}
            onChange={(event) => setDigest({ dailyLocalTime: event.target.value })} /></label>
          <label className="checkbox-label"><input type="checkbox" checked={digest?.weeklyEnabled ?? false}
            disabled={pending} onChange={(event) => setDigest({ weeklyEnabled: event.target.checked })} />{t.weeklyDigest}</label>
          <label>{t.weeklyDigestDay}<select value={digest?.weeklyDayOfWeek ?? 7} disabled={pending}
            onChange={(event) => setDigest({ weeklyDayOfWeek: Number(event.target.value) })}>
            {([['monday', 1], ['tuesday', 2], ['wednesday', 3], ['thursday', 4], ['friday', 5],
              ['saturday', 6], ['sunday', 7]] as const).map(([key, day]) =>
              <option key={day} value={day}>{t[key]}</option>)}
          </select></label>
          <label>{t.weeklyDigestTime}<input type="time" required={digest?.weeklyEnabled ?? false}
            disabled={pending} value={digest?.weeklyLocalTime ?? ''}
            onChange={(event) => setDigest({ weeklyLocalTime: event.target.value })} /></label>
          <label>{t.quietHoursStart}<input type="time" disabled={pending}
            value={digest?.quietHoursStart ?? ''}
            onChange={(event) => setDigest({ quietHoursStart: event.target.value || null })} /></label>
          <label>{t.quietHoursEnd}<input type="time" disabled={pending}
            value={digest?.quietHoursEnd ?? ''}
            onChange={(event) => setDigest({ quietHoursEnd: event.target.value || null })} /></label>
          {!quietHoursValid && <p className="form-error" role="alert">{t.quietHoursPair}</p>}
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="button button-primary" disabled={pending || !digest || !quietHoursValid}>
            {t.saveDigestSettings}
          </button>
        </form>
      </>}
      {!notificationPreferences && <button className="button button-quiet" type="button" onClick={onRetry}>{t.retry}</button>}
    </section>
  </section>;
}

function TransactionRow({ item, language, t, canWrite, onEdit, onRepeat, onVoid }: {
  item: Transaction; language: Language; t: Translations; canWrite: boolean;
  onEdit: () => void; onRepeat: () => void; onVoid: () => void;
}) {
  const amount = new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(item.amount));
  const date = new Intl.DateTimeFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
    dateStyle: 'medium',
  }).format(new Date(item.occurredAt));
  return <article className="transaction-row">
    <span className={`transaction-icon ${item.type === 'income' || item.type === 'refund' ? 'income' : ''}`} aria-hidden="true">
      {item.type === 'income' || item.type === 'refund' ? '↙' : '↗'}
    </span>
    <div className="transaction-meta"><strong>{item.description || item.categoryCode}</strong>
      <span>{item.memberName && <>{item.memberName} · </>}{item.categoryCode} · {t.source}: {item.source} · {date} · {item.status === 'posted' ? t.posted : item.status}</span></div>
    <strong className={`transaction-amount ${item.type === 'income' || item.type === 'refund' ? 'income' : ''}`}>
      {item.type === 'income' || item.type === 'refund' ? '+' : '−'}{amount}
    </strong>
    <div className="transaction-actions">
      {canWrite && item.status === 'posted' && <>
        <button className="button button-quiet" aria-label={`${t.edit} ${item.description}`} onClick={onEdit}>{t.edit}</button>
        {item.type !== 'debt_payment' && <button className="button button-quiet" aria-label={`${t.repeat} ${item.description}`} onClick={onRepeat}>{t.repeat}</button>}
        <button className="button button-quiet" aria-label={`${t.void} ${item.description}`} onClick={onVoid}>{t.void}</button>
      </>}
    </div>
  </article>;
}

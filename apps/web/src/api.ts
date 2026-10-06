export type TenantMembership = {
  tenantId: string;
  userId: string;
  displayName: string;
  role: 'owner' | 'admin' | 'member' | 'viewer';
  timezone: string;
};

export type Session = { authenticated: boolean; displayName: string };
export type TenantMember = { userId: string; displayName: string; role: TenantMembership['role'] };
export type MemberProfile = { displayName: string; plannedIncome: number | null; onboardingState: 'started' | 'complete'; timezone: string; currency: 'RUB' };
export type NotificationPreferences = {
  timezone: string; telegramLinked: boolean; language: 'ru' | 'en'; dailyEnabled: boolean; dailyLocalTime: string;
  weeklyEnabled: boolean; weeklyDayOfWeek: number; weeklyLocalTime: string;
  quietHoursStart: string | null; quietHoursEnd: string | null; version: number;
};
export type UpdateNotificationPreferences = Pick<NotificationPreferences, 'language' | 'dailyEnabled' | 'dailyLocalTime' |
  'weeklyEnabled' | 'weeklyDayOfWeek' | 'weeklyLocalTime' | 'quietHoursStart' | 'quietHoursEnd'>;
export type TelegramLinkCode = { code: string; expiresAt: string };
export type BudgetAlert = { budgetKey: string; threshold: 'near' | 'exceeded'; limit: string; spent: string };

export type Transaction = {
  id: string;
  tenantId: string;
  type: 'expense' | 'income' | 'refund' | 'debt_payment' | 'transfer';
  amount: string;
  currency: 'RUB';
  categoryCode: string;
  subcategoryCode: string | null;
  description: string;
  source: string;
  occurredAt: string;
  accountId: string | null;
  status: 'posted' | 'voided';
  version: number;
  createdAt: string;
  memberName?: string | null;
  debtId?: string | null;
  ownerUserId?: string | null;
  budgetAlerts?: BudgetAlert[];
};

export type TransactionPage = { items: Transaction[]; nextCursor: string | null };
export type DashboardSummary = {
  month: string; currency: 'RUB'; incomeTotal: string; expenseTotal: string; transactionCount: number;
  asOfDate: string; daysElapsed: number; daysInMonth: number; daysRemaining: number;
  dailyExpensePace: string | null; projectedExpenseTotal: string | null;
  safeToSpend: SafeToSpend | null;
  rolling7FoodStatus: RollingFoodStatus;
};
export type SafeToSpend = {
  incomeBasis: 'actual_income' | 'planned_income'; incomeBase: string; month: string; horizonDate: string;
  daysRemaining: number; monthlyExpenses: string; reserve: string; promisedPayments: string;
  safeTotal: string; safePerDay: string;
};
export type BudgetOverview = {
  currency: 'RUB'; month: string; familyLimits: Record<string, string>; personalOverrides: Record<string, string>;
  effectiveLimits: Record<string, string>; monthlySpent: Record<string, string>; limitStatus: Record<string, string>;
  familyVersions: Record<string, number>; personalVersions: Record<string, number>;
  familyTotalLimit: string; personalTotalOverride: string | null; effectiveTotalLimit: string;
  totalMonthlySpent: string; totalLimitStatus: string; familyTotalVersion: number; personalTotalVersion: number;
  rolling7FoodLimit: string; personalRolling7FoodOverride: string | null; effectiveRolling7FoodLimit: string;
  rolling7FoodSpent: string; rolling7FoodLimitStatus: string; familyRolling7FoodVersion: number; personalRolling7FoodVersion: number;
  rolling7FoodStatus: RollingFoodStatus;
};
export type RollingFoodStatus = {
  fromDate: string; toDate: string; limit: string; spent: string; remaining: string | null; limitStatus: string;
  usualWeeklySpend: string | null; historyWeeks: number; paceStatus: string; paceShare: string | null;
};
export type BudgetProposal = {
  id: string; monthlyIncome: string; totalLimit: string; limits: Record<string, string>;
  baseVersions: Record<string, number>; baseTotalVersion: number; status: 'pending'; createdAt: string;
  proposalSource: 'income' | 'history_ai'; historyDays: number; modelVersion: string | null; promptVersion: string | null;
};
export type Debt = {
  id: string; tenantId: string; name: string; openingBalance: string; currentBalance: string;
  interestRate: string | null; minimumPayment: string; status: 'open' | 'closed'; version: number; createdAt: string;
};
export type DebtPage = { items: Debt[] };
export type DebtForecast = { debtId: string; monthsToPayoff: number | null; estimateBasis: string };
export type FinanceWasteReport = {
  available: boolean; reasonCode: string; completeness: 'complete' | 'partial';
  reviewedSpend: string | null; optionalSpend: string | null; optionalShare: string | null;
  reviewedItemCount: number; optionalItemCount: number; missingAmountCount: number;
  optionalByDay: Record<string, string>;
  bySource: Record<string, string>;
  topItems: Array<{ name: string; amount: string; verdict: string; source: string }>;
  corrected: Array<{ productName: string; count: number; amount: string }>;
};
export type FinanceReport = {
  period: 'month' | 'week' | '90d' | 'custom'; scope: 'personal' | 'family'; fromDate: string; toDate: string;
  asOfDate: string; timezone: string; currency: 'RUB'; incomeTotal: string; expenseTotal: string;
  debtPaymentTotal: string; refundTotal: string; transactionCount: number; expenseByCategory: Record<string, string>;
  expenseByDay: Record<string, string>;
  weekendSharePercent: number | null; monthlyBudgetLimit: string | null; monthlyBudgetRemaining: string | null;
  rolling7FoodStatus: RollingFoodStatus;
  waste: FinanceWasteReport;
};
export type CreateTransaction = Pick<Transaction, 'type' | 'amount' | 'currency' | 'categoryCode' | 'description' | 'source' | 'occurredAt'>
  & { subcategoryCode?: string | null; ownerUserId?: string | null };
export type UpdateTransaction = CreateTransaction & { debtId?: string | null };
export type TransactionDraft = {
  id: string; tenantId: string; type: 'expense' | 'income' | 'debt_payment'; amount: string; currency: 'RUB';
  categoryCode: string; subcategoryCode: string | null; description: string; occurredAt: string;
  debtId: string | null; state: 'pending' | 'confirmed' | 'cancelled'; version: number; provider: string; modelVersion: string;
  promptVersion: string; createdAt: string;
};
export type Receipt = {
  id: string; tenantId: string; documentId: string | null; state: 'draft' | 'review_required' | 'confirmed' | 'cancelled';
  version: number; transactionId: string | null; currency: 'RUB'; cashTotal: string | null; itemsTotal: string | null;
  merchant: string | null; receiptDate: string | null; categoryCode: string | null;
  selectedReader?: 'ocr' | 'vision' | 'manual' | null;
  categorySource: 'rule' | 'model' | 'default' | 'unknown' | 'human'; categoryAlgorithmVersion: string;
  alcoholShare: string | null; leisureShare: string | null; leisure: boolean;
  duplicateDecision: 'unknown' | 'independent' | 'duplicate';
  duplicateOfReceiptId: string | null; items: ReceiptItem[]; itemCount: number; createdAt: string;
};
export type ReceiptProcessingJob = {
  id: string; tenantId: string; documentId: string;
  state: 'queued' | 'running' | 'retryable' | 'completed' | 'rejected';
  stage: 'queued' | 'scanning' | 'vision' | 'ocr' | 'draft' | 'complete'; progressPercent: number; attemptCount: number;
  retryable: boolean; errorCode: string | null; receiptId: string | null; createdAt: string; updatedAt: string;
};
export type ReceiptOcrReading = {
  text: string; words: Array<{ text: string; confidence: number; box: { x: number; y: number; width: number; height: number } }>;
  provider: string | null; modelVersion: string | null; promptVersion: string | null; confidence: number | null;
  ocrTotal: string | null; ocrItems: ReceiptStructuredLineItem[]; reconciliation: ReceiptReconciliation;
  visionFallbackReason: string | null; ocrFallbackReason: string | null;
  vision?: ReceiptVisionReading | null;
};
export type ReceiptStructuredLineItem = {
  name: string; quantity: string | null; unitPrice: string | null; lineSum: string | null;
};
export type ReceiptReconciliation = {
  algorithmVersion: string; decision: 'auto_selected' | 'review_required' | 'insufficient_data';
  selectedReader: 'ocr' | 'vision' | null; mismatchFields: string[]; ocrItemsTotal: string | null;
  visionItemsTotal: string | null; allowedDifference: string | null; ocrItemsReconciled: boolean;
  visionItemsReconciled: boolean;
  itemEvidence: Array<{ visionOrdinal: number; ocrOrdinal: number | null;
    status: 'corroborated' | 'amount_disagrees' | 'amount_unknown' | 'reader_only' }>;
  suggestedTopUps: Array<{ ocrOrdinal: number; name: string; lineSum: string }>;
};
export type ReceiptVisionReading = {
  store: string | null; date: string | null; total: string | null;
  items: Array<{ name: string; quantity: string | null; unitPrice: string | null; lineSum: string | null }>;
  provider: string; modelVersion: string; promptVersion: string; fallbackReason: string | null;
};
export type ReceiptItem = {
  id: string; name: string; quantity: string | null; unitPrice: string | null; lineSum: string | null;
  productKey: string; provenance: string; confidence: number | null; categoryCode: string | null;
  verdict: string | null; advice: string | null; reviewReason: string | null; reviewAction: string | null;
  verdictSource: string; reviewProvider: string | null; reviewModelVersion: string | null;
  reviewPromptVersion: string | null; reviewAlgorithmVersion: string; version: number;
};
export type ReceiptItemInput = { name: string; quantity: string; unitPrice: string | null; lineSum: string | null };
export type ProductPricePoint = {
  receiptId: string; itemId: string; purchasedAt: string; merchant: string | null;
  name: string; unitPrice: string; current: boolean;
};
export type ProductPriceComparison = {
  algorithmVersion: string; productName: string; hasBaseline: boolean;
  currentUnitPrice: string; baselineUnitPrice: string | null; change: string | null;
  relative: string | null; signal: boolean; direction: 'up' | 'down' | null;
  priorPurchases: number; history: ProductPricePoint[];
};
export type ReceiptRecalculationChange = {
  itemId: string; name: string; lineSum: string | null; itemVersion: number;
  beforeVerdict: string | null; beforeReason: string | null; beforeAction: string | null; beforeSource: string | null;
  afterVerdict: string; afterReason: string | null; afterAction: string | null; afterSource: string;
  changed: boolean;
};
export type ReceiptRecalculationImpact = {
  algorithmVersion: string; inputVersion: string;
  reasonCode: 'available' | 'no_reviewed_items' | 'missing_amounts' | 'analytics_unavailable';
  completeness: 'complete' | 'partial';
  optionalSpendBefore: string | null; optionalSpendAfter: string | null; optionalSpendDelta: string | null;
  currency: string | null;
};
export type ReceiptRecalculationPreview = {
  runId: string; algorithmVersion: string; state: 'previewed'; checked: number; updateCount: number;
  changedCount: number; impact: ReceiptRecalculationImpact; changes: ReceiptRecalculationChange[];
};
export type ReceiptRecalculationApplyResult = {
  runId: string; algorithmVersion: string; state: 'applied'; appliedCount: number;
  changedCount: number; impact: ReceiptRecalculationImpact | null; changes: ReceiptRecalculationChange[];
};
export type ProductCatalogCard = {
  productName: string; purchaseCount: number; usualUnitPrice: string; hasBaseline: boolean;
  baselineUnitPrice: string | null; lastUnitPrice: string; lastPurchasedAt: string; lastMerchant: string | null;
  cheapestUnitPrice: string; cheapestMerchant: string | null; totalSpent: string; change: string | null;
  relative: string | null; signal: boolean; direction: 'up' | 'down' | null; priorPurchases: number;
  chartAvailable: boolean; history: ProductPricePoint[];
};
export type ProductCatalogResponse = { mode: 'catalog' | 'search'; query: string; products: ProductCatalogCard[] };
export type ShoppingCandidate = {
  productName: string; productKey: string; purchaseCount: number; medianIntervalDays: number; usualUnitPrice: string;
  estimatedCost: string; lastPurchasedAt: string; dueAt: string; daysUntilDue: number;
};
export type BlockedShoppingCandidate = { productKey: string; productName: string;
  reasonCode: 'confirmed_not_to_buy' | 'rule_backed_not_to_buy' };
export type ShoppingList = {
  candidates: ShoppingCandidate[]; estimatedListCost: string; inventoryTracked: false;
  boughtCandidates: ShoppingCandidate[]; mutedCandidates: ShoppingCandidate[];
  blockedCandidates: BlockedShoppingCandidate[];
};
export type PersonalInflationItem = {
  productName: string; oldUnitPrice: string; newUnitPrice: string; oldSpendWeight: string; changePercent: string;
  olderPurchaseCount: number; windowPurchaseCount: number;
};
export type PersonalInflation = {
  available: boolean; reasonCode: 'available' | 'insufficient_history'; asOf: string; windowDays: 90; productCount: number;
  basketBefore: string | null; basketNow: string | null; indexPercent: string | null;
  rising: PersonalInflationItem[]; falling: PersonalInflationItem[];
};
export type RecurringSeries = {
  id: string; key: string; name: string; category?: string; type: 'expense' | 'income'; currency: string;
  amount: string; minAmount: string; maxAmount: string; periodCode: 'week' | 'month'; periodDays: number;
  minIntervalDays: number; maxIntervalDays: number; occurrences: number; lastDate: string; nextDate: string; daysUntil: number;
};
export type RecurringProjection = {
  algorithmVersion: 'recurring.v1'; completeness: 'complete'; timeZone: string; asOf: string;
  expenseSeries: RecurringSeries[]; incomeSeries: RecurringSeries[]; dueSoon: RecurringSeries[]; overdue: RecurringSeries[];
  nextIncome: RecurringSeries | null; monthlyExpenseEstimate: string | null; monthlyExpenseEstimates: Record<string, string>;
  mutedSeries: RecurringSeries[];
};
export type ReceiptItemPage = { items: ReceiptItem[]; page: number; totalItems: number; hasMore: boolean };
export type ReceiptRepeatWarning = {
  itemId: string; name: string; productKey: string; verdict: string; title: string;
  count: number; lastSum: string | null; advice: string | null;
};
export type ReceiptRepeatWarnings = { warnings: ReceiptRepeatWarning[] };
export type ProductDecisionKeys = { productKeys: string[]; confirmedProductKeys: string[] };
export type AdviceEvidenceGroup = {
  productKey: string; productName: string; count: number; amount: string | null; missingAmountCount: number;
  ruleCount: number; modelCount: number; unmarkedCount: number; modelOnly: boolean;
  latestVerdict: 'harmful' | 'unnecessary'; latestAdvice: string; lastPurchasedAt: string;
};
export type AdviceEvidenceReport = {
  available: boolean; reasonCode: 'available' | 'no_optional_items' | 'too_many_items' | 'analytics_unavailable';
  algorithmVersion: 'advice-evidence.v1' | null; inputVersion: string | null;
  banned: AdviceEvidenceGroup[]; guesses: AdviceEvidenceGroup[];
};
export type ReceiptDuplicateCandidate = { id: string; cashTotal: string; merchant: string | null; createdAt: string };
export type ReceiptDuplicateCandidates = {
  receiptId: string; decision: Receipt['duplicateDecision']; candidates: ReceiptDuplicateCandidate[];
};
export type ImportRow = {
  id: string; ordinal: number; operationDate: string; operationTime: string; signedAmount: string; amount: string;
  kind: string; transactionType: 'expense' | 'income' | 'refund' | 'transfer' | null; included: boolean;
  selectionSource: 'default' | 'user'; exclusionReason: string | null; merchant: string | null;
  duplicate: boolean; duplicateOfTransactionId: string | null; outcome: 'pending' | 'created' | 'duplicate' | 'excluded' | 'reverted';
  transactionId: string | null; description: string; cardLast4: string | null;
  categoryCode: string | null; categorySource: 'human' | 'mapping' | 'model' | 'unknown';
  suggestedCategoryCode: string | null; categoryConfidence: string | null; clarificationCandidate: boolean;
};
export type MerchantMapping = {
  merchant: string; normalizedMerchant: string; categoryCode: string;
  decisionSource: 'human'; decisionVersion: string; updatedAt: string;
};
export type MerchantReclassificationCandidate = {
  transactionId: string; version: number; currentCategoryCode: string;
  amount: string; occurredAt: string; description: string;
};
export type MerchantReclassificationPreview = {
  merchant: string; normalizedMerchant: string; categoryCode: string;
  candidates: MerchantReclassificationCandidate[];
};
export type MerchantReclassificationResult = {
  merchant: string; categoryCode: string; changedCount: number; transactionIds: string[];
};
export type ImportPreview = {
  id: string; tenantId: string; state: 'needs_review' | 'ready' | 'committed' | 'reverted' | 'failed'; revision: number;
  quality: 'valid' | 'mismatch' | 'unverifiable'; parseVersion: string; periodStart: string; periodEnd: string;
  parsedExpenseTotal: string; parsedIncomeTotal: string; expectedExpenseTotal: string | null;
  expectedIncomeTotal: string | null; expenseTotal: string; incomeTotal: string; refundTotal: string;
  transferTotal: string; excludedTotal: string; includedCount: number; excludedCount: number;
  createdCount: number; duplicateCount: number; rows: ImportRow[];
};
export type ImportUndoResult = { id: string; state: 'reverted' | 'committed'; revision: number; revertedCount: number; conflicts: string[] };
export type CreateReceipt = {
  cashTotal: string; merchant: string; receiptDate: string;
  items: Array<{ name: string; quantity: string; unitPrice: string; lineSum: string }>;
};
export type UpdateTransactionDraft = Omit<Pick<Transaction, 'type' | 'amount' | 'currency' | 'categoryCode' |
  'subcategoryCode' | 'description' | 'occurredAt'>, 'type'> & {
  type: 'expense' | 'income' | 'debt_payment'; debtId: string | null;
};

let csrfToken: string | undefined;

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  if (init.method && init.method !== 'GET') {
    await getCsrf();
    if (csrfToken) headers.set('X-XSRF-TOKEN', csrfToken);
  }
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    const problem = await response.json().catch(() => undefined) as {
      title?: string; detail?: string; code?: string; conflicts?: string[];
    } | undefined;
    const error = new Error(problem?.detail ?? problem?.title ?? `Request failed (${response.status})`);
    if (problem?.code || problem?.conflicts) Object.assign(error, { code: problem.code, conflicts: problem.conflicts });
    throw error;
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function getCsrf(): Promise<{ token: string }> {
  return request<{ token: string }>('/bff/csrf').then((data) => {
    csrfToken = data.token;
    return data;
  });
}

export const api = {
  getSession: () => request<Session>('/bff/session'),
  getTenants: () => request<TenantMembership[]>('/bff/me/tenants'),
  getMembers: (tenantId: string) => request<TenantMember[]>(`/bff/tenants/${tenantId}/members`),
  createTelegramLinkCode: () => request<TelegramLinkCode>('/bff/me/telegram-link', { method: 'POST' }),
  createTenant: (displayName: string, timezone: string, memberDisplayName: string, plannedIncome: number | null) => request<TenantMembership>('/bff/tenants', {
    method: 'POST',
    body: JSON.stringify({ displayName, timezone, memberDisplayName, plannedIncome }),
  }),
  getProfile: (tenantId: string) => request<MemberProfile>(`/bff/tenants/${tenantId}/profile/me`),
  getNotificationPreferences: (tenantId: string) => request<NotificationPreferences>(
    `/bff/tenants/${tenantId}/notification-preferences`,
  ),
  getSummary: (tenantId: string) => request<DashboardSummary>(`/bff/tenants/${tenantId}/summary`),
  getReport: (tenantId: string, period: FinanceReport['period'], scope: FinanceReport['scope'], month: string,
              from: string, to: string) => {
    const query = new URLSearchParams({ period });
    if (period === 'month') query.set('month', month);
    if (period === 'custom') { query.set('from', from); query.set('to', to); }
    if (scope === 'family') return request<FinanceReport>(`/bff/tenants/${tenantId}/reports/family?${query}`);
    query.set('scope', scope);
    return request<FinanceReport>(`/bff/tenants/${tenantId}/reports/period?${query}`);
  },
  getBudgets: (tenantId: string) => request<BudgetOverview>(`/bff/tenants/${tenantId}/budgets`),
  updateBudget: (tenantId: string, budgetKey: string, scope: 'family' | 'personal', amount: string, version: number, period: 'monthly' | 'rolling7' = 'monthly') =>
    request<BudgetOverview>(`/bff/tenants/${tenantId}/budgets/${encodeURIComponent(budgetKey)}`, {
      method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` },
      body: JSON.stringify({ scope, amount, period }),
    }),
  resetPersonalBudgets: (tenantId: string) => request<BudgetOverview>(
    `/bff/tenants/${tenantId}/budgets/personal-overrides`,
    { method: 'DELETE', headers: { 'Idempotency-Key': crypto.randomUUID() } },
  ),
  createBudgetProposal: (tenantId: string, monthlyIncome: string) => request<BudgetProposal>(
    `/bff/tenants/${tenantId}/budget-proposals`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ monthlyIncome }) },
  ),
  createHistoryBudgetProposal: (tenantId: string) => request<BudgetProposal>(
    `/bff/tenants/${tenantId}/budget-proposals/history`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } },
  ),
  applyBudgetProposal: (tenantId: string, proposalId: string) => request<BudgetOverview>(
    `/bff/tenants/${tenantId}/budget-proposals/${proposalId}/apply`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } },
  ),
  getDebts: (tenantId: string) => request<DebtPage>(`/bff/tenants/${tenantId}/debts`),
  createDebt: (tenantId: string, value: Pick<Debt, 'name' | 'openingBalance' | 'interestRate' | 'minimumPayment'>) => request<Debt>(
    `/bff/tenants/${tenantId}/debts`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(value) },
  ),
  payDebt: (tenantId: string, debtId: string, version: number, amount: string) => request<{ transactionId: string; balanceReduction: string; debt: Debt }>(
    `/bff/tenants/${tenantId}/debts/${debtId}/payments`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` },
      body: JSON.stringify({ amount, occurredAt: new Date().toISOString() }) },
  ),
  adjustDebtBalance: (tenantId: string, debtId: string, version: number, currentBalance: string) => request<Debt>(
    `/bff/tenants/${tenantId}/debts/${debtId}/balance`,
    { method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` },
      body: JSON.stringify({ currentBalance }) },
  ),
  getDebtForecast: (tenantId: string, debtId: string) => request<DebtForecast>(
    `/bff/tenants/${tenantId}/debts/${debtId}/forecast`,
  ),
  updateProfile: (tenantId: string, profile: Pick<MemberProfile, 'displayName' | 'plannedIncome' | 'onboardingState'>) =>
    request<MemberProfile>(`/bff/tenants/${tenantId}/profile/me`, { method: 'PATCH', body: JSON.stringify(profile) }),
  updateNotificationPreferences: (tenantId: string, version: number, preferences: UpdateNotificationPreferences) =>
    request<NotificationPreferences>(`/bff/tenants/${tenantId}/notification-preferences`, {
      method: 'PATCH', headers: { 'If-Match': `"${version}"` }, body: JSON.stringify(preferences),
    }),
  getTransactions: (tenantId: string, filters: { cursor?: string; from?: string; to?: string; type?: string; search?: string; memberId?: string } = {}) => {
    const query = new URLSearchParams({ pageSize: '50' });
    for (const [key, value] of Object.entries(filters)) if (value) query.set(key, value);
    return request<TransactionPage>(`/bff/tenants/${tenantId}/transactions?${query}`);
  },
  createTransaction: (tenantId: string, requestBody: CreateTransaction) => request<Transaction>(
    `/bff/tenants/${tenantId}/transactions`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(requestBody),
    },
  ),
  createReceipt: (tenantId: string, requestBody: CreateReceipt, idempotencyKey = crypto.randomUUID()) => request<Receipt>(
    `/bff/tenants/${tenantId}/receipts`,
    { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(requestBody) },
  ),
  uploadReceiptPhoto: (tenantId: string, file: File, idempotencyKey: string = crypto.randomUUID()) => {
    const body = new FormData();
    body.set('file', file);
    return request<ReceiptProcessingJob>(`/bff/tenants/${tenantId}/receipts/photo-jobs`, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body,
    });
  },
  getReceiptJob: (tenantId: string, jobId: string) => request<ReceiptProcessingJob>(
    `/bff/tenants/${tenantId}/receipt-jobs/${jobId}`,
  ),
  getReceipt: (tenantId: string, receiptId: string) => request<Receipt>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}`,
  ),
  getReceiptReading: (tenantId: string, receiptId: string) => request<ReceiptOcrReading>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/readings`,
  ),
  getReceiptDuplicateCandidates: (tenantId: string, receiptId: string) => request<ReceiptDuplicateCandidates>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/duplicate-candidates`,
  ),
  getReceiptItems: (tenantId: string, receiptId: string, page = 1) => request<ReceiptItemPage>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/items?page=${page}`,
  ),
  getDisputedReceiptItems: (tenantId: string, receiptId: string, page = 1) => request<ReceiptItemPage>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/disputed-items?page=${page}`,
  ),
  getReceiptRepeatWarnings: (tenantId: string, receiptId: string) => request<ReceiptRepeatWarnings>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/repeat-warnings`,
  ),
  getAllowedProductDecisions: (tenantId: string) => request<ProductDecisionKeys>(
    `/bff/tenants/${tenantId}/products/decisions`,
  ),
  getDoNotBuyList: (tenantId: string) => request<AdviceEvidenceReport>(
    `/bff/tenants/${tenantId}/products/do-not-buy`,
  ),
  getReceiptPriceHistory: (tenantId: string, receiptId: string, itemId: string) => {
    const query = new URLSearchParams({ receiptId, itemId });
    return request<ProductPriceComparison>(`/bff/tenants/${tenantId}/products/price-history?${query}`);
  },
  previewReceiptRecalculation: (tenantId: string) => request<ReceiptRecalculationPreview>(
    `/bff/tenants/${tenantId}/review-recalculations/preview`, { method: 'POST' },
  ),
  applyReceiptRecalculation: (tenantId: string, runId: string) => request<ReceiptRecalculationApplyResult>(
    `/bff/tenants/${tenantId}/review-recalculations/apply`, { method: 'POST', body: JSON.stringify({ runId }) },
  ),
  getProductCatalog: (tenantId: string, query: string) => {
    const search = query.trim();
    return request<ProductCatalogResponse>(`/bff/tenants/${tenantId}/products${search ? `?query=${encodeURIComponent(search)}` : ''}`);
  },
  getShoppingCandidates: (tenantId: string) => request<ShoppingList>(`/bff/tenants/${tenantId}/shopping`),
  getPersonalInflation: (tenantId: string) => request<PersonalInflation>(
    `/bff/tenants/${tenantId}/analytics/personal-inflation`,
  ),
  getRecurringProjection: (tenantId: string) => request<RecurringProjection>(
    `/bff/tenants/${tenantId}/analytics/recurring`,
  ),
  muteRecurringSeries: (tenantId: string, seriesId: string) => request<RecurringProjection>(
    `/bff/tenants/${tenantId}/analytics/recurring/${encodeURIComponent(seriesId)}/mute`, { method: 'PUT' },
  ),
  unmuteRecurringSeries: (tenantId: string, seriesId: string) => request<RecurringProjection>(
    `/bff/tenants/${tenantId}/analytics/recurring/${encodeURIComponent(seriesId)}/mute`, { method: 'DELETE' },
  ),
  markShoppingBought: (tenantId: string, productKey: string) => request<ShoppingList>(
    `/bff/tenants/${tenantId}/shopping/${encodeURIComponent(productKey)}/bought`, { method: 'POST' },
  ),
  muteShoppingSuggestion: (tenantId: string, productKey: string) => request<ShoppingList>(
    `/bff/tenants/${tenantId}/suggestions/shopping/${encodeURIComponent(productKey)}/mute`, { method: 'PUT' },
  ),
  unmuteShoppingSuggestion: (tenantId: string, productKey: string) => request<ShoppingList>(
    `/bff/tenants/${tenantId}/suggestions/shopping/${encodeURIComponent(productKey)}/mute`, { method: 'DELETE' },
  ),
  allowReceiptProduct: (tenantId: string, productKey: string) => request<{ productKey: string; decision: 'allowed'; version: number; updatedAt: string }>(
    `/bff/tenants/${tenantId}/products/${encodeURIComponent(productKey)}/decision`,
    { method: 'PUT', body: JSON.stringify({ decision: 'allowed' }) },
  ),
  confirmNotToBuyProduct: (tenantId: string, productKey: string) => request<{ productKey: string; decision: 'confirmed'; version: number; updatedAt: string }>(
    `/bff/tenants/${tenantId}/products/${encodeURIComponent(productKey)}/decision`,
    { method: 'PUT', body: JSON.stringify({ decision: 'confirmed' }) },
  ),
  revokeReceiptProduct: (tenantId: string, productKey: string) => request<void>(
    `/bff/tenants/${tenantId}/products/${encodeURIComponent(productKey)}/decision`, { method: 'DELETE' },
  ),
  reviewReceiptBasket: (tenantId: string, receiptId: string, version: number) => request<Receipt>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/basket-review`, {
      method: 'POST', headers: { 'If-Match': `"${version}"` },
    },
  ),
  selectReceiptCategory: (tenantId: string, receiptId: string, version: number, categoryCode: string) => request<Receipt>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/category`, {
      method: 'PATCH', headers: { 'If-Match': `"${version}"` }, body: JSON.stringify({ categoryCode }),
    },
  ),
  updateReceiptItem: (tenantId: string, receiptId: string, itemId: string, version: number, input: ReceiptItemInput) =>
    request<Receipt>(`/bff/tenants/${tenantId}/receipts/${receiptId}/items/${itemId}`, {
      method: 'PATCH', headers: { 'If-Match': `"${version}"` }, body: JSON.stringify(input),
    }),
  deleteReceiptItem: (tenantId: string, receiptId: string, itemId: string, version: number) =>
    request<Receipt>(`/bff/tenants/${tenantId}/receipts/${receiptId}/items/${itemId}`, {
      method: 'DELETE', headers: { 'If-Match': `"${version}"` },
    }),
  addReceiptItem: (tenantId: string, receiptId: string, version: number, input: ReceiptItemInput) =>
    request<Receipt>(`/bff/tenants/${tenantId}/receipts/${receiptId}/items`, {
      method: 'POST', headers: { 'If-Match': `"${version}"`, 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(input),
    }),
  syncReceiptTotal: (tenantId: string, receiptId: string, version: number) => request<Receipt>(
    `/bff/tenants/${tenantId}/receipts/${receiptId}/sync-total`, {
      method: 'POST', headers: { 'If-Match': `"${version}"` },
    },
  ),
  decideReceiptDuplicate: (tenantId: string, receiptId: string, version: number,
                           decision: 'independent' | 'duplicate', duplicateReceiptId: string | null) =>
    request<Receipt>(`/bff/tenants/${tenantId}/receipts/${receiptId}/duplicate-decision`, {
      method: 'PUT', headers: { 'If-Match': `"${version}"` },
      body: JSON.stringify({ decision, duplicateReceiptId }),
    }),
  confirmReceipt: (tenantId: string, receiptId: string, version: number, idempotencyKey: string) =>
    request<Receipt>(`/bff/tenants/${tenantId}/receipts/${receiptId}/confirm`, {
      method: 'POST', headers: { 'If-Match': `"${version}"`, 'Idempotency-Key': idempotencyKey },
    }),
  createTransactionDraft: (tenantId: string, text: string) => request<TransactionDraft>(
    `/bff/tenants/${tenantId}/transaction-drafts`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ text }) },
  ),
  updateTransactionDraft: (tenantId: string, draftId: string, version: number, requestBody: UpdateTransactionDraft) =>
    request<TransactionDraft>(`/bff/tenants/${tenantId}/transaction-drafts/${draftId}`, {
      method: 'PATCH', headers: { 'If-Match': `"${version}"` }, body: JSON.stringify(requestBody),
    }),
  confirmTransactionDraft: (tenantId: string, draftId: string, version: number, idempotencyKey: string) =>
    request<Transaction>(`/bff/tenants/${tenantId}/transaction-drafts/${draftId}/confirm`, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': `"${version}"` },
    }),
  cancelTransactionDraft: (tenantId: string, draftId: string, version: number) =>
    request<void>(`/bff/tenants/${tenantId}/transaction-drafts/${draftId}`, {
      method: 'DELETE', headers: { 'If-Match': `"${version}"` },
    }),
  createImport: (tenantId: string, file: File) => {
    const body = new FormData();
    body.set('file', file);
    return request<ImportPreview>(`/bff/tenants/${tenantId}/imports`, { method: 'POST', body });
  },
  getImportPreview: (tenantId: string, importId: string) => request<ImportPreview>(
    `/bff/tenants/${tenantId}/imports/${importId}/preview`,
  ),
  selectImportRow: (tenantId: string, importId: string, rowId: string,
                    transactionType: ImportRow['transactionType'], revision: number) =>
    request<ImportPreview>(`/bff/tenants/${tenantId}/imports/${importId}/rows/${rowId}`, {
      method: 'PATCH', headers: { 'If-Match': `"${revision}"` }, body: JSON.stringify({ transactionType }),
    }),
  classifyImport: (tenantId: string, importId: string, revision: number) =>
    request<ImportPreview>(`/bff/tenants/${tenantId}/imports/${importId}/classify`, {
      method: 'POST', headers: { 'If-Match': `"${revision}"` },
    }),
  selectImportCategory: (tenantId: string, importId: string, rowId: string, categoryCode: string, revision: number) =>
    request<ImportPreview>(`/bff/tenants/${tenantId}/imports/${importId}/rows/${rowId}/category`, {
      method: 'PUT', headers: { 'If-Match': `"${revision}"` }, body: JSON.stringify({ categoryCode }),
    }),
  getMerchantMappings: (tenantId: string) => request<{ mappings: MerchantMapping[] }>(
    `/bff/tenants/${tenantId}/merchant-mappings`,
  ),
  saveMerchantMapping: (tenantId: string, merchant: string, categoryCode: string) =>
    request<MerchantMapping>(`/bff/tenants/${tenantId}/merchant-mappings`, {
      method: 'PUT', body: JSON.stringify({ merchant, categoryCode }),
    }),
  previewMerchantReclassification: (tenantId: string, merchant: string) =>
    request<MerchantReclassificationPreview>(`/bff/tenants/${tenantId}/merchant-reclassifications/preview`, {
      method: 'POST', body: JSON.stringify({ merchant }),
    }),
  applyMerchantReclassification: (tenantId: string, preview: MerchantReclassificationPreview,
                                   idempotencyKey: string) =>
    request<MerchantReclassificationResult>(`/bff/tenants/${tenantId}/merchant-reclassifications/apply`, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ merchant: preview.merchant, categoryCode: preview.categoryCode,
        candidates: preview.candidates.map(({ transactionId, version, currentCategoryCode }) =>
          ({ transactionId, version, currentCategoryCode })) }),
    }),
  confirmImport: (tenantId: string, importId: string, revision: number, idempotencyKey: string) =>
    request<ImportPreview>(`/bff/tenants/${tenantId}/imports/${importId}/confirm`, {
      method: 'POST', headers: { 'If-Match': `"${revision}"`, 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ confirmed: true }),
    }),
  undoImport: (tenantId: string, importId: string, revision: number, idempotencyKey: string) =>
    request<ImportUndoResult>(`/bff/tenants/${tenantId}/imports/${importId}/undo`, {
      method: 'POST', headers: { 'If-Match': `"${revision}"`, 'Idempotency-Key': idempotencyKey },
    }),
  updateTransaction: (tenantId: string, transactionId: string, version: number, requestBody: UpdateTransaction) => request<Transaction>(
    `/bff/tenants/${tenantId}/transactions/${transactionId}`,
    { method: 'PATCH', headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` }, body: JSON.stringify(requestBody) },
  ),
  voidTransaction: (tenantId: string, transactionId: string, version: number) => request<Transaction>(
    `/bff/tenants/${tenantId}/transactions/${transactionId}/void`,
    { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${version}"` } },
  ),
  logout: async () => {
    await getCsrf();
    const response = await fetch('/logout', {
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': csrfToken ?? '' },
      credentials: 'same-origin',
    });
    if (!response.ok) throw new Error(`Request failed (${response.status})`);
    csrfToken = undefined;
  },
};

import { useState, type FormEvent } from 'react';
import { api, type ImportPreview, type ImportRow, type MerchantReclassificationPreview } from './api';
import { formatMoney } from './formatting';

type Language = 'ru' | 'en';
type Copy = {
  title: string; intro: string; file: string; preview: string; upload: string; mismatch: string; unverifiable: string;
  valid: string; period: string; operations: string; included: string; excluded: string; expenses: string; income: string;
  refunds: string; transfers: string; excludedSum: string; includedByDefault: string; includedByUser: string;
  reason: Record<string, string>; chooseType: (row: ImportRow) => string; keepExcluded: string; expense: string;
  incomeType: string; refund: string; transfer: string; noTransactions: string; error: string; loading: string;
  confirm: (count: number) => string; undo: string; committed: (created: number, duplicates: number) => string;
  reverted: string; duplicate: string; created: string; undoConflict: (rows: string[]) => string;
  merchantMissing: string; card: string; parsedTotals: string; expectedTotals: string; feeWarning: string;
  classify: string; classificationHint: string; clarificationTitle: string; clarificationHint: string;
  hypothesis: (category: string, confidence: string | null) => string; category: string; categoryFor: (merchant: string) => string;
  selectCategory: string; reviewPast: string; reclassificationTitle: string;
  reclassificationHint: (merchant: string, category: string) => string;
  applyReclassification: (count: number) => string; closeReclassification: string;
  noReclassificationCandidates: string; reclassificationApplied: (count: number) => string;
  staleReclassification: string;
  parseErrors: Record<string, string>;
};

const categories = ['еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее'];

const copy: Record<Language, Copy> = {
  ru: {
    title: 'Импорт выписки', intro: 'Поддерживается PDF «Справка о движении средств» Т-Банка. Сначала проверьте строки и итоги.',
    file: 'PDF выписка Т-Банка', preview: 'Предпросмотр выписки', upload: 'Показать предпросмотр', mismatch: 'Расхождение итогов выписки',
    unverifiable: 'Итоги выписки не удалось проверить', valid: 'Итоги выписки сверены', period: 'Период', operations: 'Строк операции',
    included: 'Включено', excluded: 'Пропущено', expenses: 'Расходы', income: 'Доходы', refunds: 'Возвраты',
    transfers: 'Переводы', excludedSum: 'Сумма пропущенных строк', includedByDefault: 'Включено по типу операции',
    includedByUser: 'Включено вашим выбором',
    reason: { bank_fee: 'Комиссия банка', external_transfer: 'Перевод между счетами', cash_withdrawal: 'Снятие наличных',
      internal_transfer: 'Внутренний перевод', requires_review: 'Требует проверки' },
    chooseType: (row) => `Выбрать тип для строки ${row.description}`,
    keepExcluded: 'Не включать', expense: 'Расход', incomeType: 'Доход', refund: 'Возврат', transfer: 'Перевод',
    noTransactions: 'Транзакции ещё не созданы', error: 'Не удалось обработать выписку. Проверьте файл и повторите.',
    confirm: (count) => `Создать транзакции (${count})`, undo: 'Отменить импорт',
    committed: (created, duplicates) => `Создано: ${created}. Дубли пропущены: ${duplicates}.`,
    reverted: 'Импорт отменён. Созданные им строки помечены отменёнными.', duplicate: 'Уже импортировано; строка будет пропущена',
    created: 'Созданная транзакция', undoConflict: (rows) => `Отмена остановлена: записи изменены после импорта — ${rows.join(', ')}.`,
    loading: 'Проверяем PDF…', merchantMissing: 'Магазин не указан', card: 'Карта', parsedTotals: 'Найдено в операциях',
    expectedTotals: 'В выписке', feeWarning: 'Комиссии и переводы не добавляются автоматически; каждая пропущенная строка показана ниже.',
    classify: 'Предложить категории', classificationHint: 'AI покажет гипотезы по названиям магазинов. Названия без сумм и описаний отправляются только по настройке AI-политики.',
    clarificationTitle: 'Нужно уточнить категории', clarificationHint: 'До 6 магазинов с расходами от 300 ₽. Выбранная категория сохранится как ваше правило для этого магазина.',
    hypothesis: (category, confidence) => `Гипотеза: ${category}${confidence ? ` · ${Math.round(Number(confidence) * 100)}%` : ''}`,
    category: 'Категория', categoryFor: (merchant) => `Категория магазина ${merchant}`, selectCategory: 'Выберите категорию',
    reviewPast: 'Проверить прошлые расходы', reclassificationTitle: 'Ранее импортированные расходы',
    reclassificationHint: (merchant, category) => `Проверьте все записи магазина «${merchant}». Новая категория: ${category}.`,
    applyReclassification: (count) => `Изменить категорию (${count})`, closeReclassification: 'Закрыть список',
    noReclassificationCandidates: 'Подходящих прошлых расходов нет.',
    reclassificationApplied: (count) => `Категория изменена у записей: ${count}.`,
    staleReclassification: 'Записи или правило изменились. Обновите список и проверьте его снова.',
    parseErrors: {
      invalid_pdf: 'Не удалось прочитать PDF. Проверьте файл и повторите.',
      invalid_format: 'Поддерживается только выписка Т-Банка «Справка о движении средств».',
      no_text: 'В PDF нет извлекаемого текста. Скан выписки пока не поддерживается.',
      no_operations: 'В выписке не найдены операции в поддерживаемом формате.',
      invalid_totals: 'Итоги выписки имеют неверный формат или выходят за диапазон.',
      invalid_operation: 'В одной из операций неверная сумма, дата или описание.',
    },
  },
  en: {
    title: 'Statement import', intro: 'Supports T-Bank account statement PDFs. Review operation rows and reconciliation totals first.',
    file: 'T-Bank statement PDF', preview: 'Statement preview', upload: 'Preview statement', mismatch: 'Statement totals do not match',
    unverifiable: 'Statement totals could not be verified', valid: 'Statement totals reconciled', period: 'Period',
    operations: 'Operation rows', included: 'Included', excluded: 'Skipped', expenses: 'Expenses', income: 'Income',
    refunds: 'Refunds', transfers: 'Transfers', excludedSum: 'Skipped row amount', includedByDefault: 'Included by operation type',
    includedByUser: 'Included by your choice',
    reason: { bank_fee: 'Bank fee', external_transfer: 'External transfer', cash_withdrawal: 'Cash withdrawal',
      internal_transfer: 'Internal transfer', requires_review: 'Needs review' },
    chooseType: (row) => `Choose type for row ${row.description}`,
    keepExcluded: 'Keep excluded', expense: 'Expense', incomeType: 'Income', refund: 'Refund', transfer: 'Transfer',
    noTransactions: 'No transactions created yet', error: 'Could not process this statement. Check the file and try again.',
    confirm: (count) => `Create transactions (${count})`, undo: 'Undo import',
    committed: (created, duplicates) => `Created: ${created}. Duplicates skipped: ${duplicates}.`,
    reverted: 'Import undone. Transactions created by this batch were voided.', duplicate: 'Already imported; this row will be skipped',
    created: 'Transaction created', undoConflict: (rows) => `Undo stopped because imported records changed: ${rows.join(', ')}.`,
    loading: 'Checking PDF…', merchantMissing: 'Merchant not listed', card: 'Card', parsedTotals: 'From operations',
    expectedTotals: 'Statement totals', feeWarning: 'Fees and transfers are never added automatically; every skipped row is listed below.',
    classify: 'Suggest categories', classificationHint: 'AI returns hypotheses from merchant names. No amounts or descriptions are sent; AI policy still applies.',
    clarificationTitle: 'Categories need review', clarificationHint: 'Up to 6 merchants with at least RUB 300 in expenses. Your selection is saved as a personal merchant rule.',
    hypothesis: (category, confidence) => `Hypothesis: ${category}${confidence ? ` · ${Math.round(Number(confidence) * 100)}%` : ''}`,
    category: 'Category', categoryFor: (merchant) => `Category for ${merchant}`, selectCategory: 'Choose a category',
    reviewPast: 'Review past transactions', reclassificationTitle: 'Previously imported expenses',
    reclassificationHint: (merchant, category) => `Review every listed transaction from ${merchant}. New category: ${category}.`,
    applyReclassification: (count) => `Change category (${count})`, closeReclassification: 'Close list',
    noReclassificationCandidates: 'No eligible past expenses found.',
    reclassificationApplied: (count) => `Category changed for ${count} transactions.`,
    staleReclassification: 'Transactions or mapping changed. Refresh the list and review it again.',
    parseErrors: {
      invalid_pdf: 'Could not read the PDF. Check the file and try again.',
      invalid_format: 'Only T-Bank account statement PDFs are supported.',
      no_text: 'The PDF has no extractable text. Scanned statements are not supported yet.',
      no_operations: 'No operations in the supported statement format were found.',
      invalid_totals: 'Statement totals are malformed or outside the supported range.',
      invalid_operation: 'An operation has an invalid amount, date or description.',
    },
  },
};

function money(value: string, language: Language): string {
  return formatMoney(value, 'RUB', language);
}

export function ImportsPanel({ tenantId, language }: { tenantId: string; language: Language }) {
  const t = copy[language];
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<ImportPreview>();
  const [pending, setPending] = useState(false);
  const [changingRow, setChangingRow] = useState<string>();
  const [reclassification, setReclassification] = useState<MerchantReclassificationPreview>();
  const [reclassificationResult, setReclassificationResult] = useState<string>();
  const [error, setError] = useState<string>();

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) return;
    setPending(true);
    setError(undefined);
    try {
      setPreview(await api.createImport(tenantId, file));
      setReclassification(undefined);
      setReclassificationResult(undefined);
    } catch (failure) {
      const code = failure instanceof Error ? (failure as Error & { code?: string }).code : undefined;
      setError((code && t.parseErrors[code]) || t.error);
    } finally {
      setPending(false);
    }
  }

  async function select(row: ImportRow, value: string) {
    if (!preview) return;
    setChangingRow(row.id);
    setError(undefined);
    try {
      setPreview(await api.selectImportRow(tenantId, preview.id, row.id,
        value === '' ? null : value as ImportRow['transactionType'], preview.revision));
    } catch (failure) {
      const code = failure instanceof Error ? (failure as Error & { code?: string }).code : undefined;
      setError((code && t.parseErrors[code]) || t.error);
    } finally {
      setChangingRow(undefined);
    }
  }

  async function classify() {
    if (!preview) return;
    setPending(true);
    setError(undefined);
    try {
      setPreview(await api.classifyImport(tenantId, preview.id, preview.revision));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t.error);
    } finally {
      setPending(false);
    }
  }

  async function selectCategory(row: ImportRow, categoryCode: string) {
    if (!preview || !categoryCode) return;
    setChangingRow(row.id);
    setError(undefined);
    setReclassification(undefined);
    setReclassificationResult(undefined);
    try {
      setPreview(await api.selectImportCategory(tenantId, preview.id, row.id, categoryCode, preview.revision));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t.error);
    } finally {
      setChangingRow(undefined);
    }
  }

  async function reviewPastTransactions(merchant: string) {
    setPending(true);
    setError(undefined);
    setReclassification(undefined);
    setReclassificationResult(undefined);
    try {
      setReclassification(await api.previewMerchantReclassification(tenantId, merchant));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t.error);
    } finally {
      setPending(false);
    }
  }

  async function applyPastTransactions() {
    if (!reclassification || reclassification.candidates.length === 0) return;
    setPending(true);
    setError(undefined);
    try {
      const result = await api.applyMerchantReclassification(tenantId, reclassification, crypto.randomUUID());
      setReclassification(undefined);
      setReclassificationResult(t.reclassificationApplied(result.changedCount));
    } catch (failure) {
      const code = failure instanceof Error ? (failure as Error & { code?: string }).code : undefined;
      setError(code === 'precondition_failed' ? t.staleReclassification
        : failure instanceof Error ? failure.message : t.error);
    } finally {
      setPending(false);
    }
  }

  async function confirm() {
    if (!preview) return;
    setPending(true);
    setError(undefined);
    try {
      setPreview(await api.confirmImport(tenantId, preview.id, preview.revision, crypto.randomUUID()));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t.error);
    } finally {
      setPending(false);
    }
  }

  async function undo() {
    if (!preview) return;
    setPending(true);
    setError(undefined);
    try {
      await api.undoImport(tenantId, preview.id, preview.revision, crypto.randomUUID());
      setPreview(await api.getImportPreview(tenantId, preview.id));
    } catch (failure) {
      const conflicts = failure instanceof Error
        ? (failure as Error & { conflicts?: string[] }).conflicts ?? [] : [];
      const conflictRows = conflicts.map((id) => preview.rows.find((row) => row.transactionId === id)?.description ?? id);
      setError(conflictRows.length ? t.undoConflict(conflictRows) : failure instanceof Error ? failure.message : t.error);
    } finally {
      setPending(false);
    }
  }

  const quality = preview?.quality === 'mismatch' ? t.mismatch
    : preview?.quality === 'unverifiable' ? t.unverifiable : t.valid;
  const displayedTotal = (parsed: string, expected: string | null) => `${money(parsed, language)}${expected == null ? '' : ` · ${money(expected, language)}`}`;
  const clarificationMerchants = preview ? [...new Set(preview.rows.filter((row) => row.clarificationCandidate)
    .map((row) => row.merchant).filter((merchant): merchant is string => !!merchant))] : [];
  const hasClassifiableRows = preview?.rows.some((row) => row.included && row.transactionType === 'expense' && row.merchant) ?? false;

  return <section className="imports-panel">
    <header className="page-header"><div><span className="eyebrow">PDF · Т-Банк</span><h1>{t.title}</h1><p>{t.intro}</p></div></header>
    <section className="panel import-upload">
      <form noValidate onSubmit={(event) => void upload(event)}>
        <label>{t.file}<input type="file" accept="application/pdf,.pdf" required
          onChange={(event) => setFile(event.currentTarget.files?.[0])} /></label>
        <button className="button button-primary" disabled={!file || pending}>{pending ? t.loading : t.upload}</button>
      </form>
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>
    {preview && <section className="panel import-preview" aria-label={t.preview}>
      <div className={`import-quality ${preview.quality}`} role="status">{quality}</div>
      {preview.quality !== 'valid' && <p className="import-warning">{t.expectedTotals}: {t.expenses.toLowerCase()} {money(preview.expectedExpenseTotal ?? preview.parsedExpenseTotal, language)} · {t.income.toLowerCase()} {money(preview.expectedIncomeTotal ?? preview.parsedIncomeTotal, language)}. {t.parsedTotals}: {displayedTotal(preview.parsedExpenseTotal, null)} / {displayedTotal(preview.parsedIncomeTotal, null)}.</p>}
      <div className="import-period"><strong>{t.period}</strong><span>{preview.periodStart} — {preview.periodEnd}</span>
        <strong>{t.operations}</strong><span>{preview.rows.length}</span></div>
      <dl className="import-summary">
        <div><dt>{t.included}</dt><dd>{preview.includedCount}</dd></div>
        <div><dt>{t.excluded}</dt><dd>{preview.excludedCount}</dd></div>
        <div><dt>{t.expenses}</dt><dd>{money(preview.expenseTotal, language)}</dd></div>
        <div><dt>{t.income}</dt><dd>{money(preview.incomeTotal, language)}</dd></div>
        <div><dt>{t.refunds}</dt><dd>{money(preview.refundTotal, language)}</dd></div>
        <div><dt>{t.transfers}</dt><dd>{money(preview.transferTotal, language)}</dd></div>
        <div><dt>{t.excludedSum}</dt><dd>{money(preview.excludedTotal, language)}</dd></div>
      </dl>
      <p className="import-warning">{t.feeWarning}</p>
      {preview.state === 'needs_review' && hasClassifiableRows && <div className="import-actions">
        <p>{t.classificationHint}</p>
        <button className="button" disabled={pending} onClick={() => void classify()}>{pending ? t.loading : t.classify}</button>
      </div>}
      {preview.state === 'needs_review' && clarificationMerchants.length > 0 && <aside className="import-warning" aria-label={t.clarificationTitle}>
        <strong>{t.clarificationTitle}</strong><p>{t.clarificationHint}</p>
        <ul>{clarificationMerchants.map((merchant) => <li key={merchant}>{merchant}</li>)}</ul>
      </aside>}
      {reclassificationResult && <p role="status">{reclassificationResult}</p>}
      {reclassification && <aside className="import-warning" aria-label={t.reclassificationTitle}>
        <strong>{t.reclassificationTitle}</strong>
        <p>{t.reclassificationHint(reclassification.merchant, reclassification.categoryCode)}</p>
        {reclassification.candidates.length === 0 ? <p>{t.noReclassificationCandidates}</p> : <ul>
          {reclassification.candidates.map((candidate) => <li key={candidate.transactionId}>
            <strong>{candidate.description}</strong>{' · '}{candidate.occurredAt.slice(0, 10)}{' · '}
            {money(candidate.amount, language)}{' · '}{candidate.currentCategoryCode}{' → '}{reclassification.categoryCode}
          </li>)}
        </ul>}
        <div className="import-actions">
          <button className="button button-primary" disabled={pending || reclassification.candidates.length === 0}
            onClick={() => void applyPastTransactions()}>{t.applyReclassification(reclassification.candidates.length)}</button>
          <button className="button" disabled={pending} onClick={() => setReclassification(undefined)}>{t.closeReclassification}</button>
        </div>
      </aside>}
      <div className="import-rows">{preview.rows.map((row) => <article className="import-row" key={row.id}>
        <div className="import-row-main"><strong>{row.merchant ?? row.description}</strong>
          <span>{row.operationDate} · {row.operationTime}{row.cardLast4 ? ` · ${t.card} ••${row.cardLast4}` : ''}</span>
          {row.merchant && <small>{row.description}</small>}
        </div>
        <strong className={row.signedAmount.startsWith('-') ? 'import-expense' : 'import-income'}>
          {money(row.signedAmount, language)}
        </strong>
        <div className="import-row-selection">
          {row.duplicate ? <span className="import-exclusion">{t.duplicate}</span>
            : row.outcome === 'created' ? <span>{t.created}</span>
              : row.included ? <span>{row.selectionSource === 'user' ? t.includedByUser : t.includedByDefault}</span>
            : <span className="import-exclusion">{t.reason[row.exclusionReason ?? 'requires_review'] ?? t.reason.requires_review}</span>}
          {preview.state === 'needs_review' && <label><span className="sr-only">{t.chooseType(row)}</span>
            <select aria-label={t.chooseType(row)} value={row.included ? row.transactionType ?? '' : ''}
              disabled={pending || changingRow === row.id}
              onChange={(event) => void select(row, event.currentTarget.value)}>
              <option value="">{t.keepExcluded}</option><option value="expense">{t.expense}</option>
              <option value="income">{t.incomeType}</option><option value="refund">{t.refund}</option>
              <option value="transfer">{t.transfer}</option>
            </select>
          </label>}
          {preview.state === 'needs_review' && row.included && row.transactionType === 'expense' && row.merchant && <div>
            {row.suggestedCategoryCode && <small className="import-hypothesis">{t.hypothesis(row.suggestedCategoryCode, row.categoryConfidence)}</small>}
            <label><span className="sr-only">{t.categoryFor(row.merchant)}</span>
              <select aria-label={t.categoryFor(row.merchant)} value={row.categoryCode ?? ''}
                disabled={pending || changingRow === row.id}
                onChange={(event) => void selectCategory(row, event.currentTarget.value)}>
                <option value="" disabled>{t.selectCategory}</option>
                {categories.map((category) => <option key={category} value={category}>{category}</option>)}
              </select>
            </label>
            {row.categoryCode && <button className="button" disabled={pending || changingRow === row.id}
              onClick={() => void reviewPastTransactions(row.merchant!)}>{pending ? t.loading : t.reviewPast}</button>}
          </div>}
        </div>
      </article>)}</div>
      {preview.state === 'needs_review' && <div className="import-actions">
        <p className="import-not-committed" role="status">{t.noTransactions}</p>
        <button className="button button-primary" disabled={pending || preview.includedCount === 0}
          onClick={() => void confirm()}>{pending ? t.loading : t.confirm(preview.includedCount - preview.duplicateCount)}</button>
      </div>}
      {preview.state === 'committed' && <div className="import-actions" role="status">
        <p>{t.committed(preview.createdCount, preview.duplicateCount)}</p>
        <button className="button" disabled={pending} onClick={() => void undo()}>{pending ? t.loading : t.undo}</button>
      </div>}
      {preview.state === 'reverted' && <p className="import-not-committed" role="status">{t.reverted}</p>}
    </section>}
  </section>;
}

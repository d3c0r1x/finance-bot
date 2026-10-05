import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type ImportPreview, type MerchantReclassificationPreview } from './api';
import { ImportsPanel } from './ImportsPanel';

const initial: ImportPreview = {
  id: 'import-1', tenantId: 'tenant-1', state: 'needs_review', revision: 1, quality: 'mismatch',
  parseVersion: 'tbank-pdf.v1', periodStart: '2026-10-01', periodEnd: '2026-10-01',
  parsedExpenseTotal: '13.00', parsedIncomeTotal: '0.00', expectedExpenseTotal: '12.00', expectedIncomeTotal: '0.00',
  expenseTotal: '12.00', incomeTotal: '0.00', refundTotal: '0.00', transferTotal: '0.00', excludedTotal: '1.00',
  includedCount: 1, excludedCount: 1, createdCount: 0, duplicateCount: 0,
  rows: [
    { id: 'row-1', ordinal: 0, operationDate: '2026-10-01', operationTime: '12:30', signedAmount: '-12.00',
      amount: '12.00', kind: 'purchase', transactionType: 'expense', included: true, selectionSource: 'default',
      exclusionReason: null, duplicate: false, duplicateOfTransactionId: null, outcome: 'pending', transactionId: null,
      merchant: 'Market', description: 'Payment', cardLast4: '1234', categoryCode: null, categorySource: 'unknown',
      suggestedCategoryCode: null, categoryConfidence: null, clarificationCandidate: false },
    { id: 'row-2', ordinal: 1, operationDate: '2026-10-01', operationTime: '12:31', signedAmount: '-1.00',
      amount: '1.00', kind: 'fee', transactionType: null, included: false, selectionSource: 'default',
      exclusionReason: 'bank_fee', duplicate: false, duplicateOfTransactionId: null, outcome: 'pending', transactionId: null,
      merchant: null, description: 'Bank fee', cardLast4: '1234', categoryCode: null, categorySource: 'unknown',
      suggestedCategoryCode: null, categoryConfidence: null, clarificationCandidate: false },
  ],
};

describe('ImportsPanel', () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(() => cleanup());

  it('uploads a statement and clearly shows reconciliation and excluded fees', async () => {
    const create = vi.spyOn(api, 'createImport').mockResolvedValue(initial);
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="ru" />);

    fireEvent.change(screen.getByLabelText('PDF выписка Т-Банка'), {
      target: { files: [new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Показать предпросмотр' }));

    expect(create).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Расхождение итогов выписки')).toBeInTheDocument();
    expect(screen.getByText('Комиссия банка')).toBeInTheDocument();
    expect(screen.getAllByText(/1,00/).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Транзакции ещё не созданы')).toBeInTheDocument();
  });

  it('lets the user explicitly include a fee with the current preview revision', async () => {
    vi.spyOn(api, 'createImport').mockResolvedValue(initial);
    const selected = { ...initial, revision: 2, expenseTotal: '13.00', excludedTotal: '0.00',
      includedCount: 2, excludedCount: 0,
      rows: initial.rows.map((row) => row.id === 'row-2' ? { ...row, included: true,
        transactionType: 'expense' as const, selectionSource: 'user' as const, exclusionReason: null } : row) };
    const select = vi.spyOn(api, 'selectImportRow').mockResolvedValue(selected);
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="en" />);
    fireEvent.change(screen.getByLabelText('T-Bank statement PDF'), {
      target: { files: [new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Preview statement' }));
    const selector = await screen.findByLabelText('Choose type for row Bank fee');
    await user.selectOptions(selector, 'expense');

    await waitFor(() => expect(select).toHaveBeenCalledWith('tenant-1', 'import-1', 'row-2', 'expense', 1));
    expect(await screen.findByText('Included by your choice')).toBeInTheDocument();
    expect(screen.getByText('No transactions created yet')).toBeInTheDocument();
  });

  it('explains a scanned PDF with a localized message', async () => {
    vi.spyOn(api, 'createImport').mockRejectedValue(Object.assign(new Error('parser error'), { code: 'no_text' }));
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="ru" />);
    fireEvent.change(screen.getByLabelText('PDF выписка Т-Банка'), {
      target: { files: [new File(['%PDF-1.7'], 'scan.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Показать предпросмотр' }));

    expect(await screen.findByText('В PDF нет извлекаемого текста. Скан выписки пока не поддерживается.')).toBeInTheDocument();
  });

  it('shows an AI hypothesis, asks about qualifying merchants and persists the selected personal rule', async () => {
    const classified: ImportPreview = {
      ...initial, revision: 2,
      rows: initial.rows.map((row) => row.id === 'row-1' ? { ...row, suggestedCategoryCode: 'еда',
        categoryConfidence: '0.880', clarificationCandidate: true } : row),
    };
    const mapped: ImportPreview = {
      ...classified, revision: 3,
      rows: classified.rows.map((row) => row.id === 'row-1' ? { ...row, categoryCode: 'еда', categorySource: 'mapping' } : row),
    };
    vi.spyOn(api, 'createImport').mockResolvedValue(initial);
    const classify = vi.spyOn(api, 'classifyImport').mockResolvedValue(classified);
    const selectCategory = vi.spyOn(api, 'selectImportCategory').mockResolvedValue(mapped);
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="en" />);
    fireEvent.change(screen.getByLabelText('T-Bank statement PDF'), {
      target: { files: [new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Preview statement' }));
    await user.click(await screen.findByRole('button', { name: 'Suggest categories' }));

    expect(classify).toHaveBeenCalledWith('tenant-1', 'import-1', 1);
    expect(await screen.findByText('Hypothesis: еда · 88%')).toBeInTheDocument();
    expect(screen.getByText('Market', { selector: 'li' })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Category for Market'), 'еда');
    await waitFor(() => expect(selectCategory).toHaveBeenCalledWith('tenant-1', 'import-1', 'row-1', 'еда', 2));
  });

  it('offers a separate preview before changing prior imported transactions', async () => {
    const mapped: ImportPreview = {
      ...initial,
      revision: 2,
      rows: initial.rows.map((row) => row.id === 'row-1'
        ? { ...row, categoryCode: 'еда', categorySource: 'human' as const } : row),
    };
    const reclassification: MerchantReclassificationPreview = {
      merchant: 'Market', normalizedMerchant: 'market', categoryCode: 'еда', candidates: [{
        transactionId: 'transaction-old', version: 1, currentCategoryCode: 'прочее', amount: '12.00',
        occurredAt: '2026-10-01T09:30:00Z', description: 'Payment',
      }],
    };
    vi.spyOn(api, 'createImport').mockResolvedValue(initial);
    vi.spyOn(api, 'selectImportCategory').mockResolvedValue(mapped);
    const review = vi.spyOn(api, 'previewMerchantReclassification').mockResolvedValue(reclassification);
    const apply = vi.spyOn(api, 'applyMerchantReclassification').mockResolvedValue({
      merchant: 'Market', categoryCode: 'еда', changedCount: 1, transactionIds: ['transaction-old'],
    });
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="en" />);
    fireEvent.change(screen.getByLabelText('T-Bank statement PDF'), {
      target: { files: [new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Preview statement' }));
    await user.selectOptions(await screen.findByLabelText('Category for Market'), 'еда');

    await user.click(await screen.findByRole('button', { name: 'Review past transactions' }));
    expect(review).toHaveBeenCalledWith('tenant-1', 'Market');
    const reviewedItem = await screen.findByRole('listitem');
    expect(reviewedItem.closest('li')).toHaveTextContent('прочее → еда');
    await user.click(screen.getByRole('button', { name: 'Change category (1)' }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith('tenant-1', reclassification, expect.any(String)));
    expect(await screen.findByText('Category changed for 1 transactions.')).toBeInTheDocument();
  });

  it('confirms a batch, reports duplicates and lets the user undo it', async () => {
    const committed: ImportPreview = {
      ...initial, state: 'committed', revision: 2, includedCount: 2, excludedCount: 0, createdCount: 1, duplicateCount: 1,
      rows: initial.rows.map((row, index) => index === 0 ? {
        ...row, outcome: 'created' as const, transactionId: 'transaction-1',
      } : { ...row, included: true, transactionType: 'expense' as const, exclusionReason: null,
        duplicate: true, duplicateOfTransactionId: 'transaction-1', outcome: 'duplicate' as const }),
    };
    const reverted: ImportPreview = {
      ...committed, state: 'reverted', revision: 3,
      rows: committed.rows.map((row) => row.outcome === 'created' ? { ...row, outcome: 'reverted' as const } : row),
    };
    vi.spyOn(api, 'createImport').mockResolvedValue(initial);
    const confirm = vi.spyOn(api, 'confirmImport').mockResolvedValue(committed);
    const undo = vi.spyOn(api, 'undoImport').mockResolvedValue({
      id: 'import-1', state: 'reverted', revision: 3, revertedCount: 1, conflicts: [],
    });
    vi.spyOn(api, 'getImportPreview').mockResolvedValue(reverted);
    const user = userEvent.setup();
    render(<ImportsPanel tenantId="tenant-1" language="en" />);
    fireEvent.change(screen.getByLabelText('T-Bank statement PDF'), {
      target: { files: [new File(['%PDF-1.7'], 'statement.pdf', { type: 'application/pdf' })] },
    });
    await user.click(screen.getByRole('button', { name: 'Preview statement' }));
    await user.click(await screen.findByRole('button', { name: 'Create transactions (1)' }));

    expect(confirm).toHaveBeenCalledWith('tenant-1', 'import-1', 1, expect.any(String));
    expect(await screen.findByText('Created: 1. Duplicates skipped: 1.')).toBeInTheDocument();
    expect(screen.getByText('Already imported; this row will be skipped')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Undo import' }));
    expect(undo).toHaveBeenCalledWith('tenant-1', 'import-1', 2, expect.any(String));
    expect(await screen.findByText('Import undone. Transactions created by this batch were voided.')).toBeInTheDocument();
  });
});

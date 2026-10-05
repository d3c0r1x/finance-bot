import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type CreateReceipt, type ProductPriceComparison, type Receipt, type ReceiptItem, type ReceiptItemInput } from './api';

type Language = 'ru' | 'en';

const text = {
  ru: {
    title: 'Черновик чека', total: 'Кассовый итог', item: 'Товар в чеке', merchant: 'Магазин',
    create: 'Создать черновик чека', confirm: 'Подтвердить расход', independent: 'Это отдельная покупка',
    duplicate: 'Это дубль', candidate: 'Возможный дубль', noCandidate: 'Совпадений за последние 10 минут нет.',
    decision: 'Решение', unknown: 'Нужно проверить', independentSaved: 'Отдельная покупка', duplicateSaved: 'Отмечен как дубль',
    confirmed: 'Расход записан', error: 'Не удалось обработать чек', loading: 'Загрузка…',
    draft: 'Черновик создан. Проверьте совпадения до записи расхода.',
    cashTotal: 'Итог кассы', itemsTotal: 'Сумма позиций', quantity: 'Количество', price: 'Цена за единицу',
    lineSum: 'Сумма позиции', edit: 'Изменить', saveItem: 'Сохранить позицию', cancel: 'Не менять',
    deleteItem: 'Удалить', addItem: 'Добавить позицию', syncTotal: 'Синхронизировать итог',
    previous: 'Назад', next: 'Далее', page: 'Страница', category: 'Категория чека',
    saveCategory: 'Сохранить категорию', categorySource: 'Источник категории', human: 'вручную', rule: 'правило',
    model: 'модель', default: 'по умолчанию', unknownSource: 'не определён', reviewBasket: 'Разобрать корзину',
    reviewProvider: 'Источник разбора', reviewVersion: 'Версия разбора', verdict: 'Оценка', advice: 'Совет',
    reason: 'Причина', action: 'Действие', disputed: 'Спорные позиции', allowProduct: 'Разрешить товар',
    revokeProduct: 'Отозвать разрешение', repeat: 'Повтор', lastPurchase: 'Последняя сумма',
    noWarnings: 'Повторяющихся покупок нет.', photoFile: 'Фото чека', uploadPhoto: 'Загрузить фото чека',
    comparePrice: 'Сравнить цену', hidePrice: 'Скрыть сравнение цены', priceLoading: 'Загрузка истории цен…',
    priceError: 'История цен временно недоступна.', noPriceHistory: 'Пока нет сопоставимых покупок.',
    currentPrice: 'Сейчас', usualPrice: 'Обычно', priceUp: 'Подорожание', priceDown: 'Снижение',
    priceMovement: 'Изменение', purchases: 'Покупок', priceChart: 'История цены', perUnit: '/ед.',
    noPriceInputs: 'Для сравнения нужны корректные количество и сумма позиции.',
    photoWorking: 'Чек обрабатывается', photoReady: 'Чек готов', photoFailed: 'Не удалось распознать чек',
    photoRetrying: 'Повторная попытка обработки', checkPhotoStatus: 'Проверить статус', photoPollStopped: 'Автообновление остановлено',
    recognizedText: 'Распознанный текст', recognizedWords: 'Слова и координаты', visionReading: 'Чтение Vision',
    visionModel: 'Модель Vision', ocrModel: 'Модель OCR', visionFallback: 'Сбой Vision, использован OCR',
    ocrFallback: 'Сбой OCR, использован Vision', modelFallback: 'Резервная модель Vision',
    reconciliation: 'Сверка', reconciliationAuto: 'чтения согласованы',
    reconciliationReview: 'нужно проверить расхождения', reconciliationInsufficient: 'недостаточно данных',
    selectedReader: 'Источник расчёта', noSelectedReader: 'не выбран', ocrItems: 'Позиции OCR',
    itemEvidence: 'Сопоставление позиций', corroborated: 'суммы совпали', amountDisagrees: 'суммы расходятся',
    amountUnknown: 'сумма неизвестна', readerOnly: 'есть только у одного читателя',
    suggestedTopUps: 'Подсказки добора из OCR',
    visionUnverified: 'Данные модели — проверьте перед использованием', visionDate: 'Дата в чтении Vision',
  },
  en: {
    title: 'Receipt draft', total: 'Receipt total', item: 'Item on receipt', merchant: 'Merchant',
    create: 'Create receipt draft', confirm: 'Confirm expense', independent: 'This is a separate purchase',
    duplicate: 'This is a duplicate', candidate: 'Possible duplicate', noCandidate: 'No matches in the last 10 minutes.',
    decision: 'Decision', unknown: 'Review needed', independentSaved: 'Separate purchase', duplicateSaved: 'Marked as duplicate',
    confirmed: 'Expense recorded', error: 'Could not process receipt', loading: 'Loading…',
    draft: 'Draft created. Review matches before recording the expense.',
    cashTotal: 'Cash total', itemsTotal: 'Item total', quantity: 'Quantity', price: 'Unit price',
    lineSum: 'Line total', edit: 'Edit', saveItem: 'Save item', cancel: 'Cancel', deleteItem: 'Delete',
    addItem: 'Add item', syncTotal: 'Sync receipt total', previous: 'Previous', next: 'Next', page: 'Page',
    category: 'Receipt category', saveCategory: 'Save category', categorySource: 'Category source', human: 'manual',
    rule: 'rule', model: 'model', default: 'default', unknownSource: 'unknown', reviewBasket: 'Review basket',
    reviewProvider: 'Review source', reviewVersion: 'Review version', verdict: 'Verdict', advice: 'Advice',
    reason: 'Reason', action: 'Action', disputed: 'Disputed items', allowProduct: 'Allow product',
    revokeProduct: 'Revoke allowance', repeat: 'Repeat', lastPurchase: 'Last amount', noWarnings: 'No repeat purchases found.',
    photoFile: 'Receipt photo', uploadPhoto: 'Upload receipt photo', photoWorking: 'Processing receipt',
    photoReady: 'Receipt ready', photoFailed: 'Could not read receipt', photoRetrying: 'Retrying receipt processing',
    checkPhotoStatus: 'Check status', photoPollStopped: 'Automatic updates stopped', recognizedText: 'Recognized text',
    recognizedWords: 'Words and coordinates', visionReading: 'Vision reading', visionModel: 'Vision model', ocrModel: 'OCR model',
    visionFallback: 'Vision unavailable; OCR was used', ocrFallback: 'OCR unavailable; Vision was used',
    modelFallback: 'Vision model fallback',
    reconciliation: 'Reconciliation', reconciliationAuto: 'readings agree',
    reconciliationReview: 'review differences', reconciliationInsufficient: 'not enough data',
    selectedReader: 'Selected reading', noSelectedReader: 'none selected', ocrItems: 'OCR items',
    itemEvidence: 'Item matching', corroborated: 'amounts agree', amountDisagrees: 'amounts differ',
    amountUnknown: 'amount unknown', readerOnly: 'found by one reader only',
    suggestedTopUps: 'OCR items to review',
    comparePrice: 'Compare price', hidePrice: 'Hide price comparison', priceLoading: 'Loading price history…',
    priceError: 'Price history is temporarily unavailable.', noPriceHistory: 'No comparable purchases yet.',
    currentPrice: 'Current', usualPrice: 'Usual', priceUp: 'Price increase', priceDown: 'Price decrease',
    priceMovement: 'Change', purchases: 'Purchases', priceChart: 'Price history', perUnit: '/unit',
    noPriceInputs: 'A valid quantity and item total are required for comparison.',
    visionUnverified: 'Model reading — verify before use', visionDate: 'Vision reading date',
  },
} as const;

type Translations = typeof text[Language];

export function ReceiptsPanel({ tenantId, language, canWrite }: {
  tenantId: string; language: Language; canWrite: boolean;
}) {
  const t = text[language];
  const reconciliationStatus = {
    auto_selected: t.reconciliationAuto,
    review_required: t.reconciliationReview,
    insufficient_data: t.reconciliationInsufficient,
  } as const;
  const itemEvidenceStatus = {
    corroborated: t.corroborated,
    amount_disagrees: t.amountDisagrees,
    amount_unknown: t.amountUnknown,
    reader_only: t.readerOnly,
  } as const;
  const queryClient = useQueryClient();
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [photoFile, setPhotoFile] = useState<File | null>(null);
  const [photoJobId, setPhotoJobId] = useState<string | null>(null);
  const [photoKey, setPhotoKey] = useState(() => crypto.randomUUID());
  const [pollStartedAt, setPollStartedAt] = useState<number | null>(null);
  const [pollingStopped, setPollingStopped] = useState(false);
  const [cashTotal, setCashTotal] = useState('');
  const [itemName, setItemName] = useState('');
  const [merchant, setMerchant] = useState('');
  const [receiptDate, setReceiptDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [createKey, setCreateKey] = useState(() => crypto.randomUUID());
  const [confirmKey, setConfirmKey] = useState(() => crypto.randomUUID());
  const [page, setPage] = useState(1);
  const [editingItemId, setEditingItemId] = useState<string | null>(null);
  const [itemDraft, setItemDraft] = useState({ name: '', quantity: '1', unitPrice: '', lineSum: '' });
  const [newItem, setNewItem] = useState({ name: '', quantity: '1', unitPrice: '', lineSum: '' });
  const [basketReviewed, setBasketReviewed] = useState(false);
  const [categoryCode, setCategoryCode] = useState('');

  const candidates = useQuery({
    queryKey: ['receipt-duplicate-candidates', tenantId, receipt?.id],
    queryFn: () => api.getReceiptDuplicateCandidates(tenantId, receipt!.id),
    enabled: Boolean(receipt) && receipt?.state !== 'confirmed',
    retry: false,
  });
  const items = useQuery({
    queryKey: ['receipt-items', tenantId, receipt?.id, page],
    queryFn: () => api.getReceiptItems(tenantId, receipt!.id, page),
    enabled: Boolean(receipt) && receipt?.state !== 'confirmed',
    retry: false,
  });
  const disputedItems = useQuery({
    queryKey: ['receipt-disputed-items', tenantId, receipt?.id, page],
    queryFn: () => api.getDisputedReceiptItems(tenantId, receipt!.id, page),
    enabled: Boolean(receipt && basketReviewed && receipt.state !== 'confirmed'),
    retry: false,
  });
  const repeatWarnings = useQuery({
    queryKey: ['receipt-repeat-warnings', tenantId, receipt?.id],
    queryFn: () => api.getReceiptRepeatWarnings(tenantId, receipt!.id),
    enabled: Boolean(receipt && basketReviewed && receipt.state !== 'confirmed'),
    retry: false,
  });
  const allowedProducts = useQuery({
    queryKey: ['receipt-allowed-products', tenantId],
    queryFn: () => api.getAllowedProductDecisions(tenantId),
    enabled: Boolean(receipt && basketReviewed && receipt.state !== 'confirmed'),
    retry: false,
  });
  const create = useMutation({
    mutationFn: (request: CreateReceipt) => api.createReceipt(tenantId, request, createKey),
    onSuccess: (value) => {
      setReceipt(value);
      setBasketReviewed(false); setCategoryCode(value.categoryCode ?? '');
      setCreateKey(crypto.randomUUID());
      setConfirmKey(crypto.randomUUID());
      setCashTotal(''); setItemName(''); setMerchant('');
    },
  });
  const uploadPhoto = useMutation({
    mutationFn: ({ file, key }: { file: File; key: string }) => api.uploadReceiptPhoto(tenantId, file, key),
    onSuccess: (job) => {
      setPhotoJobId(job.id);
      setPollStartedAt(Date.now());
      setPollingStopped(false);
      setReceipt(null);
      setPhotoFile(null);
      setPhotoKey(crypto.randomUUID());
      setBasketReviewed(false);
    },
  });
  const photoJob = useQuery({
    queryKey: ['receipt-processing-job', tenantId, photoJobId],
    queryFn: () => api.getReceiptJob(tenantId, photoJobId!),
    enabled: Boolean(photoJobId),
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state;
      if (pollingStopped || state === 'completed' || state === 'rejected') return false;
      return state === 'retryable' ? 5000 : 1000;
    },
  });
  useEffect(() => {
    const state = photoJob.data?.state;
    if (!photoJobId || !pollStartedAt || state === 'completed' || state === 'rejected') return;
    const remaining = Math.max(0, 15 * 60 * 1000 - (Date.now() - pollStartedAt));
    const timer = window.setTimeout(() => setPollingStopped(true), remaining);
    return () => window.clearTimeout(timer);
  }, [photoJobId, photoJob.data?.state, pollStartedAt]);
  const photoReceipt = useQuery({
    queryKey: ['receipt-photo-draft', tenantId, photoJob.data?.receiptId],
    queryFn: () => api.getReceipt(tenantId, photoJob.data!.receiptId!),
    enabled: photoJob.data?.state === 'completed' && Boolean(photoJob.data.receiptId),
    retry: false,
  });
  useEffect(() => {
    if (photoReceipt.data) {
      setReceipt(photoReceipt.data);
      setCategoryCode(photoReceipt.data.categoryCode ?? '');
      setBasketReviewed(false);
    }
  }, [photoReceipt.data]);
  const ocrReading = useQuery({
    queryKey: ['receipt-reading', tenantId, receipt?.id],
    queryFn: () => api.getReceiptReading(tenantId, receipt!.id),
    enabled: Boolean(receipt?.documentId && receipt.selectedReader !== 'manual'),
    retry: false,
  });
  const saveDecision = useMutation({
    mutationFn: ({ decision, duplicateReceiptId }: { decision: 'independent' | 'duplicate'; duplicateReceiptId: string | null }) => {
      if (!receipt) throw new Error(t.error);
      return api.decideReceiptDuplicate(tenantId, receipt.id, receipt.version, decision, duplicateReceiptId);
    },
    onSuccess: async (value) => {
      setReceipt(value);
      await queryClient.invalidateQueries({ queryKey: ['receipt-duplicate-candidates', tenantId, value.id] });
    },
  });
  const confirm = useMutation({
    mutationFn: () => {
      if (!receipt) throw new Error(t.error);
      return api.confirmReceipt(tenantId, receipt.id, receipt.version, confirmKey);
    },
    onSuccess: async (value) => {
      setReceipt(value);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['transactions', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['summary', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['budgets', tenantId] }),
      ]);
    },
  });
  const invalidateItems = async (id: string) => queryClient.invalidateQueries({
    queryKey: ['receipt-items', tenantId, id],
  });
  const updateItem = useMutation({
    mutationFn: ({ itemId, input }: { itemId: string; input: ReceiptItemInput }) => {
      if (!receipt) throw new Error(t.error);
      return api.updateReceiptItem(tenantId, receipt.id, itemId, receipt.version, input);
    },
    onSuccess: async (value) => {
      setReceipt(value); setEditingItemId(null);
      await invalidateItems(value.id);
      await queryClient.invalidateQueries({ queryKey: ['receipt-duplicate-candidates', tenantId, value.id] });
    },
  });
  const deleteItem = useMutation({
    mutationFn: (itemId: string) => {
      if (!receipt) throw new Error(t.error);
      return api.deleteReceiptItem(tenantId, receipt.id, itemId, receipt.version);
    },
    onSuccess: async (value) => {
      setReceipt(value); await invalidateItems(value.id);
    },
  });
  const addItem = useMutation({
    mutationFn: (input: ReceiptItemInput) => {
      if (!receipt) throw new Error(t.error);
      return api.addReceiptItem(tenantId, receipt.id, receipt.version, input);
    },
    onSuccess: async (value) => {
      setReceipt(value); setPage(1); setNewItem({ name: '', quantity: '1', unitPrice: '', lineSum: '' });
      await invalidateItems(value.id);
    },
  });
  const syncTotal = useMutation({
    mutationFn: () => {
      if (!receipt) throw new Error(t.error);
      return api.syncReceiptTotal(tenantId, receipt.id, receipt.version);
    },
    onSuccess: async (value) => {
      setReceipt(value);
      await Promise.all([invalidateItems(value.id),
        queryClient.invalidateQueries({ queryKey: ['receipt-duplicate-candidates', tenantId, value.id] })]);
    },
  });
  const selectCategory = useMutation({
    mutationFn: () => {
      if (!receipt || !categoryCode) throw new Error(t.error);
      return api.selectReceiptCategory(tenantId, receipt.id, receipt.version, categoryCode);
    },
    onSuccess: (value) => { setReceipt(value); setCategoryCode(value.categoryCode ?? ''); },
  });
  const reviewBasket = useMutation({
    mutationFn: () => {
      if (!receipt) throw new Error(t.error);
      return api.reviewReceiptBasket(tenantId, receipt.id, receipt.version);
    },
    onSuccess: async (value) => {
      setReceipt(value); setBasketReviewed(true);
      await Promise.all([
        invalidateItems(value.id),
        queryClient.invalidateQueries({ queryKey: ['receipt-disputed-items', tenantId, value.id] }),
        queryClient.invalidateQueries({ queryKey: ['receipt-repeat-warnings', tenantId, value.id] }),
      ]);
    },
  });
  const decideProduct = useMutation({
    mutationFn: async ({ productKey, allow }: { productKey: string; allow: boolean }) => {
      if (allow) await api.allowReceiptProduct(tenantId, productKey);
      else await api.revokeReceiptProduct(tenantId, productKey);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['receipt-allowed-products', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['receipt-disputed-items', tenantId, receipt?.id] }),
        queryClient.invalidateQueries({ queryKey: ['receipt-repeat-warnings', tenantId, receipt?.id] }),
      ]);
    },
  });

  const candidateList = candidates.data?.candidates ?? [];
  const itemList = items.data?.items ?? (receipt?.state === 'confirmed' ? receipt.items : []) ?? [];
  const itemListReady = Boolean(items.data) || receipt?.state === 'confirmed';
  const amount = cashTotal.trim().replace(',', '.');
  const amountValid = /^(?:0\.(?:0?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\.[0-9]{1,2})?)$/.test(amount);
  const totalsReconciled = Boolean(receipt?.cashTotal && receipt.itemsTotal && receipt.cashTotal === receipt.itemsTotal);
  const canConfirm = Boolean(receipt && canWrite && ['draft', 'review_required'].includes(receipt.state)
    && totalsReconciled && !candidates.isPending
    && !candidates.isError && !confirm.isPending && !saveDecision.isPending
    && receipt.duplicateDecision !== 'duplicate'
    && !(candidateList.length > 0 && receipt.duplicateDecision === 'unknown'));

  return <section className="transactions-layout" aria-label={t.title}>
    <form className="transaction-form panel" aria-label={t.uploadPhoto} onSubmit={(event) => {
      event.preventDefault();
      if (photoFile) uploadPhoto.mutate({ file: photoFile, key: photoKey });
    }}>
      <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2>{t.uploadPhoto}</h2></div></div>
      <fieldset className="transaction-fields" disabled={!canWrite || uploadPhoto.isPending}>
        <label>{t.photoFile}<input aria-label={t.photoFile} type="file" accept="image/jpeg,image/png"
          onChange={(event) => setPhotoFile(event.target.files?.[0] ?? null)} /></label>
        <button className="button button-primary" type="submit" disabled={!photoFile || uploadPhoto.isPending}>
          {uploadPhoto.isPending ? t.loading : t.uploadPhoto}
        </button>
      </fieldset>
    </form>
    {uploadPhoto.error && <p className="form-error" role="alert">{uploadPhoto.error.message || t.error}</p>}
    {photoJob.data && <section className="panel" aria-live="polite">
      <h2>{photoJob.data.state === 'completed' ? t.photoReady
        : photoJob.data.state === 'rejected' ? t.photoFailed
          : photoJob.data.state === 'retryable' ? t.photoRetrying : t.photoWorking}</h2>
      <progress aria-label={t.photoWorking} max={100} value={photoJob.data.progressPercent} />
      <p>{photoJob.data.progressPercent}% · {photoJob.data.attemptCount}/5</p>
      {photoJob.data.errorCode && <p role="alert">{photoJob.data.errorCode}</p>}
      {pollingStopped && <><p>{t.photoPollStopped}</p><button className="button button-quiet" type="button"
        onClick={() => { setPollingStopped(false); setPollStartedAt(Date.now()); void photoJob.refetch(); }}>
        {t.checkPhotoStatus}
      </button></>}
    </section>}
    <form className="transaction-form panel" onSubmit={(event) => {
      event.preventDefault();
      if (!amountValid || !itemName.trim() || !receiptDate) return;
      create.mutate({
        cashTotal: amount,
        merchant: merchant.trim(),
        receiptDate,
        items: [{ name: itemName.trim(), quantity: '1', unitPrice: amount, lineSum: amount }],
      });
    }}>
      <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2>{t.create}</h2></div></div>
      <fieldset className="transaction-fields" disabled={!canWrite || create.isPending}>
        <label>{t.total}<input aria-label={t.total} inputMode="decimal" value={cashTotal}
          onChange={(event) => setCashTotal(event.target.value)} /></label>
        <label>{t.item}<input aria-label={t.item} maxLength={200} value={itemName}
          onChange={(event) => setItemName(event.target.value)} /></label>
        <label>{t.merchant}<input aria-label={t.merchant} maxLength={200} value={merchant}
          onChange={(event) => setMerchant(event.target.value)} /></label>
        <label>{language === 'ru' ? 'Дата чека' : 'Receipt date'}<input type="date" value={receiptDate}
          onChange={(event) => setReceiptDate(event.target.value)} /></label>
        <button className="button button-primary" type="submit" disabled={!amountValid || !itemName.trim()}>
          {create.isPending ? t.loading : t.create}
        </button>
      </fieldset>
    </form>

    {(create.error || saveDecision.error || confirm.error || candidates.error || items.error || updateItem.error
      || deleteItem.error || addItem.error || syncTotal.error || selectCategory.error || reviewBasket.error
      || decideProduct.error || disputedItems.error || repeatWarnings.error || allowedProducts.error
      || ocrReading.error || photoReceipt.error || photoJob.error) &&
      <p className="form-error" role="alert">{(create.error ?? saveDecision.error ?? confirm.error ?? candidates.error
        ?? items.error ?? updateItem.error ?? deleteItem.error ?? addItem.error ?? syncTotal.error ?? selectCategory.error
        ?? reviewBasket.error ?? decideProduct.error ?? disputedItems.error ?? repeatWarnings.error ?? allowedProducts.error
        ?? ocrReading.error ?? photoReceipt.error ?? photoJob.error)?.message ?? t.error}</p>}
    {receipt && <section className="panel draft-review" aria-live="polite">
      <h2>{receipt.state === 'confirmed' ? t.confirmed : t.draft}</h2>
      <p>{receipt.merchant ?? t.merchant} · {receipt.cashTotal} ₽</p>
      <p>{t.cashTotal}: {receipt.cashTotal ?? '—'} ₽ · {t.itemsTotal}: {receipt.itemsTotal ?? '—'} ₽</p>
      <p>{t.categorySource}: {sourceLabel(receipt.categorySource, t)} · {receipt.categoryAlgorithmVersion}</p>
      {ocrReading.data && <section aria-label={t.recognizedText}>
        <h3>{t.recognizedText}</h3>
        {ocrReading.data.modelVersion && <p>{t.ocrModel}: {ocrReading.data.modelVersion}</p>}
        {ocrReading.data.visionFallbackReason && <p>{t.visionFallback}: {ocrReading.data.visionFallbackReason}</p>}
        {ocrReading.data.ocrFallbackReason && <p>{t.ocrFallback}: {ocrReading.data.ocrFallbackReason}</p>}
        {ocrReading.data.ocrItems.length > 0 && <>
          <h4>{t.ocrItems}</h4>
          <ul>{ocrReading.data.ocrItems.map((item, index) => <li key={`${index}-${item.name}`}>
            {item.name} · {item.lineSum ?? '—'} ₽
          </li>)}</ul>
        </>}
        <pre>{ocrReading.data.text || '—'}</pre>
        <details><summary>{t.recognizedWords} ({ocrReading.data.words.length})</summary>
          <ul>{ocrReading.data.words.map((word, index) => <li key={`${index}-${word.box.x}-${word.box.y}`}>
            {word.text} · {word.confidence}% · ({word.box.x}, {word.box.y}, {word.box.width}, {word.box.height})
          </li>)}</ul>
        </details>
      </section>}
      {ocrReading.data?.reconciliation && <section aria-label={t.reconciliation}>
        <h3>{t.reconciliation}: {reconciliationStatus[ocrReading.data.reconciliation.decision]}</h3>
        <p>{t.selectedReader}: {ocrReading.data.reconciliation.selectedReader ?? t.noSelectedReader}</p>
        <p>{t.ocrModel}: {ocrReading.data.reconciliation.ocrItemsTotal ?? '—'} ₽ · {t.visionModel}: {ocrReading.data.reconciliation.visionItemsTotal ?? '—'} ₽</p>
        {ocrReading.data.reconciliation.mismatchFields.length > 0
          && <p>{ocrReading.data.reconciliation.mismatchFields.join(', ')}</p>}
        {ocrReading.data.reconciliation.itemEvidence.length > 0 && <>
          <h4>{t.itemEvidence}</h4>
          <ul>{ocrReading.data.reconciliation.itemEvidence.map((item) => <li key={item.visionOrdinal}>
            Vision #{item.visionOrdinal} ↔ OCR #{item.ocrOrdinal ?? '—'}: {itemEvidenceStatus[item.status]}
          </li>)}</ul>
        </>}
        {ocrReading.data.reconciliation.suggestedTopUps.length > 0 && <>
          <h4>{t.suggestedTopUps}</h4>
          <ul>{ocrReading.data.reconciliation.suggestedTopUps.map((item) => <li key={item.ocrOrdinal}>
            OCR #{item.ocrOrdinal}: {item.name} · {item.lineSum} ₽
          </li>)}</ul>
        </>}
      </section>}
      {ocrReading.data?.vision && <section aria-label={t.visionReading}>
        <h3>{t.visionReading}</h3>
        <p>{t.visionModel}: {ocrReading.data.vision.modelVersion}</p>
        <p>{t.visionUnverified}</p>
        {ocrReading.data.vision.fallbackReason && <p>{t.modelFallback}: {ocrReading.data.vision.fallbackReason}</p>}
        <p>{t.merchant}: {ocrReading.data.vision.store ?? '—'} · {t.total}: {ocrReading.data.vision.total ?? '—'} ₽</p>
        <p>{t.visionDate}: {ocrReading.data.vision.date ?? '—'}</p>
        <ul>{ocrReading.data.vision.items.map((item, index) => <li key={`${index}-${item.name}`}>
          {item.name} · {item.lineSum ?? '—'} ₽
        </li>)}</ul>
      </section>}
      {receipt.alcoholShare && <p>{language === 'ru' ? 'Доля алкоголя' : 'Alcohol share'}: {receipt.alcoholShare}</p>}
      {receipt.leisureShare && <p>{language === 'ru' ? 'Доля досуга' : 'Leisure share'}: {receipt.leisureShare}</p>}
      {receipt.state !== 'confirmed' && <div className="receipt-category">
        <label>{t.category}<select aria-label={t.category} value={categoryCode || receipt.categoryCode || ''}
          onChange={(event) => setCategoryCode(event.target.value)}>
          <option value="">—</option>
          {RECEIPT_CATEGORIES.map((code) => <option key={code} value={code}>{categoryLabel(code, language)}</option>)}
        </select></label>
        <button className="button button-quiet" type="button" disabled={!canWrite || !categoryCode || selectCategory.isPending}
          onClick={() => selectCategory.mutate()}>{selectCategory.isPending ? t.loading : t.saveCategory}</button>
      </div>}
      {receipt.state !== 'confirmed' && <button className="button button-quiet" type="button"
        disabled={!canWrite || reviewBasket.isPending} onClick={() => reviewBasket.mutate()}>
        {reviewBasket.isPending ? t.loading : t.reviewBasket}
      </button>}
      {items.isPending && receipt.state !== 'confirmed' && <p>{t.loading}</p>}
      {itemListReady && <>
        <div className="receipt-items" aria-label={t.title}>
          {itemList.map((item: ReceiptItem) => <article className="transaction-row" key={item.id}>
            {editingItemId === item.id ? <form onSubmit={(event) => {
              event.preventDefault();
              updateItem.mutate({ itemId: item.id, input: toItemInput(itemDraft) });
            }}>
              <label>{t.item}<input aria-label={t.item} value={itemDraft.name}
                onChange={(event) => setItemDraft({ ...itemDraft, name: event.target.value })} /></label>
              <label>{t.quantity}<input aria-label={t.quantity} inputMode="decimal" value={itemDraft.quantity}
                onChange={(event) => setItemDraft({ ...itemDraft, quantity: event.target.value })} /></label>
              <label>{t.price}<input aria-label={t.price} inputMode="decimal" value={itemDraft.unitPrice}
                onChange={(event) => setItemDraft({ ...itemDraft, unitPrice: event.target.value })} /></label>
              <label>{t.lineSum}<input aria-label={t.lineSum} inputMode="decimal" value={itemDraft.lineSum}
                onChange={(event) => setItemDraft({ ...itemDraft, lineSum: event.target.value })} /></label>
              <button className="button button-primary" type="submit" disabled={!canWrite || updateItem.isPending}>{t.saveItem}</button>
              <button className="button button-quiet" type="button" onClick={() => setEditingItemId(null)}>{t.cancel}</button>
            </form> : <>
              <span>{item.name} · {item.lineSum ?? '—'} ₽</span>
              {receipt.state === 'confirmed' && <ReceiptPriceComparison tenantId={tenantId} receiptId={receipt.id}
                item={item} language={language} t={t} />}
              {(item.verdict || item.advice || item.reviewReason || item.reviewAction) && <div className="receipt-item-review">
                {item.verdict && <p>{t.verdict}: {item.verdict}</p>}
                {item.reviewReason && <p>{t.reason}: {item.reviewReason}</p>}
                {item.advice && <p>{t.advice}: {item.advice}</p>}
                {item.reviewAction && <p>{t.action}: {item.reviewAction}</p>}
                <p>{t.reviewProvider}: {item.reviewProvider ?? item.verdictSource} · {t.reviewVersion}: {item.reviewAlgorithmVersion}</p>
                {item.productKey && <button className="button button-quiet" type="button"
                  disabled={!canWrite || decideProduct.isPending || allowedProducts.isPending}
                  onClick={() => decideProduct.mutate({ productKey: item.productKey,
                    allow: !(allowedProducts.data?.productKeys ?? []).includes(item.productKey) })}>
                  {(allowedProducts.data?.productKeys ?? []).includes(item.productKey)
                    ? `${t.revokeProduct} ${item.name}` : `${t.allowProduct} ${item.name}`}
                </button>}
              </div>}
              {receipt.state !== 'confirmed' && <>
                <button className="button button-quiet" type="button" disabled={!canWrite || updateItem.isPending || deleteItem.isPending}
                  aria-label={`${t.edit} ${item.name}`} onClick={() => {
                    setEditingItemId(item.id);
                    setItemDraft({ name: item.name, quantity: item.quantity ?? '', unitPrice: item.unitPrice ?? '', lineSum: item.lineSum ?? '' });
                  }}>{t.edit}</button>
                <button className="button button-quiet" type="button" disabled={!canWrite || deleteItem.isPending || updateItem.isPending}
                  aria-label={`${t.deleteItem} ${item.name}`} onClick={() => deleteItem.mutate(item.id)}>{t.deleteItem}</button>
              </>}
            </>}
          </article>)}
        </div>
        {receipt.state !== 'confirmed' && (items.data?.totalItems ?? 0) > 8 && <div className="quick-amounts" aria-label={t.page}>
          <button className="button button-quiet" type="button" disabled={page <= 1}
            onClick={() => setPage((current) => current - 1)}>{t.previous}</button>
          <span>{t.page} {items.data!.page}</span>
          <button className="button button-quiet" type="button" disabled={!items.data!.hasMore}
            onClick={() => setPage((current) => current + 1)}>{t.next}</button>
        </div>}
        {receipt.state !== 'confirmed' && <>
          <form className="receipt-add-item" aria-label={t.addItem} onSubmit={(event) => {
            event.preventDefault();
            if (!newItem.name.trim()) return;
            addItem.mutate(toItemInput(newItem));
          }}>
            <label>{language === 'ru' ? 'Новая позиция' : 'New item'}<input value={newItem.name}
              onChange={(event) => setNewItem({ ...newItem, name: event.target.value })} /></label>
            <label>{t.quantity}<input inputMode="decimal" value={newItem.quantity}
              onChange={(event) => setNewItem({ ...newItem, quantity: event.target.value })} /></label>
            <label>{t.price}<input inputMode="decimal" value={newItem.unitPrice}
              onChange={(event) => setNewItem({ ...newItem, unitPrice: event.target.value })} /></label>
            <label>{t.lineSum}<input inputMode="decimal" value={newItem.lineSum}
              onChange={(event) => setNewItem({ ...newItem, lineSum: event.target.value })} /></label>
            <button className="button button-quiet" type="submit" disabled={!canWrite || !newItem.name.trim() || addItem.isPending}>{t.addItem}</button>
          </form>
          {receipt.itemsTotal && receipt.cashTotal && receipt.itemsTotal !== receipt.cashTotal &&
            <button className="button button-quiet" type="button" disabled={!canWrite || syncTotal.isPending}
              onClick={() => syncTotal.mutate()}>{syncTotal.isPending ? t.loading : t.syncTotal}</button>}
        </>}
      </>}
      {basketReviewed && receipt.state !== 'confirmed' && <>
        <section className="receipt-disputed" aria-label={t.disputed}>
          <h3>{t.disputed}</h3>
          {disputedItems.isPending ? <p>{t.loading}</p> : disputedItems.data?.items.map((item) =>
            <p key={item.id}>{item.name} · {item.verdict ?? t.unknown}</p>)}
        </section>
        <section className="receipt-repeat-warnings" aria-label={t.repeat}>
          <h3>{t.repeat}</h3>
          {repeatWarnings.isPending ? <p>{t.loading}</p> : repeatWarnings.data?.warnings.length
            ? repeatWarnings.data.warnings.map((warning) => <p key={warning.itemId}>
              {warning.title}: {warning.name} · {t.repeat}: {warning.count}
              {warning.lastSum ? ` · ${t.lastPurchase}: ${warning.lastSum} ₽` : ''}
              {warning.advice ? ` · ${t.advice}: ${warning.advice}` : ''}
            </p>) : <p>{t.noWarnings}</p>}
        </section>
      </>}
      <p>{t.decision}: {receipt.duplicateDecision === 'independent' ? t.independentSaved
        : receipt.duplicateDecision === 'duplicate' ? t.duplicateSaved : t.unknown}</p>
      {candidates.isPending && <p>{t.loading}</p>}
      {!candidates.isPending && !candidates.isError && candidateList.length === 0 && <p>{t.noCandidate}</p>}
      {candidateList.length > 0 && <div className="receipt-candidates" aria-label={t.candidate}>
        <h3>{t.candidate}</h3>
        {candidateList.map((candidate) => <article className="transaction-row" key={candidate.id}>
          <span>{candidate.merchant ?? t.merchant} · {candidate.cashTotal} ₽</span>
          <button className="button button-quiet" type="button" disabled={!canWrite || saveDecision.isPending || receipt.state === 'confirmed'}
            onClick={() => saveDecision.mutate({ decision: 'duplicate', duplicateReceiptId: candidate.id })}>
            {t.duplicate}: {candidate.merchant ?? candidate.cashTotal}
          </button>
        </article>)}
        <button className="button button-quiet" type="button" disabled={!canWrite || saveDecision.isPending || receipt.state === 'confirmed'}
          onClick={() => saveDecision.mutate({ decision: 'independent', duplicateReceiptId: null })}>
          {t.independent}
        </button>
      </div>}
      {['draft', 'review_required'].includes(receipt.state) && <button className="button button-primary" type="button" disabled={!canConfirm}
        onClick={() => confirm.mutate()}>
        {confirm.isPending ? t.loading : t.confirm}
      </button>}
      {receipt.transactionId && <p>{receipt.transactionId}</p>}
    </section>}
  </section>;
}

function toItemInput(item: { name: string; quantity: string; unitPrice: string; lineSum: string }): ReceiptItemInput {
  const decimal = (value: string) => value.trim() ? value.trim().replace(',', '.') : null;
  return { name: item.name.trim(), quantity: item.quantity.trim().replace(',', '.'),
    unitPrice: decimal(item.unitPrice), lineSum: decimal(item.lineSum) };
}

function ReceiptPriceComparison({ tenantId, receiptId, item, language, t }: {
  tenantId: string; receiptId: string; item: ReceiptItem; language: Language; t: Translations;
}) {
  const [open, setOpen] = useState(false);
  const comparison = useQuery({
    queryKey: ['receipt-price-history', tenantId, receiptId, item.id],
    queryFn: () => api.getReceiptPriceHistory(tenantId, receiptId, item.id),
    enabled: open,
    retry: false,
  });
  const usable = (value: string | null) => value !== null && /^\d+(?:\.\d+)?$/.test(value)
    && Number.isFinite(Number(value)) && Number(value) > 0;
  if (!usable(item.quantity) || !usable(item.lineSum)) return <p>{t.noPriceInputs}</p>;

  return <div className="receipt-price-comparison">
    <button className="button button-quiet" type="button" aria-expanded={open}
      aria-label={`${open ? t.hidePrice : t.comparePrice} ${item.name}`} onClick={() => setOpen((value) => !value)}>
      {open ? t.hidePrice : t.comparePrice}
    </button>
    {open && <div aria-live="polite">
      {comparison.isPending && <p>{t.priceLoading}</p>}
      {comparison.isError && <p className="form-error" role="alert">{t.priceError}</p>}
      {comparison.data && <PriceHistory comparison={comparison.data} language={language} t={t} />}
    </div>}
  </div>;
}

function PriceHistory({ comparison, language, t }: {
  comparison: ProductPriceComparison; language: Language; t: Translations;
}) {
  const locale = language === 'ru' ? 'ru-RU' : 'en-US';
  const money = (value: string) => new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(Number(value));
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(value));
  const shown = comparison.history.slice(-12);
  const values = shown.map((point) => Number(point.unitPrice));
  const canDraw = values.length >= 2 && values.every(Number.isFinite);
  const baseline = comparison.hasBaseline && comparison.baselineUnitPrice !== null
    ? Number(comparison.baselineUnitPrice) : null;
  const chartValues = baseline === null ? values : [...values, baseline];
  const min = Math.min(...chartValues);
  const max = Math.max(...chartValues);
  const span = max - min || Math.abs(max) * 0.12 || 1;
  const y = (value: number) => 12 + ((max + span * 0.15 - value) / (span * 1.3)) * 76;
  const coordinates = values.map((value, index) => ({
    x: 18 + (values.length === 1 ? 142 : index * 284 / (values.length - 1)), y: y(value),
  }));
  const percent = comparison.relative === null ? null : new Intl.NumberFormat(locale, {
    style: 'percent', maximumFractionDigits: 1, signDisplay: 'exceptZero',
  }).format(Number(comparison.relative));
  const movement = comparison.direction === 'up' ? t.priceUp
    : comparison.direction === 'down' ? t.priceDown : t.priceMovement;

  return <section className="price-history" aria-label={`${t.priceChart}: ${comparison.productName}`}>
    <p>{t.currentPrice}: {money(comparison.currentUnitPrice)}{t.perUnit}</p>
    {comparison.hasBaseline && comparison.baselineUnitPrice !== null && <>
      <p>{t.usualPrice}: {money(comparison.baselineUnitPrice)}{t.perUnit} · {t.purchases}: {comparison.priorPurchases}</p>
      {percent !== null && <p>{movement}: {percent}</p>}
    </>}
    {!comparison.hasBaseline && <p>{t.noPriceHistory}</p>}
    {canDraw && <svg className="price-history-chart" role="img" aria-label={`${t.priceChart}: ${comparison.productName}`}
      viewBox="0 0 320 112" preserveAspectRatio="none">
      {baseline !== null && <line x1="18" x2="302" y1={y(baseline)} y2={y(baseline)}
        className="price-history-baseline" />}
      <polyline points={coordinates.map((point) => `${point.x},${point.y}`).join(' ')} className="price-history-line" />
      {coordinates.map((point, index) => <circle key={`${shown[index].receiptId}-${index}`}
        cx={point.x} cy={point.y} r="4" className={shown[index].current
          ? 'price-history-point-current' : 'price-history-point'} />)}
    </svg>}
    <ul>{shown.slice(-6).map((point) => <li key={`${point.receiptId}-${point.itemId}`}>
      {date(point.purchasedAt)} — {money(point.unitPrice)}{point.merchant ? ` · ${point.merchant}` : ''}
    </li>)}</ul>
  </section>;
}

const RECEIPT_CATEGORIES = ['еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее'] as const;
type ReceiptCategory = typeof RECEIPT_CATEGORIES[number];

function categoryLabel(code: ReceiptCategory, language: Language): string {
  const labels: Record<ReceiptCategory, { ru: string; en: string }> = {
    еда: { ru: 'Еда', en: 'Food' }, транспорт: { ru: 'Транспорт', en: 'Transport' },
    жилье: { ru: 'Жильё', en: 'Housing' }, досуг: { ru: 'Досуг', en: 'Leisure' },
    одежда: { ru: 'Одежда', en: 'Clothing' }, здоровье: { ru: 'Здоровье', en: 'Health' },
    работа: { ru: 'Работа', en: 'Work' }, техника: { ru: 'Техника', en: 'Technology' },
    долги: { ru: 'Долги', en: 'Debt' }, прочее: { ru: 'Прочее', en: 'Other' },
  };
  return labels[code][language];
}

function sourceLabel(source: Receipt['categorySource'], t: {
  human: string; rule: string; model: string; default: string; unknownSource: string;
}): string {
  return ({ human: t.human, rule: t.rule, model: t.model, default: t.default, unknown: t.unknownSource })[source];
}

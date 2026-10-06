import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type AdviceEvidenceGroup } from './api';

const copy = {
  ru: {
    title: 'Не брать', banned: 'Не брать', guesses: 'Догадки модели', unverified: 'Непроверенные отметки',
    guessNote: 'Модель только предлагает проверить товар. Покупка остаётся видимой, пока вы не подтвердите решение.',
    unverifiedNote: 'Источник части отметок неизвестен. Покупка остаётся видимой, пока вы не подтвердите решение.',
    rule: 'Основано на проверке чеков', confirmed: 'Подтверждено вами',
    confirm: 'Подтвердить «не брать»', allow: 'Можно брать', revoke: 'Отменить решение',
    count: (value: number) => `Отмечено в чеках: ${value}`,
    amount: (value: string) => `Сумма: ${value} ₽`, missing: 'Сумма неполная',
    empty: 'Повторяющихся отметок «вредно» или «лишнее» пока нет.',
    unavailable: 'Советы по чекам временно недоступны.', retry: 'Повторить',
    decisionError: 'Не удалось сохранить решение. Повторите попытку.',
  },
  en: {
    title: 'Do not buy', banned: 'Do not buy', guesses: 'Model guesses', unverified: 'Unverified evidence',
    guessNote: 'The model only suggests a review. The product remains in shopping until you confirm the decision.',
    unverifiedNote: 'Some verdict sources are unknown. The product remains in shopping until you confirm the decision.',
    rule: 'Based on receipt review', confirmed: 'Confirmed by you',
    confirm: 'Confirm do not buy', allow: 'Allow purchase', revoke: 'Undo decision',
    count: (value: number) => `Flagged receipts: ${value}`,
    amount: (value: string) => `Amount: ${value} RUB`, missing: 'Amount is incomplete',
    empty: 'No repeated harmful or unnecessary verdicts yet.',
    unavailable: 'Advice evidence is temporarily unavailable.', retry: 'Retry',
    decisionError: 'Could not save the decision. Try again.',
  },
} as const;

export function DoNotBuyPanel({ tenantId, language, canWrite }: {
  tenantId: string; language: 'ru' | 'en'; canWrite: boolean;
}) {
  const t = copy[language];
  const queryClient = useQueryClient();
  const evidence = useQuery({ queryKey: ['do-not-buy', tenantId], queryFn: () => api.getDoNotBuyList(tenantId), retry: false });
  const decisions = useQuery({ queryKey: ['product-decisions', tenantId],
    queryFn: () => api.getAllowedProductDecisions(tenantId), retry: false });
  const decision = useMutation({
    mutationFn: async ({ key, action }: { key: string; action: 'confirm' | 'allow' | 'revoke' }) => {
      if (action === 'confirm') await api.confirmNotToBuyProduct(tenantId, key);
      else if (action === 'allow') await api.allowReceiptProduct(tenantId, key);
      else await api.revokeReceiptProduct(tenantId, key);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['do-not-buy', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['product-decisions', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['shopping-candidates', tenantId] }),
      ]);
    },
  });
  const canDecide = canWrite && !decisions.isPending && !decisions.isError && !decision.isPending;
  const action = (key: string, value: 'confirm' | 'allow' | 'revoke') => decision.mutate({ key, action: value });
  const item = (group: AdviceEvidenceGroup, guess: boolean) => {
    const confirmed = decisions.data?.confirmedProductKeys.includes(group.productKey) ?? false;
    return <article className="advice-evidence-row" key={group.productKey}>
      <div><h4>{group.productName}</h4>
        <p><span>{confirmed ? t.confirmed : guess ? group.modelOnly ? t.guesses :
          (language === 'ru' ? 'Источник части отметок неизвестен' : 'Some verdict sources are unknown') : t.rule}</span> · {t.count(group.count)} ·
          {' '}{group.amount === null ? t.missing : t.amount(group.amount)}</p>
        {group.latestAdvice && <p>{group.latestAdvice}</p>}
      </div>
      {canWrite && <div className="advice-evidence-actions">
        {guess && <button className="button button-primary" disabled={!canDecide}
          onClick={() => action(group.productKey, 'confirm')}>{t.confirm}</button>}
        <button className="button" disabled={!canDecide}
          onClick={() => action(group.productKey, 'allow')}>{t.allow}</button>
        {confirmed && <button className="button" disabled={!canDecide}
          onClick={() => action(group.productKey, 'revoke')}>{t.revoke}</button>}
      </div>}
    </article>;
  };

  return <section className="panel do-not-buy-panel" aria-labelledby="do-not-buy-title" aria-busy={evidence.isPending}>
    <div className="panel-heading"><div><span className="eyebrow">{t.title}</span><h2 id="do-not-buy-title">{t.title}</h2></div></div>
    {evidence.isPending ? <p className="empty-state">{language === 'ru' ? 'Загрузка…' : 'Loading…'}</p>
      : evidence.isError || !evidence.data?.available ? <div className="empty-state" role="alert">
        <p>{t.unavailable}</p><button className="button" onClick={() => void evidence.refetch()}>{t.retry}</button>
      </div>
        : evidence.data.banned.length === 0 && evidence.data.guesses.length === 0
          ? <p className="empty-state">{t.empty}</p>
          : <>
            {evidence.data.banned.length > 0 && <section aria-labelledby="do-not-buy-banned-title">
              <h3 id="do-not-buy-banned-title">{t.banned}</h3>
              <div className="advice-evidence-list">{evidence.data.banned.map((group) => item(group, false))}</div>
            </section>}
            {evidence.data.guesses.length > 0 && <section aria-labelledby="do-not-buy-guesses-title">
              <h3 id="do-not-buy-guesses-title">{evidence.data.guesses.some((group) => !group.modelOnly) ? t.unverified : t.guesses}</h3>
              <p className="product-note">{evidence.data.guesses.some((group) => !group.modelOnly)
                ? t.unverifiedNote : t.guessNote}</p>
              <div className="advice-evidence-list">{evidence.data.guesses.map((group) => item(group, true))}</div>
            </section>}
          </>}
    {decision.isError && <p role="alert">{t.decisionError}</p>}
  </section>;
}

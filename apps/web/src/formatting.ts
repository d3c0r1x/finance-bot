export type Language = 'ru' | 'en';
export type SemanticStatusKind = 'limitStatus' | 'paceStatus';

const DECIMAL_PATTERN = /^(-?)(\d{1,30})(?:\.(\d{1,12}))?$/;
const DECIMAL_SCALE = 1_000_000_000_000n;

function scaledDecimal(value: string): bigint | null {
  const match = DECIMAL_PATTERN.exec(value);
  if (!match) return null;
  const [, sign, integer, fraction = ''] = match;
  const scaled = BigInt(integer) * DECIMAL_SCALE + BigInt((fraction + '0'.repeat(12)).slice(0, 12));
  return sign === '-' ? -scaled : scaled;
}

function decimalString(value: bigint): string {
  const sign = value < 0n ? '-' : '';
  const absolute = value < 0n ? -value : value;
  const integer = absolute / DECIMAL_SCALE;
  const fraction = (absolute % DECIMAL_SCALE).toString().padStart(12, '0').replace(/0+$/, '');
  return `${sign}${integer}${fraction ? `.${fraction}` : ''}`;
}

export function subtractDecimal(left: string, right: string): string | null {
  const leftValue = scaledDecimal(left);
  const rightValue = scaledDecimal(right);
  return leftValue === null || rightValue === null ? null : decimalString(leftValue - rightValue);
}

export function compareDecimal(left: string, right: string): -1 | 0 | 1 | null {
  const leftValue = scaledDecimal(left);
  const rightValue = scaledDecimal(right);
  if (leftValue === null || rightValue === null) return null;
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

export function absoluteDecimal(value: string): string | null {
  const parsed = scaledDecimal(value);
  return parsed === null ? null : decimalString(parsed < 0n ? -parsed : parsed);
}

const statuses: Record<Language, Record<SemanticStatusKind, Record<string, string>>> = {
  ru: {
    limitStatus: { disabled: 'Отключён', normal: 'В норме', near: 'Почти достигнут', exceeded: 'Превышен' },
    paceStatus: { under: 'Медленнее обычного', normal: 'Обычный темп', over: 'Быстрее обычного',
      insufficient_history: 'Недостаточно истории' },
  },
  en: {
    limitStatus: { disabled: 'Disabled', normal: 'Within limit', near: 'Near limit', exceeded: 'Exceeded' },
    paceStatus: { under: 'Slower than usual', normal: 'Usual pace', over: 'Faster than usual',
      insufficient_history: 'Insufficient history' },
  },
};

const integerFormatter = (language: Language) => new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
  maximumFractionDigits: 0,
});

export function formatMoney(value: string | null, currency: string, language: Language): string {
  if (value === null || !/^-?\d{1,30}(?:\.\d{1,12})?$/.test(value)) return '—';
  const match = /^(-?)(\d+)(?:\.(\d{1,12}))?$/.exec(value);
  if (!match) return '—';
  const [, sign, integer, fraction = ''] = match;
  let parts: Intl.NumberFormatPart[];
  try {
    parts = new Intl.NumberFormat(language === 'ru' ? 'ru-RU' : 'en-US', {
      style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
    }).formatToParts(0);
  } catch {
    return '—';
  }
  const firstNumber = parts.findIndex((part) => part.type === 'integer');
  let lastNumber = -1;
  parts.forEach((part, index) => {
    if (part.type === 'fraction') lastNumber = index;
  });
  const decimal = parts.find((part) => part.type === 'decimal')?.value ?? (language === 'ru' ? ',' : '.');
  let whole = BigInt(integer);
  let minor = BigInt((fraction + '00').slice(0, 2));
  if (fraction.length > 2 && fraction[2] >= '5') minor += 1n;
  if (minor === 100n) {
    whole += 1n;
    minor = 0n;
  }
  const formattedInteger = integerFormatter(language).format(whole);
  const formattedFraction = minor.toString().padStart(2, '0');
  return `${sign}${parts.slice(0, firstNumber).map((part) => part.value).join('')}${formattedInteger}${decimal}${formattedFraction}` +
    parts.slice(lastNumber + 1).map((part) => part.value).join('');
}

export function formatSemanticStatus(kind: SemanticStatusKind, code: string, language: Language): string {
  return statuses[language][kind][code] ?? (language === 'ru' ? 'Неизвестный статус' : 'Unknown status');
}

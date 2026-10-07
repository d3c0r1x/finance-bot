import { describe, expect, it } from 'vitest';
import { absoluteDecimal, compareDecimal, formatMoney, formatSemanticStatus, subtractDecimal } from './formatting';

describe('shared presentation formatting', () => {
  it('formats the same decimal money value for Russian and English', () => {
    expect(formatMoney('1234.50', 'RUB', 'ru')).toBe('1 234,50 ₽');
    expect(formatMoney('-12.30', 'RUB', 'en')).toBe('-RUB 12.30');
    expect(formatMoney('9007199254740993.995', 'RUB', 'en')).toBe('RUB 9,007,199,254,740,994.00');
    expect(formatMoney('864.000000', 'RUB', 'ru')).toBe('864,00 ₽');
  });

  it('localizes known Core status codes and hides unknown codes', () => {
    expect(formatSemanticStatus('paceStatus', 'insufficient_history', 'ru')).toBe('Недостаточно истории');
    expect(formatSemanticStatus('limitStatus', 'near', 'en')).toBe('Near limit');
    expect(formatSemanticStatus('paceStatus', 'future_code', 'ru')).toBe('Неизвестный статус');
  });

  it('calculates displayed monetary differences without binary floating point', () => {
    expect(subtractDecimal('20000.00', '19924.50')).toBe('75.5');
    expect(subtractDecimal('1.00', '2.25')).toBe('-1.25');
    expect(compareDecimal('9007199254740993.00', '9007199254740992.99')).toBe(1);
    expect(absoluteDecimal('-0.10')).toBe('0.1');
  });
});

import { describe, it, expect } from 'vitest';
import { relTime, shortTime, maskKey, formatNum } from '../src/lib/format';

describe('lib/format', () => {
  it('relTime returns 刚刚 for < 1 min', () => {
    const iso = new Date(Date.now() - 30_000).toISOString();
    expect(relTime(iso)).toBe('刚刚');
  });

  it('relTime returns N 分钟前', () => {
    const iso = new Date(Date.now() - 5 * 60_000).toISOString();
    expect(relTime(iso)).toBe('5 分钟前');
  });

  it('relTime returns 昨天', () => {
    const iso = new Date(Date.now() - 26 * 3600_000).toISOString();
    expect(relTime(iso)).toBe('昨天');
  });

  it('shortTime returns HH:mm', () => {
    const iso = new Date(2026, 7, 17, 14, 23).toISOString();
    expect(shortTime(iso)).toBe('14:23');
  });

  it('maskKey keeps prefix and last 4 chars', () => {
    expect(maskKey('pk_live_abcdefgh1234', 'pk_live_')).toBe('pk_live_••••1234');
  });

  it('maskKey handles short input', () => {
    expect(maskKey('short', 'pk_')).toBe('short');
  });

  it('formatNum adds thousands separator', () => {
    expect(formatNum(12345)).toBe('12,345');
  });
});
/**
 * fuzzy.ts — 轻量模糊匹配（不依赖第三方）
 * 评分规则：
 *   - 完全相等 1000
 *   - 起始前缀 100
 *   - 字符按序匹配  base 给 10，每连续 +5
 *   - 子串包含     5
 *   - 不匹配       0
 * 返回 ≤ 0 表示不匹配。
 */
export function fuzzyScore(query: string, target: string): number {
  if (!query) return 1; // 空查询 = 全部命中
  const q = query.toLowerCase();
  const t = target.toLowerCase();
  if (t === q) return 1000;
  if (t.startsWith(q)) return 100;
  if (t.includes(q)) return 5;
  // 顺序匹配 query 的每个字符
  let qi = 0;
  let score = 0;
  let prevMatch = -1;
  while (qi < q.length && prevMatch < t.length - 1) {
    const idx = t.indexOf(q[qi], prevMatch + 1);
    if (idx < 0) return 0;
    score += 10;
    if (idx === prevMatch + 1) score += 5;
    prevMatch = idx;
    qi++;
  }
  return qi === q.length ? score : 0;
}

export interface Scored<T> {
  item: T;
  score: number;
}

export function rankBy<T>(items: T[], query: string, getKey: (it: T) => string): T[] {
  if (!query.trim()) return items;
  const scored: Scored<T>[] = [];
  for (const it of items) {
    const s = fuzzyScore(query, getKey(it));
    if (s > 0) scored.push({ item: it, score: s });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.map((s) => s.item);
}

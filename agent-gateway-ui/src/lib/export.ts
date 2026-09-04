/**
 * export.ts — 通用导出工具
 * 支持 CSV / JSON 两种格式，零依赖
 */

function escapeCsv(v: unknown): string {
  if (v == null) return '';
  let s = typeof v === 'object' ? JSON.stringify(v) : String(v);
  if (s.includes(',') || s.includes('"') || s.includes('\n')) {
    s = `"${s.replace(/"/g, '""')}"`;
  }
  return s;
}

function download(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: `${mime};charset=utf-8;` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export function exportCsv<T>(
  basename: string,
  columns: (keyof T)[],
  rows: T[],
): string {
  const head = columns.map((c) => escapeCsv(String(c))).join(',');
  const body = rows
    .map((r) => columns.map((c) => escapeCsv((r as any)[c])).join(','))
    .join('\n');
  // 加 BOM 让 Excel 识别 UTF-8
  const content = '﻿' + head + '\n' + body;
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const filename = `${basename}-${ts}.csv`;
  download(filename, content, 'text/csv');
  return filename;
}

export function exportJson<T>(basename: string, rows: T[]): string {
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const filename = `${basename}-${ts}.json`;
  download(filename, JSON.stringify(rows, null, 2), 'application/json');
  return filename;
}

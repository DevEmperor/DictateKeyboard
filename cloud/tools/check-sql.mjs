#!/usr/bin/env node
/**
 * Does every table the code queries still exist?
 *
 * SQL lives in strings, so `tsc` cannot see it and a table that is dropped takes its callers with
 * it silently. That is not hypothetical: `cache` was removed with the old billing endpoint it
 * existed for, and one line in the daily report kept selecting from it — a failure that would only
 * have shown itself at five in the morning, on the one cron run of the day that touches it.
 *
 * Names introduced by a `WITH x AS (…)` are common table expressions, not tables, and are collected
 * first so they do not read as missing.
 *
 * Run it with `npm run check:sql`.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const schema = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8');
const tables = new Set([
  ...schema.matchAll(/CREATE TABLE IF NOT EXISTS (\w+)/g)].map((m) => m[1]),
);
// SQLite's own, and the pragma functions the dashboard reads a schema with.
['sqlite_master', 'sqlite_sequence', 'pragma_table_info'].forEach((t) => tables.add(t));

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : p.endsWith('.ts') ? [p] : [];
  });
}

const missing = [];
for (const file of walk(new URL('../src', import.meta.url).pathname)) {
  const source = readFileSync(file, 'utf8');
  const ctes = new Set([...source.matchAll(/\b(?:WITH|,)\s+(\w+)\s+AS\s*\(/gi)].map((m) => m[1]));
  for (const m of source.matchAll(/\b(?:FROM|INTO|UPDATE|JOIN)\s+([a-z_][a-z_0-9]*)/g)) {
    const table = m[1];
    if (tables.has(table) || ctes.has(table)) continue;
    const line = source.slice(0, m.index).split('\n').length;
    missing.push({ file: file.replace(/.*\/src\//, 'src/'), line, table });
  }
}

if (missing.length) {
  for (const m of missing) console.error(`check-sql: ${m.file}:${m.line} queries "${m.table}", which the schema does not define`);
  process.exit(1);
}
console.log(`check-sql: ${tables.size - 3} tables, every reference resolves`);

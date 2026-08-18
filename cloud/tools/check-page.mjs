#!/usr/bin/env node
/**
 * Does the dashboard's own script hold together?
 *
 * The page is one big template literal, so nothing in the toolchain reads the JavaScript inside it:
 * `tsc` sees a string. That blind spot cost a working feature once — a second `function wireDetail`
 * was added for the network diagram, hoisted over the one the account dialog used, and clicking an
 * account silently did nothing, because the rejected promise had no catch. Neither the type checker
 * nor a deploy could have said so.
 *
 * This checks the two things that would have caught it without a browser:
 *  - the script parses at all;
 *  - no two functions at the same nesting level share a name, which in a plain script is not an
 *    overload but a silent overwrite.
 *
 * Run it with `npm run check:page`.
 */

import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/admin/page.ts', import.meta.url), 'utf8');

const open = source.indexOf('<script>');
const close = source.lastIndexOf('</script>');
if (open === -1 || close === -1 || close < open) {
  console.error('check-page: no <script> block found in page.ts');
  process.exit(1);
}
const script = source.slice(open + '<script>'.length, close);

// The page is a template literal, so `${...}` holes are server-side values. Replace them with a
// harmless literal: what is being checked is the shape of the script, not the data poured into it.
const code = script.replace(/\$\{[^}]*\}/g, 'null');

let failed = false;

try {
  new vm.Script(code, { filename: 'admin/page.ts <script>' });
} catch (err) {
  console.error('check-page: the dashboard script does not parse\n  ' + err.message);
  process.exit(1);
}

// Indentation is the nesting level here: the whole script lives in one IIFE, so its own functions
// are indented by two spaces and anything deeper is nested inside another function and free to
// reuse a name.
const byIndent = new Map();
const lines = code.split('\n');
lines.forEach((line, i) => {
  const m = /^(\s*)function (\w+)\s*\(/.exec(line);
  if (!m) return;
  const key = m[1].length + ':' + m[2];
  if (!byIndent.has(key)) byIndent.set(key, []);
  byIndent.get(key).push(i + 1);
});

for (const [key, hits] of byIndent) {
  if (hits.length < 2) continue;
  const name = key.split(':')[1];
  console.error(
    'check-page: function ' + name + ' is declared ' + hits.length + ' times at the same level ' +
    '(script lines ' + hits.join(', ') + '). The later one silently replaces the earlier one.',
  );
  failed = true;
}

if (failed) process.exit(1);
console.log('check-page: script parses, ' + byIndent.size + ' function declarations, no name collisions');

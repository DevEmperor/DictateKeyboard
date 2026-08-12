#!/usr/bin/env node
/**
 * Does the network diagram hold together?
 *
 * The layout is computed in TypeScript rather than in the browser precisely so this can exist: a
 * check that runs without a screen and says which box a line crosses, instead of a person noticing
 * six weeks later that a label sits on a heading. The previous diagram was hand-placed, and the
 * twenty-fourth node broke three older labels — silently, because nothing could tell.
 *
 * Run it with `npm run check:graph`, which compiles the two source files into a temp directory
 * first. No extra dependency: the TypeScript that is already here does the compiling.
 */

import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const out = mkdtempSync(join(tmpdir(), 'dictate-graph-'));
let layout;
try {
  try {
    execFileSync('npx', [
      'tsc', 'src/admin/graph.ts', 'src/admin/graph-layout.ts',
      '--outDir', out, '--rootDir', 'src', '--target', 'es2022',
      // CommonJS on purpose: the emitted imports carry no file extension, which Node's ESM loader
      // refuses and its require() resolves without complaint. /tmp has no package.json, so a .js
      // file there is CommonJS whatever this package says about itself.
      '--module', 'commonjs', '--moduleResolution', 'node', '--skipLibCheck',
    ], { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
  } catch (err) {
    console.error('Compiling failed:\n' + (err.stdout || '') + (err.stderr || ''));
    process.exit(1);
  }
  const require = createRequire(import.meta.url);
  layout = require(join(out, 'admin', 'graph-layout.js')).layoutGraph();
} finally {
  rmSync(out, { recursive: true, force: true });
}

const { zones, nodes, edges, extent } = layout;
const problems = [];
const fail = (msg) => problems.push(msg);

/* ---------------------------------------------------------------- Geometry */

const overlap = (a, b) =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

for (let i = 0; i < nodes.length; i++) {
  for (let j = i + 1; j < nodes.length; j++) {
    if (overlap(nodes[i], nodes[j])) fail(`Boxes overlap: ${nodes[i].id} / ${nodes[j].id}`);
  }
}

const zoneById = new Map(zones.map((z) => [z.id, z]));
for (const n of nodes) {
  const z = zoneById.get(n.zone);
  if (!z) { fail(`${n.id} names a zone that does not exist: ${n.zone}`); continue; }
  const inside = n.x >= z.x && n.y >= z.y && n.x + n.w <= z.x + z.w && n.y + n.h <= z.y + z.h;
  if (!inside) fail(`${n.id} does not sit entirely inside zone ${n.zone}`);
}
for (let i = 0; i < zones.length; i++) {
  for (let j = i + 1; j < zones.length; j++) {
    if (overlap(zones[i], zones[j])) fail(`Zones overlap: ${zones[i].id} / ${zones[j].id}`);
  }
}

/* ------------------------------------------------------------------ Routes */

/** Every straight leg of every route, as an axis-aligned box with a little width. */
const segments = [];
for (const e of edges) {
  for (let k = 1; k < e.pts.length; k++) {
    const a = e.pts[k - 1], b = e.pts[k];
    segments.push({
      edge: `${e.from}→${e.to}`,
      axis: a.x === b.x ? 'v' : 'h',
      x0: Math.min(a.x, b.x), x1: Math.max(a.x, b.x),
      y0: Math.min(a.y, b.y), y1: Math.max(a.y, b.y),
    });
  }
}

// A leg may touch the box it starts or ends at — that is the stub. Anything else is a line through
// a box, which is the failure this whole rebuild exists to make impossible.
const TOUCH = 2;
for (const s of segments) {
  const [from, to] = s.edge.split('→');
  for (const n of nodes) {
    if (n.id === from || n.id === to) continue;
    if (s.x0 < n.x + n.w - TOUCH && n.x + TOUCH < s.x1 &&
        s.y0 < n.y + n.h - TOUCH && n.y + TOUCH < s.y1) {
      fail(`Route ${s.edge} crosses box ${n.id}`);
    }
    // A leg with no extent on one axis still crosses if the other axis passes through the box.
    if (s.axis === 'v' && s.x0 > n.x + TOUCH && s.x0 < n.x + n.w - TOUCH &&
        s.y0 < n.y + n.h - TOUCH && n.y + TOUCH < s.y1) {
      fail(`Route ${s.edge} crosses box ${n.id} (vertical leg)`);
    }
    if (s.axis === 'h' && s.y0 > n.y + TOUCH && s.y0 < n.y + n.h - TOUCH &&
        s.x0 < n.x + n.w - TOUCH && n.x + TOUCH < s.x1) {
      fail(`Route ${s.edge} crosses box ${n.id} (horizontal leg)`);
    }
  }
}

const NEAR = 6, RUN = 40;
for (let i = 0; i < segments.length; i++) {
  for (let j = i + 1; j < segments.length; j++) {
    const a = segments[i], b = segments[j];
    if (a.edge === b.edge || a.axis !== b.axis) continue;
    if (a.axis === 'v') {
      if (Math.abs(a.x0 - b.x0) > NEAR) continue;
      if (Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0) > RUN) {
        fail(`Two routes coincide at x≈${Math.round(a.x0)}: ${a.edge} / ${b.edge}`);
      }
    } else {
      if (Math.abs(a.y0 - b.y0) > NEAR) continue;
      if (Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0) > RUN) {
        fail(`Two routes coincide at y≈${Math.round(a.y0)}: ${a.edge} / ${b.edge}`);
      }
    }
  }
}

/* ------------------------------------------------------------------ Labels */

/**
 * Would every label find a free place?
 *
 * The browser makes the real decision, because only it knows how wide a string renders. This runs
 * the same greedy choice against an estimate of that width, which is close enough to answer the
 * question that matters: does the route offer enough places, or is the diagram so tight that chips
 * have to be shoved off their lines? A generous estimate is used on purpose — if it fits here, it
 * fits there.
 */
const CHAR = 6.1, PAD = 12, LH = 17;
const PERP = [0, -19, 19, -38, 38, -57, 57, -76, 76];
const ALONG = [0, -24, 24, -48, 48];
const blockers = nodes.map((n) => ({ x: n.x, y: n.y, w: n.w, h: n.h }))
  .concat(zones.map((z) => ({ x: z.x + 10, y: z.y + 6, w: 260, h: 46 })));
const areaOver = (a, b) => {
  const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
  const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
  return w > 0 && h > 0 ? w * h : 0;
};

const placedLabels = [];
let onLine = 0, shifted = 0, dirty = 0;
// Fewest options first. Placed in list order, an edge with one possible place can arrive to find it
// taken by an edge that had five and picked this one; the other way round, everybody fits.
const order = edges.map((e, i) => ({ e, i })).sort((p, q) => p.e.spots.length - q.e.spots.length || p.i - q.i);
for (const { e } of order) {
  const w = e.label.length * CHAR + PAD;
  let best = null, bestCost = Infinity, bestK = 0, bestOff = 0;
  for (let k = 0; k < e.spots.length && bestCost > 0; k++) {
    const spot = e.spots[k];
    for (const [off, slide] of PERP.flatMap((o) => ALONG.map((a) => [o, a]))) {
      const box = spot.along === 'h'
        ? { x: spot.x - w / 2 + slide, y: spot.y - LH / 2 + off, w, h: LH }
        : { x: spot.x - w / 2 + off, y: spot.y - LH / 2 + slide, w, h: LH };
      let cost = k * 26 + Math.abs(off) * 1.4 + Math.abs(slide) * 0.5;
      for (const b of blockers) cost += areaOver(box, b) * 0.9;
      for (const b of placedLabels) cost += areaOver(box, b) * 1.6;
      if (cost < bestCost) { bestCost = cost; best = box; bestK = k; bestOff = off + slide; }
      if (cost === 0) break;
    }
  }
  placedLabels.push(best);
  const clean = blockers.every((b) => areaOver(best, b) === 0)
    && placedLabels.slice(0, -1).every((b) => areaOver(best, b) === 0);
  if (!clean) { dirty++; fail(`Label "${e.label}" covers something (${e.from}→${e.to})`); }
  else if (bestK === 0 && bestOff === 0) onLine++;
  else shifted++;
}

/* ----------------------------------------------------------------- Content */

const ids = new Set(nodes.map((n) => n.id));
const touched = new Set();
for (const e of edges) {
  if (!ids.has(e.from)) fail(`Edge names an unknown source: ${e.from}`);
  if (!ids.has(e.to)) fail(`Edge names an unknown target: ${e.to}`);
  touched.add(e.from); touched.add(e.to);
  if (!e.long || e.long.length < 120) fail(`Edge ${e.from}→${e.to} has no long text`);
  if (!e.spots.length) fail(`Edge ${e.from}→${e.to} has nowhere to put its label`);
  if (/<\/script/i.test(e.long)) fail(`Edge ${e.from}→${e.to}: </script inside the text`);
}
for (const n of nodes) {
  if (!touched.has(n.id)) fail(`Box ${n.id} is on no edge at all`);
  if (!n.long || n.long.length < 120) fail(`Box ${n.id} has no long text`);
  if (/<\/script/i.test(n.long)) fail(`Box ${n.id}: </script inside the text`);
}
for (const z of zones) {
  if (!z.long || z.long.length < 120) fail(`Zone ${z.id} has no long text`);
}

/* ------------------------------------------------------------------ Report */

const words = [...nodes, ...edges, ...zones]
  .map((x) => (x.long || '').replace(/<[^>]+>/g, ' ').split(/\s+/).filter(Boolean).length)
  .reduce((a, b) => a + b, 0);

if (problems.length) {
  console.error(`\n${problems.length} problem(s):\n`);
  for (const p of problems) console.error('  ✗ ' + p);
  console.error('');
  process.exit(1);
}

console.log(
  `✓ ${nodes.length} boxes, ${edges.length} routes, ${zones.length} zones · ` +
  `${onLine} labels centred, ${shifted} shifted, ${dirty} overlapping · ` +
  `${segments.length} legs, none through a box · ` +
  `area ${Math.round(extent.w)}×${Math.round(extent.h)} · ${words} words of explanation`,
);

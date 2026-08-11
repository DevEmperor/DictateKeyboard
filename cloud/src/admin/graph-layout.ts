import { COLUMNS, EDGES, NODES, ZONES, type GraphEdge, type GraphNode, type GraphZone } from './graph';

/**
 * Where every box and every line actually goes.
 *
 * The model in `graph.ts` says a node belongs in a column and a row; nothing in it knows a pixel.
 * This turns that into geometry — rectangles, routed paths, places a label may sit — and it does so
 * **on the server**, which is the whole point: a layout computed here can be checked by a script
 * (`tools/check-graph.mjs`) instead of only looked at. The browser receives finished coordinates.
 *
 * The rule that keeps it honest is spatial, not cosmetic. Boxes live in columns; every route runs
 * exclusively through the **streets** between them — the gutters between columns and the gaps
 * between rows. A line therefore cannot cross a box, because no street passes through one. That
 * replaces the previous arrangement, where each edge named its own sides and a hand-tuned offset,
 * and where a new node quietly pushed some older line through a box nobody was looking at.
 */

export interface Point { x: number; y: number }

/** A box, laid out. */
export interface PlacedNode extends GraphNode {
  x: number; y: number; w: number; h: number;
}

/** A zone rectangle, grown to hold its members. */
export interface PlacedZone extends GraphZone {
  x: number; y: number; w: number; h: number;
}

/** Somewhere a label may sit, best first. The browser picks — only it knows how wide the text is. */
export interface LabelSpot {
  x: number; y: number;
  /** 'h' if the segment runs horizontally, so a clash is dodged by moving up or down. */
  along: 'h' | 'v';
}

export interface RoutedEdge extends GraphEdge {
  /** SVG path data, ready to draw. */
  d: string;
  /** The same corners as numbers, for the checking script. */
  pts: Point[];
  spots: LabelSpot[];
}

export interface GraphLayout {
  zones: PlacedZone[];
  nodes: PlacedNode[];
  edges: RoutedEdge[];
  extent: { w: number; h: number };
}

/* ------------------------------------------------------------------ Raster */

const NODE_W = 240;
const NODE_H = 66;
/** Distance between the tops of two rows. What is left over after the box is the street. */
const ROW_PITCH = 132;
const ROW_GAP = ROW_PITCH - NODE_H;
/** Between two columns of the same zone, and between columns belonging to different ones. */
const GUTTER_IN = 96;
const GUTTER_OUT = 132;
/** Room outside the outermost columns: a street plus the zone border plus air. */
const MARGIN = 100;
/** Zone padding — more at the top, where the zone writes its name. */
const ZONE_PAD = 26;
const ZONE_HEAD = 54;
/** Distance between two routes sharing a street. */
const LANE = 16;
/** Every turn costs this much, in pixels of detour, so a straight line wins over a shorter zigzag. */
const TURN_COST = 300;

const at = <T>(list: T[], i: number): T => list[Math.max(0, Math.min(list.length - 1, i))] as T;

/* ----------------------------------------------------------------- Streets */

class Streets {
  constructor(readonly vs: number[], readonly hs: number[]) {}
  vx(i: number): number { return at(this.vs, i); }
  hy(i: number): number { return at(this.hs, i); }
  /** Index of a street by its exact coordinate, or −1 — how a leg learns which street it is on. */
  vIndex(x: number): number { return this.vs.indexOf(x); }
  hIndex(y: number): number { return this.hs.indexOf(y); }
}

/* ------------------------------------------------------------------- Ports */

type Side = 'l' | 'r' | 't' | 'b';

/**
 * Which side an edge leaves by, and where on that side.
 *
 * Sideways whenever the two boxes sit in different columns — that is what the eye reads as "this
 * one talks to that one". Only a move within a column goes over the top or under the bottom.
 *
 * Several edges on one side are spread along it rather than stacked on its midpoint. Without that,
 * two routes leave at exactly the same pixel and read as a single line for their first stretch —
 * precisely where a reader is trying to tell them apart.
 */
function sideFor(a: PlacedNode, b: PlacedNode): Side {
  if (a.col !== b.col) return b.col > a.col ? 'r' : 'l';
  return b.row > a.row ? 'b' : 't';
}

function portPoint(n: PlacedNode, side: Side, index: number, count: number): Point {
  const t = (index + 1) / (count + 1);
  if (side === 'l') return { x: n.x, y: Math.round(n.y + n.h * t) };
  if (side === 'r') return { x: n.x + n.w, y: Math.round(n.y + n.h * t) };
  if (side === 't') return { x: Math.round(n.x + n.w * t), y: n.y };
  return { x: Math.round(n.x + n.w * t), y: n.y + n.h };
}

/* ------------------------------------------------------------------ Search */

interface Step { v: number; h: number }

/**
 * Shortest way through the streets, counted in pixels plus a charge per turn.
 *
 * Dijkstra over the lattice of street crossings, with the direction of arrival part of the state —
 * without that the turn charge cannot be applied and the result is a staircase where an L would do.
 * The lattice is five by twelve; searching it costs nothing worth measuring.
 */
function search(st: Streets, from: Step, to: Step, entry: 'v' | 'h', exit: 'v' | 'h'): Step[] {
  const V = st.vs.length, H = st.hs.length;
  const key = (v: number, h: number, d: number) => (v * H + h) * 2 + d;
  const dist = new Map<number, number>();
  const prev = new Map<number, number>();
  let queue: { v: number; h: number; d: number; cost: number }[] = [];

  const startDir = entry === 'v' ? 0 : 1;
  dist.set(key(from.v, from.h, startDir), 0);
  queue.push({ v: from.v, h: from.h, d: startDir, cost: 0 });

  const goalDir = exit === 'v' ? 0 : 1;
  let best = Infinity, bestKey = -1;

  while (queue.length) {
    queue.sort((p, q) => p.cost - q.cost);
    const cur = queue.shift();
    if (!cur) break;
    const ck = key(cur.v, cur.h, cur.d);
    if (cur.cost > (dist.get(ck) ?? Infinity)) continue;
    if (cur.v === to.v && cur.h === to.h) {
      const total = cur.cost + (cur.d === goalDir ? 0 : TURN_COST);
      if (total < best) { best = total; bestKey = ck; }
      continue;
    }
    const moves = [
      { v: cur.v, h: cur.h - 1, d: 0, step: Math.abs(st.hy(cur.h) - st.hy(cur.h - 1)) },
      { v: cur.v, h: cur.h + 1, d: 0, step: Math.abs(st.hy(cur.h + 1) - st.hy(cur.h)) },
      { v: cur.v - 1, h: cur.h, d: 1, step: Math.abs(st.vx(cur.v) - st.vx(cur.v - 1)) },
      { v: cur.v + 1, h: cur.h, d: 1, step: Math.abs(st.vx(cur.v + 1) - st.vx(cur.v)) },
    ];
    for (const m of moves) {
      if (m.v < 0 || m.v >= V || m.h < 0 || m.h >= H) continue;
      const cost = cur.cost + m.step + (m.d === cur.d ? 0 : TURN_COST);
      const mk = key(m.v, m.h, m.d);
      if (cost < (dist.get(mk) ?? Infinity)) {
        dist.set(mk, cost);
        prev.set(mk, ck);
        queue.push({ v: m.v, h: m.h, d: m.d, cost });
      }
    }
    if (queue.length > 4000) queue = queue.slice(0, 2000);
  }

  if (bestKey < 0) return [from, to];
  const path: Step[] = [];
  let k: number | undefined = bestKey;
  while (k !== undefined) {
    const d = k % 2;
    const rest = (k - d) / 2;
    const h = rest % H;
    const v = (rest - h) / H;
    path.unshift({ v, h });
    k = prev.get(k);
  }
  return path;
}

/* ------------------------------------------------------------------- Lanes */

/**
 * Two routes down the same street get different lanes — but only while they are actually beside
 * each other.
 *
 * One lane per route per street would push the outermost ones into the boxes; a gutter is only so
 * wide. So lanes are handed out like seats in a row: an edge takes the lowest number no
 * *overlapping* neighbour is using. Routes that share a street at different heights share a lane
 * and never meet.
 */
interface Usage { street: string; lo: number; hi: number; edge: number; leg: number; lane: number }

function assignLanes(usages: Usage[], halfWidth: (street: string) => number): Map<string, number> {
  const byStreet = new Map<string, Usage[]>();
  for (const u of usages) {
    const list = byStreet.get(u.street) ?? [];
    list.push(u);
    byStreet.set(u.street, list);
  }
  const lanes = new Map<string, number>();
  byStreet.forEach((list) => {
    list.sort((p, q) => p.lo - q.lo || p.edge - q.edge);
    let top = 0;
    for (const u of list) {
      const taken = new Set<number>();
      for (const other of list) {
        if (other === u || other.lane < 0) continue;
        if (other.lo < u.hi && u.lo < other.hi) taken.add(other.lane);
      }
      let lane = 0;
      while (taken.has(lane)) lane++;
      u.lane = lane;
      if (lane > top) top = lane;
    }
    // Centred on the street rather than starting at it, so a lone route runs down the middle — and
    // narrowed until the outermost lane still fits. A gutter is only so wide; an offset that ignores
    // that puts the outermost line flush against a box, which looks exactly like a bug.
    const room = halfWidth(at(list, 0).street);
    const spacing = top > 0 ? Math.min(LANE, (2 * room) / top) : 0;
    for (const u of list) {
      lanes.set(u.street + '#' + u.edge + '#' + u.leg, Math.round((u.lane - top / 2) * spacing));
    }
  });
  return lanes;
}

/* ------------------------------------------------------------------ Labels */

function spotsAlong(pts: Point[]): LabelSpot[] {
  const legs: { a: Point; b: Point; len: number }[] = [];
  for (let i = 1; i < pts.length; i++) {
    const a = at(pts, i - 1), b = at(pts, i);
    const len = Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
    if (len > 24) legs.push({ a, b, len });
  }
  legs.sort((p, q) => q.len - p.len);
  const spots: LabelSpot[] = [];
  for (const leg of legs) {
    const along: 'h' | 'v' = Math.abs(leg.b.x - leg.a.x) > Math.abs(leg.b.y - leg.a.y) ? 'h' : 'v';
    // Longer legs offer more places to try. A route that crosses the whole picture has plenty of
    // room; the one label that could not be seated cleanly was on exactly such a route, and it had
    // three places to choose from.
    const fracs = leg.len > 300 ? [0.5, 0.32, 0.68, 0.18, 0.82]
      : leg.len > 160 ? [0.5, 0.3, 0.7]
      : [0.5];
    for (const f of fracs) {
      spots.push({
        x: Math.round(leg.a.x + (leg.b.x - leg.a.x) * f),
        y: Math.round(leg.a.y + (leg.b.y - leg.a.y) * f),
        along,
      });
    }
  }
  if (!spots.length) {
    const first = at(pts, 0);
    spots.push({ x: first.x, y: first.y, along: 'h' });
  }
  return spots;
}

/* ---------------------------------------------------------------- Assembly */

export function layoutGraph(): GraphLayout {
  /* Columns, rows, boxes. */
  const cols: { x: number; w: number }[] = [];
  let cursor = MARGIN;
  COLUMNS.forEach((col, i) => {
    if (i > 0) cursor += at(COLUMNS, i - 1).zone === col.zone ? GUTTER_IN : GUTTER_OUT;
    const w = col.w ?? NODE_W;
    cols.push({ x: cursor, w });
    cursor += w;
  });
  const rowY = (row: number) => MARGIN + row * ROW_PITCH;
  const maxRow = NODES.reduce((m, n) => Math.max(m, n.row), 0);

  const nodes: PlacedNode[] = NODES.map((n) => {
    const col = at(cols, n.col);
    return { ...n, x: col.x, y: rowY(n.row), w: col.w, h: NODE_H };
  });
  const byId = new Map(nodes.map((n) => [n.id, n]));

  /* Zones grow to hold their members — a zone can no longer be too small. */
  const zones: PlacedZone[] = ZONES.map((z) => {
    const members = nodes.filter((n) => n.zone === z.id);
    const x0 = Math.min(...members.map((n) => n.x)) - ZONE_PAD;
    const y0 = Math.min(...members.map((n) => n.y)) - ZONE_HEAD;
    const x1 = Math.max(...members.map((n) => n.x + n.w)) + ZONE_PAD;
    const y1 = Math.max(...members.map((n) => n.y + n.h)) + ZONE_PAD;
    return { ...z, x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  });

  /* Streets: one down each gutter and each outer margin, one across each row gap. */
  const vs: number[] = [Math.round(at(cols, 0).x - MARGIN / 2)];
  for (let i = 1; i < cols.length; i++) {
    const left = at(cols, i - 1);
    vs.push(Math.round((left.x + left.w + at(cols, i).x) / 2));
  }
  const lastCol = at(cols, cols.length - 1);
  vs.push(Math.round(lastCol.x + lastCol.w + MARGIN / 2));
  const hs: number[] = [Math.round(rowY(0) - ROW_GAP / 2)];
  for (let r = 0; r <= maxRow; r++) hs.push(Math.round(rowY(r) + NODE_H + ROW_GAP / 2));
  const st = new Streets(vs, hs);

  /* Ports: how many edges share a side decides where along it each one sits. */
  const sides = new Map<string, number[]>();
  const sideOf = (from: PlacedNode, to: PlacedNode) => sideFor(from, to);
  EDGES.forEach((e, i) => {
    const a = byId.get(e.from), b = byId.get(e.to);
    if (!a || !b) return;
    for (const k of [e.from + ':' + sideOf(a, b), e.to + ':' + sideOf(b, a)]) {
      const list = sides.get(k) ?? [];
      list.push(i);
      sides.set(k, list);
    }
  });
  const port = (n: PlacedNode, side: Side, edge: number): Point => {
    const list = sides.get(n.id + ':' + side) ?? [edge];
    return portPoint(n, side, Math.max(0, list.indexOf(edge)), list.length);
  };

  /* Pass one: corners through street centrelines, plus a note of which street each leg uses. */
  interface Draft { pts: Point[]; legs: { street: string; axis: 'v' | 'h' }[] }
  const drafts: Draft[] = [];
  const usages: Usage[] = [];

  const pushDraft = (edge: number, raw: Point[]): void => {
    // Straightened first, on purpose. The lattice hands back a corner at every crossing, so a
    // straight run down one street arrives as four separate legs — and four legs get four lane
    // decisions for what the eye sees as one line. Merging them first is what makes the lane
    // arithmetic describe the line that is actually drawn.
    const pts = simplify(raw);
    const legs: Draft['legs'] = [];
    for (let k = 1; k < pts.length; k++) {
      const u = at(pts, k - 1), v = at(pts, k);
      const vertical = Math.abs(v.x - u.x) < Math.abs(v.y - u.y);
      const idx = vertical ? st.vIndex(u.x) : st.hIndex(u.y);
      const street = idx >= 0 ? (vertical ? 'v' : 'h') + idx : '';
      legs.push({ street, axis: vertical ? 'v' : 'h' });
      if (street) {
        usages.push({
          street,
          lo: vertical ? Math.min(u.y, v.y) : Math.min(u.x, v.x),
          hi: vertical ? Math.max(u.y, v.y) : Math.max(u.x, v.x),
          edge, leg: k - 1, lane: -1,
        });
      }
    }
    drafts[edge] = { pts, legs };
  };

  /** Is the straight run between two boxes free of everything else? */
  const clearBetween = (a: PlacedNode, b: PlacedNode): boolean => {
    if (a.col === b.col) {
      const lo = Math.min(a.row, b.row), hi = Math.max(a.row, b.row);
      return !nodes.some((n) => n.col === a.col && n.row > lo && n.row < hi);
    }
    const lo = Math.min(a.col, b.col), hi = Math.max(a.col, b.col);
    return !nodes.some((n) => n.row === a.row && n.col > lo && n.col < hi);
  };

  EDGES.forEach((e, i) => {
    const a = byId.get(e.from), b = byId.get(e.to);
    if (!a || !b) { drafts[i] = { pts: [], legs: [] }; return; }
    const sa = sideFor(a, b), sb = sideFor(b, a);
    const p0 = port(a, sa, i);
    const p1 = port(b, sb, i);

    // Two boxes with nothing between them get the obvious line rather than a tour of the lattice:
    // out, across the one street that separates them, in. The turn sits next to the *target*, so
    // the long stretch runs at the source's own height, where the eye expects it.
    if (clearBetween(a, b)) {
      if (a.col === b.col) {
        const y = st.hy(b.row > a.row ? b.row : b.row + 1);
        pushDraft(i, [p0, { x: p0.x, y }, { x: p1.x, y }, p1]);
      } else {
        const x = st.vx(b.col > a.col ? b.col : b.col + 1);
        pushDraft(i, [p0, { x, y: p0.y }, { x, y: p1.y }, p1]);
      }
      return;
    }

    // Otherwise: a stub straight into the neighbouring street, then streets all the way.
    const horizontal = (s: Side) => s === 'l' || s === 'r';
    const from: Step = horizontal(sa)
      ? { v: sa === 'r' ? a.col + 1 : a.col, h: nearest(hs, p0.y) }
      : { v: nearest(vs, p0.x), h: sa === 'b' ? a.row + 1 : a.row };
    const to: Step = horizontal(sb)
      ? { v: sb === 'r' ? b.col + 1 : b.col, h: nearest(hs, p1.y) }
      : { v: nearest(vs, p1.x), h: sb === 'b' ? b.row + 1 : b.row };
    const entry: 'v' | 'h' = horizontal(sa) ? 'v' : 'h';
    const exit: 'v' | 'h' = horizontal(sb) ? 'v' : 'h';
    const path = search(st, from, to, entry, exit);
    const head = at(path, 0), tail = at(path, path.length - 1);

    const pts: Point[] = [p0];
    pts.push(entry === 'v' ? { x: st.vx(head.v), y: p0.y } : { x: p0.x, y: st.hy(head.h) });
    for (const step of path) pts.push({ x: st.vx(step.v), y: st.hy(step.h) });
    pts.push(exit === 'v' ? { x: st.vx(tail.v), y: p1.y } : { x: p1.x, y: st.hy(tail.h) });
    pts.push(p1);
    pushDraft(i, pts);
  });

  /* Pass two: lanes, then rebuild the corners from the shifted street lines. */
  const roomFor = (street: string): number => {
    const idx = Number(street.slice(1));
    if (street.startsWith('h')) return ROW_GAP / 2 - 9;
    if (idx <= 0) return at(cols, 0).x - st.vx(0) - 9;
    if (idx >= cols.length) return st.vx(idx) - (lastCol.x + lastCol.w) - 9;
    const left = at(cols, idx - 1);
    return Math.min(st.vx(idx) - (left.x + left.w), at(cols, idx).x - st.vx(idx)) - 9;
  };
  const lanes = assignLanes(usages, roomFor);

  const edges: RoutedEdge[] = EDGES.map((e, i) => {
    const draft = drafts[i] ?? { pts: [], legs: [] };
    const offsets = draft.legs.map((leg, li) =>
      leg.street ? (lanes.get(leg.street + '#' + i + '#' + li) ?? 0) : 0);
    // A corner belongs to two legs — the one arriving and the one leaving. Each moves the coordinate
    // it owns: the vertical leg the x, the horizontal leg the y. Keyed per leg rather than per
    // street, because a route may well come back down a street it has already used.
    const moved = draft.pts.map((p, k) => {
      let x = p.x, y = p.y;
      for (const li of [k - 1, k]) {
        const leg = draft.legs[li];
        if (!leg || !leg.street) continue;
        if (leg.axis === 'v') x = p.x + (offsets[li] ?? 0); else y = p.y + (offsets[li] ?? 0);
      }
      return { x, y };
    });
    const pts = simplify(squareUp(moved));
    return {
      ...e,
      pts,
      d: 'M' + pts.map((p) => Math.round(p.x) + ' ' + Math.round(p.y)).join(' L'),
      spots: spotsAlong(pts),
    };
  });

  const extent = {
    w: Math.max(...zones.map((z) => z.x + z.w)) + MARGIN / 2,
    h: Math.max(...zones.map((z) => z.y + z.h)) + MARGIN / 2,
  };

  return { zones, nodes, edges, extent };
}

function nearest(list: number[], value: number): number {
  let best = 0;
  for (let i = 1; i < list.length; i++) {
    if (Math.abs(at(list, i) - value) < Math.abs(at(list, best) - value)) best = i;
  }
  return best;
}

/**
 * Lane offsets move lines sideways, which can leave a corner a few pixels out of square. This walks
 * the corners and forces every leg back onto an axis — a diagonal in an orthogonal diagram reads as
 * a mistake even when it is two pixels long.
 */
function squareUp(pts: Point[]): Point[] {
  const out = pts.map((p) => ({ ...p }));
  for (let i = 1; i < out.length; i++) {
    const a = at(out, i - 1), b = at(out, i);
    if (a.x === b.x || a.y === b.y) continue;
    if (Math.abs(b.x - a.x) <= Math.abs(b.y - a.y)) b.x = a.x; else b.y = a.y;
  }
  return out;
}

/** Drop repeated points and corners that sit on a straight line between their neighbours. */
function simplify(pts: Point[]): Point[] {
  const out: Point[] = [];
  for (const p of pts) {
    const last = out[out.length - 1];
    if (last && Math.abs(last.x - p.x) < 0.5 && Math.abs(last.y - p.y) < 0.5) continue;
    out.push(p);
  }
  const flat: Point[] = [];
  for (let i = 0; i < out.length; i++) {
    const a = flat[flat.length - 1], b = at(out, i), c = out[i + 1];
    if (a && c && ((a.x === b.x && b.x === c.x) || (a.y === b.y && b.y === c.y))) continue;
    flat.push(b);
  }
  return flat;
}

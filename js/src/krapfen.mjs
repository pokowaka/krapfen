// Krapfen's JavaScript side: Strudel's pattern engine running in QuickJS.
//
// Exposes `globalThis.krapfen` to the native host:
//   await krapfen.evaluate(code)  -> { cps }        (throws on error)
//   krapfen.query(begin, end)     -> [{ begin, end, duration, value }]
//   krapfen.tick()                -> runs due timers (embedded host only)
//
// Times are in cycles; the host converts to seconds using `cps`.

import { runTimers } from './shims.mjs';
import * as core from '@strudel/core';
import * as mini from '@strudel/mini';
import { transpiler } from '@strudel/transpiler';

const { repl, evalScope, Pattern, noteToMidi } = core;

// Visualisation helpers live in @strudel/draw (browser canvas). Make them
// harmless no-ops so code written for strudel.cc still evaluates.
for (const name of [
  'pianoroll',
  'punchcard',
  'scope',
  'tscope',
  'fscope',
  'spiral',
  'pitchwheel',
  'spectrum',
  'wordfall',
  'draw',
  'onPaint',
]) {
  for (const n of [name, `_${name}`]) {
    if (typeof Pattern.prototype[n] !== 'function') {
      Pattern.prototype[n] = function () {
        return this;
      };
    }
  }
}

// Double-quoted strings anywhere become mini-notation patterns, as on
// strudel.cc.
mini.miniAllStrings();

const strudel = repl({
  transpiler,
  getTime: () => performance.now() / 1000,
  // Scheduling is driven by the native host querying patterns directly;
  // the repl's own scheduler is never started.
  onSchedule: () => {},
  setInterval: (...a) => setInterval(...a),
  clearInterval: (id) => clearInterval(id),
});

const ready = evalScope(core, mini);

function plainValue(value) {
  if (value === null || typeof value !== 'object') {
    // Bare values (e.g. from `"c e g"` without a control) are treated as notes.
    value = { note: value };
  }
  const out = {};
  for (const [key, v] of Object.entries(value)) {
    // Fractions and other objects -> numbers/strings.
    out[key] = typeof v === 'object' && v !== null && typeof v.valueOf === 'function' ? v.valueOf() : v;
  }
  if (typeof out.note === 'string') {
    out.note = noteToMidi(out.note);
  }
  return out;
}

globalThis.krapfen = {
  async evaluate(code) {
    await ready;
    await strudel.evaluate(code, false);
    if (strudel.state.evalError) {
      throw strudel.state.evalError;
    }
    return { cps: strudel.scheduler.cps };
  },

  query(begin, end) {
    const pattern = strudel.state.pattern;
    if (!pattern) return [];
    return pattern
      .queryArc(begin, end)
      .filter((hap) => hap.hasOnset())
      .map((hap) => ({
        begin: hap.whole.begin.valueOf(),
        end: hap.whole.end.valueOf(),
        duration: hap.duration.valueOf(),
        value: plainValue(hap.value),
      }));
  },

  get cps() {
    return strudel.scheduler.cps;
  },

  tick: runTimers,
};

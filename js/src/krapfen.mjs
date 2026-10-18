// Krapfen's JavaScript side: Strudel's pattern engine running in QuickJS.
//
// Exposes `globalThis.krapfen` to the native host:
//   await krapfen.evaluate(code)  -> { cps }   evaluate without playing
//   await krapfen.play(code)      -> { cps }   evaluate and (keep) playing
//   krapfen.stop()                            stop scheduling, hush dough
//   krapfen.status()              -> { started, cycle, cps, time }
//   krapfen.query(begin, end)     -> [{ begin, end, duration, value }]
//   krapfen.tick()                            run due timers (host heartbeat)
//
// Playback uses Strudel's own scheduler (Cyclist) on dough's audio clock:
// every scheduled hap becomes a timestamped dough message, which dough
// starts sample-accurately. Errors are thrown as exceptions.

import { runTimers } from './shims.mjs';
import * as core from '@strudel/core';
import * as mini from '@strudel/mini';
import { transpiler } from '@strudel/transpiler';

const { repl, evalScope, Pattern, noteToMidi } = core;

const hostSend = typeof globalThis.__krapfenSend === 'function' ? globalThis.__krapfenSend : () => true;
const audioTime =
  typeof globalThis.__krapfenAudioTime === 'function' ? globalThis.__krapfenAudioTime : () => performance.now() / 1000;

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

// --- hap -> dough message ---

// Strudel controls dough doesn't understand under the same name.
const renamed = {
  sound: 's',
};

// superdough's `roomsize` is roughly the reverb tail length in seconds
// (0..10); dough's Dattorro reverb takes a 0..1 decay instead. This mapping
// is an approximation chosen by ear, not a port of superdough's reverb.
const roomsizeToDecay = (size) => Math.min(0.97, Math.max(0.2, 0.5 + 0.045 * size));

// Keys that are bookkeeping, not sound parameters.
const skipped = new Set(['id', 'cps', 'cycle', 'delta', 'roomsize', 'size', 'sz']);

function doughMessage(value, time, duration) {
  const parts = ['dough', 'play', 'time', time.toFixed(6), 'duration', duration.toFixed(6)];
  for (let [key, v] of Object.entries(value)) {
    if (skipped.has(key)) continue;
    key = renamed[key] ?? key;
    if (typeof v === 'number') {
      if (!Number.isFinite(v)) continue;
      parts.push(key, String(Math.round(v * 1e6) / 1e6));
    } else if (typeof v === 'string' && /^[A-Za-z0-9_.:-]+$/.test(v)) {
      // dough messages are '/'-separated, so only simple strings are safe.
      parts.push(key, v);
    }
  }
  const size = value.roomsize ?? value.size ?? value.sz;
  if (typeof size === 'number' && value.roomdecay === undefined) {
    parts.push('roomdecay', roomsizeToDecay(size).toFixed(3));
  }
  return parts.join('/');
}

let scheduled = 0;
let dropped = 0;
// Smallest margin (seconds) between an event's start time and the audio
// clock when it was sent. dough silently drops events that arrive >10 ms
// late, so this must stay comfortably positive.
let minLead = Infinity;
let late = 0;

function onSchedule(hap, time, cps) {
  const value = plainValue(hap.value);
  if (!value.s && value.note === undefined && value.freq === undefined) return; // nothing to play
  value.s ??= 'triangle';
  const message = doughMessage(value, time, hap.duration.valueOf() / cps);
  const lead = time - audioTime();
  minLead = Math.min(minLead, lead);
  if (lead < 0) late++;
  if (hostSend(message)) scheduled++;
  else dropped++;
}

// --- repl ---

const strudel = repl({
  transpiler,
  getTime: audioTime,
  onSchedule,
  setInterval: (...a) => setInterval(...a),
  clearInterval: (id) => clearInterval(id),
});

const ready = evalScope(core, mini);

async function evaluate(code, autostart) {
  await ready;
  await strudel.evaluate(code, autostart);
  if (strudel.state.evalError) {
    throw strudel.state.evalError;
  }
  return { cps: strudel.scheduler.cps };
}

globalThis.krapfen = {
  evaluate: (code) => evaluate(code, false),

  play: (code) => evaluate(code, true),

  stop() {
    strudel.stop();
    hostSend('dough/hush');
    return true;
  },

  status() {
    const s = strudel.scheduler;
    return {
      started: s.started,
      cycle: s.now(),
      cps: s.cps,
      time: audioTime(),
      scheduled,
      dropped,
      late,
      minLead: Number.isFinite(minLead) ? minLead : null,
    };
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

  // Exposed for tests.
  doughMessage,

  tick: runTimers,
};

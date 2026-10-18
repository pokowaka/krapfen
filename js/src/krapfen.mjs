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
//
// Strudel controls are passed to dough by name. Where dough's defaults or
// behaviour differ from superdough (strudel.cc's engine), values are
// adjusted here so patterns sound the same. Measurements referenced below
// come from tools/dough-bench (dough-synth 0.4.2).

// Strudel controls dough doesn't understand under the same name.
const renamed = {
  sound: 's',
};

// Keys that are bookkeeping or handled specially, not passed through.
const skipped = new Set(['id', 'cps', 'cycle', 'delta', 'roomsize', 'size', 'sz']);

// superdough's basic synth waveforms (synth.mjs).
const synthWaveforms = new Set(['triangle', 'square', 'sawtooth', 'sine', 'saw', 'tri']);

// superdough's envelope rules (helpers.mjs getADSRValues): without any ADSR
// controls, synths use [0.001, 0.05, 0.6, 0.01]; otherwise a missing sustain
// is 1 if only attack is given (or nothing), else 0.001. dough defaults to
// sustain 1, so synth envelopes are always sent explicitly.
function superdoughADSR(v) {
  const { attack: a, decay: d, sustain: s, release: r } = v;
  if (a == null && d == null && s == null && r == null) return [0.001, 0.05, 0.6, 0.01];
  const sustain = s != null ? s : (a != null && d == null) || (a == null && d == null) ? 1 : 0.001;
  return [Math.max(a ?? 0, 0.001), Math.max(d ?? 0, 0.001), Math.min(sustain, 1), Math.max(r ?? 0, 0.01)];
}

// superdough's `roomsize` is the RT60 (seconds) of the reverb impulse
// response it generates (reverbGen.mjs; default 2 s). dough's Dattorro reverb
// takes a decay coefficient instead. Measured RT60 of dough per decay
// (tools/dough-bench rt60); we interpolate in log(RT60) to invert it.
const DOUGH_RT60 = [
  [0.1, 0.408], [0.2, 0.522], [0.3, 0.701], [0.4, 0.904], [0.5, 1.152],
  [0.6, 1.542], [0.7, 2.179], [0.75, 2.642], [0.8, 3.403], [0.85, 4.534],
  [0.9, 6.94], [0.92, 8.4], [0.94, 10.734], [0.96, 17.12], [0.97, 22.171],
  [0.98, 32.266], [0.99, 61.029],
];

export function decayForRT60(seconds) {
  const t = Math.log(Math.max(seconds, 1e-3));
  const first = DOUGH_RT60[0];
  const last = DOUGH_RT60[DOUGH_RT60.length - 1];
  if (t <= Math.log(first[1])) return first[0];
  if (t >= Math.log(last[1])) return last[0];
  for (let i = 1; i < DOUGH_RT60.length; i++) {
    const [d0, r0] = DOUGH_RT60[i - 1];
    const [d1, r1] = DOUGH_RT60[i];
    const l0 = Math.log(r0);
    const l1 = Math.log(r1);
    if (t <= l1) return d0 + ((t - l0) / (l1 - l0)) * (d1 - d0);
  }
  return last[0];
}

// Applies superdough's defaults/semantics on top of a plain hap value.
export function toDoughParams(value) {
  const v = { ...value };
  v.s ??= 'triangle'; // superdough's default sound
  v.gain ??= 0.8; // superdough's default gain (dough's is 1)
  if (synthWaveforms.has(v.s)) {
    [v.attack, v.decay, v.sustain, v.release] = superdoughADSR(v);
  }
  // Both engines use the same equal-power pan law, except that dough skips
  // panning entirely at exactly 0.5 (both channels at full level), whereas
  // superdough attenuates the centre by 3 dB. Nudge off the special case.
  if (v.pan === 0.5) v.pan = 0.500001;
  if (typeof v.room === 'number' && v.room > 0 && v.roomdecay === undefined) {
    v.roomdecay = decayForRT60(v.roomsize ?? v.size ?? v.sz ?? 2);
  }
  return v;
}

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
  const message = doughMessage(toDoughParams(value), time, hap.duration.valueOf() / cps);
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
  doughMessage: (value, time = 0, duration = 1) => doughMessage(toDoughParams(value), time, duration),
  decayForRT60,

  tick: runTimers,
};

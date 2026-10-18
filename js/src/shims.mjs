// Minimal browser-ish environment for running Strudel inside QuickJS.
//
// Strudel was written for the browser. The pattern engine itself is pure
// JS, but a few modules touch `window`/`document` (keyboard + mouse signals,
// logger events) and the scheduler expects timers. Everything here is a
// no-op or a tiny polyfill; the native host (krapfen_js.c) provides
// `__krapfenNow()` (monotonic seconds) and `__krapfenLog(level, message)`
// and pumps timers by calling `krapfen.tick()`.

const g = globalThis;

g.window ??= g;
g.self ??= g;

if (!g.document) {
  g.document = {
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent() {
      return true;
    },
    body: { clientWidth: 1, clientHeight: 1 },
  };
}
g.addEventListener ??= () => {};
g.removeEventListener ??= () => {};
g.dispatchEvent ??= () => true;

g.CustomEvent ??= class CustomEvent {
  constructor(type, init = {}) {
    this.type = type;
    this.detail = init.detail;
  }
};

// --- logging ---

const hostLog = typeof g.__krapfenLog === 'function' ? g.__krapfenLog : null;
const format = (args) =>
  args
    .map((a) => {
      if (a instanceof Error) return `${a.message}\n${a.stack ?? ''}`;
      if (typeof a === 'object') {
        try {
          return JSON.stringify(a);
        } catch {
          return String(a);
        }
      }
      return String(a);
    })
    .join(' ');

if (hostLog) {
  g.console = {
    log: (...a) => hostLog(1, format(a)),
    info: (...a) => hostLog(1, format(a)),
    debug: (...a) => hostLog(0, format(a)),
    warn: (...a) => hostLog(2, format(a)),
    error: (...a) => hostLog(3, format(a)),
  };
} else {
  // Keep an existing console (e.g. the qjs shell's) but fill in methods it
  // may lack.
  const c = (g.console ??= {});
  const log = typeof c.log === 'function' ? c.log.bind(c) : () => {};
  for (const m of ['log', 'info', 'debug', 'warn', 'error']) {
    if (typeof c[m] !== 'function') c[m] = log;
  }
}

// --- clock ---

const now = typeof g.__krapfenNow === 'function' ? g.__krapfenNow : () => Date.now() / 1000;
g.performance ??= { now: () => now() * 1000 };

// --- timers (pumped by the host via krapfen.tick()) ---
//
// Only installed when the host has no timers of its own (the embedded
// engine on Android). QuickJS's `qjs` shell already provides them, which is
// handy for running the smoke test on a desktop.

export const timers = new Map();
let nextTimerId = 1;

export function runTimers() {
  const t = now() * 1000;
  for (const [id, timer] of [...timers]) {
    if (!timers.has(id) || timer.due > t) continue;
    if (timer.interval) {
      timer.due += timer.delay;
      if (timer.due < t) timer.due = t + timer.delay; // don't spiral after a stall
    } else {
      timers.delete(id);
    }
    try {
      timer.fn(...timer.args);
    } catch (err) {
      console.error(err);
    }
  }
}

if (typeof g.setTimeout !== 'function') {
  const add = (fn, delay, args, interval) => {
    const id = nextTimerId++;
    delay = Math.max(0, Number(delay) || 0);
    timers.set(id, { fn, delay, args, interval, due: now() * 1000 + delay });
    return id;
  };
  g.setTimeout = (fn, delay, ...args) => add(fn, delay, args, false);
  g.setInterval = (fn, delay, ...args) => add(fn, delay, args, true);
  g.clearTimeout = g.clearInterval = (id) => timers.delete(id);
}

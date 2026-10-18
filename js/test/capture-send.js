// Loaded before the bundle in desktop tests: records dough messages instead
// of sending them to an audio thread.
globalThis.__sent = [];
globalThis.__krapfenSend = (m) => (globalThis.__sent.push(m), true);

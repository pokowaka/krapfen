// Loaded before the bundle in desktop tests: a fake Kotlin host that
// records dough messages and answers sample requests from fixtures.
globalThis.__sent = [];
globalThis.__krapfenSend = (m) => (globalThis.__sent.push(m), true);
globalThis.__requests = [];
globalThis.__krapfenRequest = (op, id, arg) => globalThis.__requests.push({ op, id, arg });
// Answers queued requests: fetchText from __fixtures, loadSample with 0.5 s.
globalThis.__answerRequests = () => {
  for (const { op, id, arg } of globalThis.__requests.splice(0)) {
    if (op === 'fetchText') {
      const text = globalThis.__fixtures?.[arg];
      krapfen._hostDone(id, text !== undefined, text ?? `404 ${arg}`);
    } else if (op === 'loadSample') {
      globalThis.__loadedUrls = [...(globalThis.__loadedUrls ?? []), JSON.parse(arg).url];
      krapfen._hostDone(id, true, 0.5);
    }
  }
};

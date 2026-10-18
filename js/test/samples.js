// Sample maps, selection (n, bank, note), loading and dough messages.
//   qjs --std -I js/test/fake-host.js -I app/src/main/assets/krapfen/strudel.js js/test/samples.js
globalThis.__fixtures = {
  'https://raw.githubusercontent.com/someone/drums/main/strudel.json': JSON.stringify({
    _base: 'https://cdn.example/drums/',
    bd: ['bd/0.wav', 'bd/1.wav'],
    tr909_bd: 'tr909/bd.wav',
  }),
};
// Like the native host: tick, then let promise jobs run.
const pump = async (ms) => {
  const end = performance.now() + ms;
  while (performance.now() < end) {
    krapfen.tick();
    __answerRequests();
    for (let i = 0; i < 10; i++) await null;
    os.sleep(5);
  }
};
(async () => {
  // github: shorthand -> strudel.json, plus an inline map with note keys.
  // Answer host requests while a promise is pending (the real host does
  // this asynchronously from its IO threads).
  const settle = async (promise) => {
    let done = false;
    promise.then(() => (done = true), () => (done = true));
    for (let i = 0; i < 10000 && !done; i++) {
      __answerRequests();
      await null;
    }
    return promise;
  };
  await settle(krapfen.evaluate(`samples('github:someone/drums')`));
  await krapfen.evaluate(`samples({ piano: { c3: 'p/c3.mp3', c5: 'p/c5.mp3' } }, 'https://cdn.example/')`);
  await krapfen.play(`setcpm(60)
$: s("bd*2").n("<0 1>")
$: s("bd").bank("tr909").speed(-1)
$: note("c3 e3 b4").s("piano").clip(.5)
$: s("nope")`);
  await pump(2300);
  krapfen.stop();
  console.log('loaded:', JSON.stringify(__loadedUrls));
  console.log('status:', JSON.stringify(krapfen.status()));
  for (const m of __sent) console.log(m);
})().catch((e) => console.log('FAILED', e.message, e.stack));

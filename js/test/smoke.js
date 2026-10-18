// Smoke test for the bundle, runnable with the desktop `qjs` shell:
//   qjs --std -I app/src/main/assets/krapfen/strudel.js js/test/smoke.js
// (or via `npm test` with QJS pointing at a qjs binary).

const show = (label, events) => {
  console.log(`\n# ${label}`);
  for (const e of events) {
    console.log(`  ${e.begin.toFixed(3)} +${e.duration.toFixed(3)}  ${JSON.stringify(e.value)}`);
  }
};

const run = async () => {
  const t0 = performance.now();
  let r = await krapfen.evaluate('note("c e g").s("sine")');
  console.log(`evaluate #1: ${(performance.now() - t0).toFixed(1)} ms, cps=${r.cps}`);
  show('note("c e g").s("sine") — cycle 0..1', krapfen.query(0, 1));

  r = await krapfen.evaluate(`
    setcpm(10)
    $: note("<a2 g2>").s("sine").attack(.5)
    $: note("[c4 e4]*2").s("triangle").gain(.2).jux(x => x.add(note(.1)))
  `);
  console.log(`\ncps after setcpm(10): ${r.cps}`);
  show('$: + setcpm + jux — cycle 0..1', krapfen.query(0, 1));

  // Rough timing of the hot path: one cycle of a busy pattern, 100 times.
  await krapfen.evaluate('note("c3 e3 g3 b3 c4 e4 g4 b4".add("<0 3 5>")).s("saw").fast(4).lpf(sine.range(200, 2000))');
  const t1 = performance.now();
  let count = 0;
  for (let i = 0; i < 100; i++) count += krapfen.query(i, i + 1).length;
  const ms = performance.now() - t1;
  console.log(`\nquery 100 cycles: ${count} events in ${ms.toFixed(1)} ms (${(ms / 100).toFixed(2)} ms/cycle)`);

  try {
    await krapfen.evaluate('note("c e g").thisDoesNotExist()');
    console.log('ERROR: expected an evaluation error');
  } catch (err) {
    console.log(`\nerror path OK: ${err.message}`);
  }
};

run().catch((err) => console.log('FAILED:', err.message, err.stack));

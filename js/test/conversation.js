// Evaluates "A Conversation in Four Minutes" and summarises all 40 cycles.
//   qjs --std -I app/src/main/assets/krapfen/strudel.js js/test/conversation.js
const code = std.loadFile('app/src/main/assets/krapfen/examples/conversation.strudel');
(async () => {
  const t0 = performance.now();
  const { cps } = await krapfen.evaluate(code);
  console.log(`evaluate: ${(performance.now() - t0).toFixed(1)} ms, cps=${cps} -> ${(40 / cps / 60).toFixed(2)} min for 40 cycles`);
  const t1 = performance.now();
  const parts = ['I. Between', 'II. Witness', 'III. Open', 'IV. Beside'];
  const sounds = {};
  let total = 0;
  for (let p = 0; p < 4; p++) {
    let n = 0;
    for (let c = p * 10; c < p * 10 + 10; c++) {
      for (const e of krapfen.query(c, c + 1)) {
        n++;
        sounds[e.value.s] = (sounds[e.value.s] ?? 0) + 1;
      }
    }
    total += n;
    console.log(`  ${parts[p].padEnd(12)} ${n} events`);
  }
  console.log(`query 40 cycles: ${total} events in ${(performance.now() - t1).toFixed(1)} ms; by sound: ${JSON.stringify(sounds)}`);
  console.log('first events of part III:');
  for (const e of krapfen.query(21, 22)) console.log(`  ${e.begin.toFixed(4)} +${e.duration.toFixed(3)} ${JSON.stringify(e.value)}`);
})().catch((err) => console.log('FAILED:', err.message, err.stack));

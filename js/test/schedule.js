// Plays a pattern for ~1.2 s with Strudel's scheduler and prints the dough
// messages it produced.
//   qjs --std -I js/test/capture-send.js -I krapfen/src/main/assets/krapfen/strudel.js js/test/schedule.js
(async () => {
  await krapfen.play('setcpm(60)\n$: note("c4 e4 g4").s("sine").room(.5).roomsize(8).lpf(800)');
  // Pump the scheduler like the native host does (every 5 ms for 1.2 s).
  const end = performance.now() + 1200;
  while (performance.now() < end) {
    krapfen.tick();
    os.sleep(5);
  }
  {
    krapfen.stop();
    const st = krapfen.status();
    console.log(`scheduled=${st.scheduled} dropped=${st.dropped} started=${st.started}`);
    let prev = null;
    for (const m of __sent) {
      const t = Number((m.match(/\/time\/([0-9.]+)/) || [])[1]);
      console.log(`${prev === null ? '      ' : ('+' + (t - prev).toFixed(3)).padStart(6)}  ${m}`);
      if (!isNaN(t)) prev = t;
    }
  }
})().catch((e) => console.log('FAILED', e.message, e.stack));

// Prints dough messages for representative Strudel values and the
// roomsize -> decay mapping.
//   qjs --std -I krapfen/src/main/assets/krapfen/strudel.js js/test/params.js
const cases = [
  { s: 'sine', note: 57 },
  { s: 'sine', note: 57, attack: 0.5 },
  { s: 'sine', note: 57, attack: 0.5, decay: 3, sustain: 0, room: 0.7, roomsize: 8, gain: 0.35 },
  { s: 'triangle', note: 76, attack: 0.02, decay: 1.5, sustain: 0, pan: 0.5 },
  { s: 'sawtooth', note: 55, attack: 2, release: 4, cutoff: 700, room: 1 },
];
for (const c of cases) console.log(krapfen.doughMessage(c));
for (const t of [0.25, 0.5, 1, 3, 8, 10, 20]) console.log(`roomsize ${t} -> ${JSON.stringify(krapfen.reverbParams(t))}`);

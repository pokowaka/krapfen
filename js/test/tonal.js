// tonal: scales, transposition, chords and voicings.
//   qjs --std -I krapfen/src/main/assets/krapfen/strudel.js js/test/tonal.js
// Prints one line per case so QuickJS output can be diffed against Node.
const cases = [
  'n("0 2 4 6").scale("C:minor")',
  'n("0 1 2 3 4 5").scale("A:minor:pentatonic")',
  'n("<0 7> [2 4]").scale("<D:dorian F#:lydian>")',
  'note("c e g").transpose("<0 2 -12>")',
  'n("0 1 2").scale("C:major").scaleTranspose(2)',
  'chord("<Am7 C^7 Fmaj7 G7>").voicing()',
  'chord("<Dm9 G13>").dict("ireal").voicing()',
  'n("0 1 2 3").chord("<C Am>").voicing()',
  'chord("<C Am F G>").rootNotes(2)',
];
(async () => {
  for (const code of cases) {
    try {
      await krapfen.evaluate(code);
      const notes = [0, 1, 2]
        .map((c) => krapfen.query(c, c + 1).map((e) => e.value.note ?? JSON.stringify(e.value)).join(' '))
        .join(' | ');
      console.log(`${code}  =>  ${notes}`);
    } catch (e) {
      console.log(`${code}  =>  ERROR ${e.message}`);
    }
  }
})();

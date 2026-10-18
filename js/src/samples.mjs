// Sample support for Krapfen, following superdough's semantics
// (packages/superdough/sampler.mjs and util.mjs):
//
//   samples(map | url | 'github:user/repo[/branch]', baseUrl?)
//   s("bd sd").n("<0 1>").bank("RolandTR909").speed(2).note(...).clip(1)
//
// Sample maps are fetched and samples downloaded/decoded by the Kotlin host
// (SampleLoader) via __krapfenRequest; decoded audio is registered with
// dough as a numbered file slot. A sample that isn't loaded yet when it is
// first scheduled is skipped (and starts loading), as in superdough where
// first plays of a sound arrive late.

import { noteToMidi, freqToMidi } from '@strudel/core';

const hostRequestFn = globalThis.__krapfenRequest;
const MAX_FILES = globalThis.__krapfenMaxFiles ?? 1024;

// --- host requests (answered via krapfen._hostDone) ---

let nextRequestId = 1;
const pendingRequests = new Map();

function hostRequest(op, arg) {
  if (typeof hostRequestFn !== 'function') {
    return Promise.reject(new Error(`${op}: no host available`));
  }
  const id = nextRequestId++;
  return new Promise((resolve, reject) => {
    pendingRequests.set(id, { resolve, reject });
    hostRequestFn(op, id, typeof arg === 'string' ? arg : JSON.stringify(arg));
  });
}

export function hostDone(id, ok, payload) {
  const req = pendingRequests.get(id);
  if (!req) return;
  pendingRequests.delete(id);
  if (ok) req.resolve(payload);
  else req.reject(new Error(String(payload)));
}

// --- sample maps ---

// lowercased sound name -> array of URLs, or { noteName: [URLs] }
const soundMap = new Map();

function githubPath(base, subpath = '') {
  let path = base.slice('github:'.length);
  path = path.endsWith('/') ? path.slice(0, -1) : path;
  const [user, repo = 'samples', branch = 'main', ...other] = path.split('/');
  other.push(subpath);
  return `https://raw.githubusercontent.com/${user}/${repo}/${branch}/${other.join('/')}`;
}

function resolveBase(base) {
  if (base.startsWith('bubo:')) base = `github:Bubobubobubobubo/dough-${base.split(':')[1]}`;
  if (base.startsWith('github:')) base = githubPath(base, '');
  return base;
}

function joinUrl(base, path) {
  if (/^[a-z]+:\/\//i.test(path)) return path;
  // superdough concatenates directly; insert a separator when the base came
  // from the map's own URL (which has no trailing slash).
  if (base && !base.endsWith('/') && !path.startsWith('/')) return `${base}/${path}`;
  return base + path;
}

function registerMap(sampleMap, baseUrl) {
  let count = 0;
  for (const [key, raw] of Object.entries(sampleMap)) {
    if (key.startsWith('_')) continue;
    let value = typeof raw === 'string' ? [raw] : raw;
    if (typeof value !== 'object' || value === null) {
      throw new Error(`wrong sample map format for ${key}`);
    }
    const base = resolveBase(value._base || baseUrl || '');
    if (Array.isArray(value)) {
      value = value.map((v) => joinUrl(base, v));
    } else {
      value = Object.fromEntries(
        Object.entries(value)
          .filter(([note]) => !note.startsWith('_'))
          .map(([note, urls]) => [note, (typeof urls === 'string' ? [urls] : urls).map((v) => joinUrl(base, v))]),
      );
    }
    soundMap.set(key.toLowerCase(), value);
    count++;
  }
  return count;
}

/**
 * Loads a collection of samples to use with `s`, as on strudel.cc.
 * @example samples('github:tidalcycles/dirt-samples')
 */
export async function samples(sampleMap, baseUrl = sampleMap?._base || '') {
  if (typeof sampleMap === 'string') {
    if (/^(shabda|local):/.test(sampleMap)) {
      throw new Error(`samples: "${sampleMap.split(':')[0]}:" sources are not supported in Krapfen`);
    }
    let url = sampleMap;
    if (url.startsWith('bubo:')) url = `github:Bubobubobubobubo/dough-${url.split(':')[1]}`;
    if (url.startsWith('github:')) url = githubPath(url, 'strudel.json');
    const json = JSON.parse(await hostRequest('fetchText', url));
    const mapBase = json._base || url.split('/').slice(0, -1).join('/');
    const n = registerMap(json, baseUrl || mapBase);
    console.log(`[samples] ${n} sounds from ${url}`);
    return;
  }
  const n = registerMap(sampleMap, baseUrl);
  console.log(`[samples] ${n} sounds registered`);
}

export const isSample = (s) => typeof s === 'string' && soundMap.has(s.toLowerCase());

/**
 * Makes a sound loaded by the app (Krapfen.loadSound) playable from
 * patterns as s("name"). Its audio is already registered in dough as `file`.
 */
export function registerSound(name, file, seconds) {
  const url = `krapfen-sound:${name}`;
  soundMap.set(name.toLowerCase(), [url]);
  loaded.set(url, { file, state: 'ready', seconds });
}

// --- sample selection (superdough util.mjs getCommonSampleInfo) ---

function valueToMidi(value, fallback) {
  if (typeof value.freq === 'number') return freqToMidi(value.freq);
  if (typeof value.note === 'string') return noteToMidi(value.note);
  if (typeof value.note === 'number') return value.note;
  return fallback;
}

const mod = (n, m) => ((n % m) + m) % m;

function selectSample(value) {
  const bank = soundMap.get(value.s.toLowerCase());
  if (!bank) return null;
  const midi = valueToMidi(value, 36);
  let transpose = midi - 36; // C3 is the reference pitch
  let urls;
  if (Array.isArray(bank)) {
    urls = bank;
  } else {
    const diff = (note) => noteToMidi(note) - midi;
    const closest = Object.keys(bank).reduce(
      (best, key) => (best === null || Math.abs(diff(key)) < Math.abs(diff(best)) ? key : best),
      null,
    );
    transpose = -diff(closest);
    urls = bank[closest];
  }
  if (!urls?.length) return null;
  const index = mod(Math.round(value.n ?? 0), urls.length);
  return { url: urls[index], transpose };
}

// --- loading ---

// url -> { file, state: 'loading' | 'ready' | 'error', seconds }
const loaded = new Map();
let nextFile = 0;
const warned = new Set();

function ensureLoaded(url) {
  let entry = loaded.get(url);
  if (entry) return entry;
  if (nextFile >= MAX_FILES) {
    if (!warned.has('files')) console.warn(`[samples] all ${MAX_FILES} sample slots are in use`);
    warned.add('files');
    return { state: 'error' };
  }
  entry = { file: nextFile++, state: 'loading', seconds: 0 };
  loaded.set(url, entry);
  hostRequest('loadSample', { url, file: entry.file })
    .then((seconds) => {
      entry.seconds = Number(seconds);
      entry.state = 'ready';
    })
    .catch((err) => {
      entry.state = 'error';
      console.warn(`[samples] failed to load ${url}: ${err.message}`);
    });
  return entry;
}

/** Starts loading the sample a value refers to (no-op for synths). */
export function preload(value) {
  if (!isSample(value.s)) return;
  const pick = selectSample(value);
  if (pick) ensureLoaded(pick.url);
}

let skippedNotReady = 0;
export const sampleStats = () => ({ sounds: soundMap.size, files: nextFile, skippedNotReady });

/**
 * Turns a sample hap value into dough parameters, or returns null if the
 * sample isn't ready yet (it starts loading). `duration` is the hap's
 * duration in seconds.
 */
export function sampleParams(value, duration) {
  const pick = selectSample(value);
  if (!pick) return null;
  const entry = ensureLoaded(pick.url);
  if (entry.state !== 'ready') {
    skippedNotReady++;
    return null;
  }
  const speed = value.speed ?? 1;
  const rate = Math.abs(speed) * Math.pow(2, pick.transpose / 12);
  const begin = value.begin ?? 0;
  const end = value.end ?? 1;
  // superdough plays the whole begin..end slice unless clip, loop or
  // release is set; then the sound is held for the hap's duration and
  // released. (hap.duration already includes the clip factor.)
  const sliceDuration = (entry.seconds * Math.max(0, end - begin)) / Math.max(rate, 1e-6);
  const holdDuration = value.clip == null && value.loop == null && value.release == null ? sliceDuration : duration;
  const params = { ...value, file: entry.file, speed: speed < 0 ? -rate : rate };
  delete params.note; // pitch is applied through speed
  delete params.freq;
  delete params.n;
  delete params.bank;
  delete params.clip;
  // dough's `loop` (Krapfen patch) keeps the sample wrapping while held.
  if (value.loop) params.loop = 1;
  return { params, duration: holdDuration };
}

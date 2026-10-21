// grog's skin: watches what grog is up to (GET /v1/events, server-sent
// events, each with the whole state) and shows it. What to show comes from
// view.js; this only draws it. With ?kiosk (the fullscreen window), there's
// no mouse pointer.

import {
  account,
  activeTurn,
  captions,
  elapsed,
  glyph,
  mode,
  pipeline,
  recent,
  stale,
  timeFormats,
  usage,
  wordChanges,
} from "./view.js";

// The event types grog sends: see brain/src/grog/events.clj.
const EVENTS = ["snapshot", "voice", "turn", "tool", "text", "done", "failed", "status", "usage"];
// The skin moves a little this often, so that nothing marks the screen.
const DRIFT_MS = 5 * 60_000;

const ICONS = {
  web: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.5 3.6 5.3 3.6 8.5s-1.2 6-3.6 8.5c-2.4-2.5-3.6-5.3-3.6-8.5S9.6 6 12 3.5z"/>',
  volume: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4.5 4.5 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11"/>',
  machine: '<rect x="7" y="7" width="10" height="10" rx="1.5"/><path d="M10 3.5V7M14 3.5V7M10 17v3.5M14 17v3.5M3.5 10H7M3.5 14H7M17 10h3.5M17 14h3.5"/>',
  shell: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 10l3 2.5L7 15M12.5 15H17"/>',
  memory: '<path d="M7 4h10v16l-5-3.5L7 20z"/>',
  settings: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
  tool: '<path d="M14.5 5.5a4 4 0 0 0-5 5L4 16l4 4 5.5-5.5a4 4 0 0 0 5-5l-2.5 2.5-2.5-.5-.5-2.5z"/>',
  voice: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/>',
  thinking: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M18 6l-2.5 2.5M8.5 15.5L6 18"/>',
  answering: '<path d="M4 6.5h16M4 11.5h16M4 16.5h10"/>',
  speaking: '<path d="M5 10v4M9 7v10M13 4v16M17 8v8M21 11v2"/>',
  done: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  failed: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
  "no-mic": '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M4 4l16 16"/>',
  offline: '<circle cx="12" cy="12" r="8.5"/><path d="M6 6l12 12"/>',
};

// A mic, with sound around it while it hears.
const WAVES = `<svg viewBox="0 0 64 48" class="wide waves" aria-hidden="true">
  <rect x="27" y="7" width="10" height="20" rx="5"/><path d="M21 21a11 11 0 0 0 22 0M32 32v7M26 39h12"/>
  <path class="w" d="M17 14a12 12 0 0 0 0 16M47 14a12 12 0 0 1 0 16"/>
  <path class="w" d="M10 9a20 20 0 0 0 0 26M54 9a20 20 0 0 1 0 26"/>
</svg>`;
// Lines of an answer, being written.
const LINES = `<svg viewBox="0 0 24 24" class="lines" aria-hidden="true">
  <path class="w" d="M4 6.5h16"/><path class="w" d="M4 11.5h16"/><path class="w" d="M4 16.5h10"/>
</svg>`;

const $ = (id) => document.getElementById(id);
const els = Object.fromEntries(
  ["name", "model", "account", "clock", "date", "glyph", "label", "detail", "timer"]
    .concat(["captions", "question", "answer", "pipeline", "recent", "usage"])
    .map((id) => [id, $(id)]),
);

let state = null;
let offline = true;
// Lost grog since the page loaded: once back, reload.
let lost = false;
// grog's clock minus ours: times in the state are grog's.
let skew = 0;
// Formats for grog's time zone, made again only when it changes.
let formats = { timezone: undefined, ...timeFormats(undefined) };
// Draws the time of the turn under way, finer, while there's one.
let fine = null;
const drawn = {};

const now = () => Date.now() + skew;

function format(kind, t) {
  const timezone = state?.timezone;
  if (formats.timezone !== timezone) formats = { timezone, ...timeFormats(timezone) };
  return formats[kind].format(new Date(t));
}

function icon(name) {
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] ?? ICONS.tool}</svg>`;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

function setText(e, text) {
  if (e.textContent !== text) e.textContent = text;
}

/** Calls `draw` only when `data` changed since the last time for `key`. */
function once(key, data, draw) {
  const json = JSON.stringify(data);
  if (drawn[key] === json) return;
  drawn[key] = json;
  draw(data);
}

/** Bars of a voice, each at its own pace. */
function equalizer(n = 5) {
  const eq = el("span", "eq");
  for (let i = 0; i < n; i++) {
    const bar = el("b");
    bar.style.setProperty("--t", `${(0.3 + Math.random() * 0.4).toFixed(2)}s`);
    bar.style.setProperty("--d", `${(-Math.random()).toFixed(2)}s`);
    eq.append(bar);
  }
  return eq;
}

function drawGlyph(g) {
  switch (g.kind) {
    case "waves":
      els.glyph.innerHTML = WAVES;
      break;
    case "dots":
      els.glyph.innerHTML = '<span class="dots"><i></i><i></i><i></i></span>';
      break;
    case "lines":
      els.glyph.innerHTML = LINES;
      break;
    case "tool":
    case "loading":
      els.glyph.innerHTML = `<span class="ring"></span>${icon(g.kind === "tool" ? g.icon : "voice")}`;
      break;
    case "eq":
      els.glyph.replaceChildren(equalizer());
      break;
    default:
      els.glyph.innerHTML = icon(g.kind === "mic" ? "voice" : g.kind);
  }
}

function drawMode(t) {
  const m = mode(offline ? null : state, t);
  if (document.body.dataset.mode !== m.mode) document.body.dataset.mode = m.mode;
  setText(els.label, m.label);
  setText(els.detail, m.detail ?? "");
  once("glyph", glyph(m), drawGlyph);
  setText(els.timer, offline ? "" : elapsed(state, t));
}

function drawBrand(t) {
  const name = state?.name ?? "";
  setText(els.name, name);
  if (name && document.title !== name) document.title = name;
  setText(els.model, state?.model ?? "");
  setText(els.account, state ? account(state) : "");
  setText(els.clock, format("clock", t));
  setText(els.date, format("date", t));
}

/**
 * Shows `text` in `e` a word at a time: the words already there stay, new
 * ones (or ones heard better now) fade in one after another, like an answer
 * streaming in.
 */
function drawWords(e, text) {
  const shown = [...e.querySelectorAll(".word")];
  const { words, gone } = wordChanges(shown.map((w) => w.textContent), text);
  let fresh = 0;
  words.forEach(({ word, change }, i) => {
    if (change === "stay") return;
    const w = el("span", "word", word);
    w.style.setProperty("--i", String(fresh++));
    if (change === "replace") shown[i].replaceWith(w);
    else e.append(...(e.firstChild ? [" ", w] : [w]));
  });
  for (const w of shown.slice(shown.length - gone)) {
    w.previousSibling?.remove();
    w.remove();
  }
}

function drawCaptions(t) {
  els.captions.hidden = !state?.captions;
  const said = captions(state, t);
  els.captions.classList.toggle("empty", !said);
  els.captions.classList.toggle("live", Boolean(said?.live));
  if (said) {
    drawWords(els.question, said.question);
    setText(els.answer, said.answer);
  } else if (els.captions.hidden) {
    clearCaptions();
  }
}

// Gone, once faded out: the next question's words all fade in.
function clearCaptions() {
  if (!els.captions.classList.contains("empty")) return;
  els.question.replaceChildren();
  setText(els.answer, "");
}

function drawPipeline(t) {
  els.pipeline.classList.toggle("stale", stale(state, t));
  once("pipeline", pipeline(state?.turn, offline), (steps) => {
    els.pipeline.replaceChildren(
      ...steps.map((s) => {
        const li = el("li", `step ${s.status}`);
        const dot = el("div", "dot");
        dot.innerHTML = icon(s.icon);
        li.append(dot, el("div", "name", s.label), el("div", "offset", s.offset));
        return li;
      }),
    );
  });
}

function drawRecent() {
  once("recent", { turns: recent(state), timezone: state?.timezone }, ({ turns }) => {
    if (!turns.length) {
      els.recent.replaceChildren(el("li", "none", "Nothing yet"));
      return;
    }
    els.recent.replaceChildren(
      ...turns.map((r) => {
        const li = el("li", r.ok ? "" : "failed");
        const when = el("time", null, format("clock", r.at));
        const what = el("span");
        what.innerHTML = icon(r.icon);
        li.append(when, what, el("span", null, r.label), el("span", "took", r.took));
        return li;
      }),
    );
  });
}

function drawUsage(t) {
  // Countdowns change by the minute.
  once("usage", usage(state?.usage, Math.floor(t / 60_000) * 60_000), (u) => {
    els.usage.hidden = !u;
    if (!u) return;
    if (u.kind === "subscription") {
      const rows = u.windows.map((w) => {
        const row = el("div", "meter");
        const track = el("div", "track");
        const fill = el("div", `fill ${w.level}`);
        fill.style.transform = `scaleX(${w.percent / 100})`;
        track.append(fill);
        row.append(el("span", "key", w.label.toUpperCase()), track, el("span", "value", `${w.percent}%`));
        if (w.resetsIn) row.append(el("span", "reset", `resets in ${w.resetsIn}`));
        return row;
      });
      els.usage.replaceChildren(
        el("h2", null, u.title),
        ...(rows.length ? rows : [el("div", "note", "usage not known yet")]),
      );
    } else {
      const chart = el("div", "chart");
      chart.append(
        ...u.chart.map((d) => {
          const bar = el("i");
          bar.style.height = `${Math.round(d.height * 100)}%`;
          bar.title = d.date;
          return bar;
        }),
      );
      const parts = [
        el("h2", null, "Spent"),
        el("div", "spent", u.month),
        el("div", "spent-label", `this month · ${u.today} today`),
        chart,
      ];
      if (u.unpriced) parts.push(el("div", "note", `${u.unpriced} turns without a price`));
      els.usage.replaceChildren(...parts);
    }
  });
}

function draw() {
  const t = now();
  drawMode(t);
  drawBrand(t);
  drawCaptions(t);
  drawPipeline(t);
  drawRecent();
  drawUsage(t);
  tick();
}

// The time of the turn under way, finer, only while there's one.
function tick() {
  const active = !offline && Boolean(activeTurn(state));
  if (active && !fine) fine = setInterval(() => drawMode(now()), 100);
  if (!active && fine) {
    clearInterval(fine);
    fine = null;
  }
}

function receive(event) {
  const { state: next } = JSON.parse(event.data);
  state = next;
  skew = state.now - Date.now();
  offline = false;
  draw();
}

function connect() {
  const source = new EventSource("/v1/events");
  for (const type of EVENTS) source.addEventListener(type, receive);
  // Back after grog restarted, a deploy say: the skin may be new too.
  source.onopen = () => {
    if (lost) location.reload();
  };
  source.onerror = () => {
    lost = true;
    offline = true;
    draw();
    // Refused for good (not just cut): start over in a while.
    if (source.readyState === EventSource.CLOSED) setTimeout(connect, 5000);
  };
}

function drift() {
  const shift = () => `${(Math.random() - 0.5).toFixed(2)}vmin`;
  document.querySelector(".app").style.setProperty("--drift", `${shift()} ${shift()}`);
}

if (new URLSearchParams(location.search).has("kiosk")) document.documentElement.classList.add("kiosk");
els.captions.addEventListener("transitionend", (e) => {
  if (e.target === els.captions) clearCaptions();
});
draw();
connect();
// The clock, countdowns, and what times out (errors, captions).
setInterval(draw, 1000);
setInterval(drift, DRIFT_MS);

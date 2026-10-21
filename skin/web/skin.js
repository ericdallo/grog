// grog's skin: watches what grog is up to (GET /v1/events, server-sent
// events, each with the whole state) and shows it. What to show comes from
// view.js; this only draws it. With ?kiosk (the fullscreen window), there's
// no mouse pointer.

import { Face } from "./face.js";
import { ICONS } from "./icons.js";
import {
  account,
  activeTurn,
  captions,
  elapsed,
  face,
  isNight,
  mode,
  pipeline,
  recent,
  signIn,
  stale,
  timeFormats,
  usage,
  wordChanges,
} from "./view.js";

// The event types grog sends: see brain/src/grog/events.clj.
const EVENTS = ["snapshot", "voice", "turn", "tool", "text", "done", "failed", "status", "login", "usage"];
// The skin moves a little this often, so that nothing marks the screen.
const DRIFT_MS = 5 * 60_000;

const $ = (id) => document.getElementById(id);
const els = Object.fromEntries(
  ["name", "model", "account", "clock", "date", "avatar", "label", "detail", "timer"]
    .concat(["captions", "question", "answer", "pipeline", "recent", "usage"])
    .concat(["signin", "signin-title", "signin-qr", "signin-url", "signin-code", "signin-expires"])
    .concat(["signin-note", "signin-error", "signin-help", "signin-help-text", "signin-help-url"])
    .map((id) => [id, $(id)]),
);
// grog's face, in its block.
const eye = new Face(els.avatar);

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
// The hour on the clock, to know when a new one starts.
let hourShown = null;
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

/** What grog does, by its color and its words, and its face, by day or by night. */
function drawMode(t) {
  const m = mode(offline ? null : state, t);
  if (document.body.dataset.mode !== m.mode) document.body.dataset.mode = m.mode;
  const night = isNight(Number(format("hour", t)));
  document.body.classList.toggle("night", night);
  setText(els.label, m.label);
  setText(els.detail, m.detail ?? "");
  const f = face(m, offline ? null : state, t, night);
  eye.set(f.state, f.icon);
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
  // On the hour, grog looks at the clock.
  const hour = format("hour", t);
  if (hourShown !== null && hour !== hourShown) eye.lookAtClock();
  hourShown = hour;
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

/** The QR code `src` in `img`, shown once it loads: none for pages the skin has no code of. */
function showQr(img, src) {
  if (!src) {
    img.hidden = true;
    img.removeAttribute("src");
  } else if (img.getAttribute("src") !== src) {
    img.hidden = true;
    img.src = src;
  }
}

/**
 * How to sign in, while grog has nothing to pay with: in place of what was
 * said and done, which there's none of then.
 */
function drawSignIn(t) {
  const s = offline ? null : signIn(state, t);
  els.signin.hidden = !s;
  document.body.classList.toggle("signing-in", Boolean(s));
  if (!s) return;
  els.signin.classList.toggle("waiting", Boolean(s.code));
  setText(els["signin-title"], s.title);
  setText(els["signin-url"], s.shortUrl ?? "");
  setText(els["signin-code"], s.code ?? "");
  setText(els["signin-expires"], s.expires ?? "");
  setText(els["signin-note"], s.note ?? "");
  setText(els["signin-error"], s.error ?? "");
  showQr(els["signin-qr"], s.qr);
  els["signin-help"].hidden = !s.help;
  setText(els["signin-help-text"], s.help?.text ?? "");
  setText(els["signin-help-url"], s.help?.shortUrl ?? "");
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
  drawSignIn(t);
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
els["signin-qr"].addEventListener("load", () => {
  els["signin-qr"].hidden = false;
});
draw();
connect();
// The clock, countdowns, and what times out (errors, captions).
setInterval(draw, 1000);
setInterval(drift, DRIFT_MS);

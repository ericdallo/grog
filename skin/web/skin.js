// grog's skin: watches what grog is up to (GET /v1/events, server-sent
// events, each with the whole state) and shows it. What to show comes from
// view.js; this only draws it, and asks grog what someone touched: its face
// (to talk to it, or stop it), and its actions (POST /v1/actions/<name>).
// With ?kiosk (the fullscreen window), there's no mouse pointer.

import { Face } from "./face.js";
import { ICONS } from "./icons.js";
import {
  account,
  activeTurn,
  chat,
  closes,
  CONFIRM_MS,
  elapsed,
  face,
  FOLLOW_MS,
  isNight,
  menu,
  mode,
  pairing,
  pipeline,
  request,
  signIn,
  stale,
  tap,
  timeFormats,
  updateNotice,
  usage,
  version,
  wordChanges,
} from "./view.js";

// The event types grog sends: see brain/src/grog/events.clj.
const EVENTS = ["snapshot", "voice", "turn", "tool", "tool-detail", "text", "done", "failed", "status"].concat([
  "login",
  "usage",
  "restarting",
  "mic",
  "volume",
  "stop",
  "forgot",
  "update",
  "pairing",
]);
// How close to its end the chat is still at its end, in pixels.
const END_PX = 8;
// The chat scrolls this soon after someone touched it: they scrolled it.
const TOUCHED_MS = 1_000;
// The skin moves a little this often, so that nothing marks the screen.
const DRIFT_MS = 5 * 60_000;
// Untouched this long, the actions close.
const SHEET_MS = 20_000;
// How long what went wrong asking grog shows.
const SHEET_ERROR_MS = 5_000;

const $ = (id) => document.getElementById(id);
const els = Object.fromEntries(
  ["name", "model", "account", "clock", "date", "avatar", "label", "detail", "timer"]
    .concat(["chat", "chat-end", "pipeline", "usage"])
    .concat(["signin", "signin-title", "signin-qr", "signin-url", "signin-code", "signin-expires"])
    .concat(["signin-note", "signin-error", "signin-help", "signin-help-text", "signin-help-url"])
    .concat(["pairing", "pairing-title", "pairing-detail", "pairing-hint", "pairing-command", "pairing-code"])
    .concat(["pairing-note", "pairing-buttons", "pairing-expires", "pairing-error"])
    .concat(["open-actions", "close-actions", "sheet", "items", "sheet-error", "sheet-version", "notice"])
    .map((id) => [id, $(id)]),
);
// grog's face, in its block.
const eye = new Face(els.avatar);

let state = null;
// The turns before the last, as grog last sent them: not with every event.
let history = [];
// Whether the chat keeps to its end, as what's new comes, and when someone
// last touched it.
let following = true;
let touchedAt = 0;
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
// The actions: whether they show, the one waiting for a second tap, and the
// timers closing them, disarming it, and clearing what went wrong.
let sheetOpen = false;
let armed = null;
const timers = {};

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

/**
 * What grog does, by its color and its words, and its face, by day or by
 * night, with what touching it does.
 */
function drawMode(t) {
  const m = mode(state, t, offline);
  if (document.body.dataset.mode !== m.mode) document.body.dataset.mode = m.mode;
  const night = isNight(Number(format("hour", t)));
  document.body.classList.toggle("night", night);
  setText(els.label, m.label);
  setText(els.detail, m.detail ?? "");
  const f = face(m, offline ? null : state, t, night);
  eye.set(f.state, f.icon);
  setText(els.timer, offline ? "" : elapsed(state, t));
  const touch = tap(state, t, offline);
  const label = touch?.label ?? "";
  if (els.avatar.getAttribute("aria-label") !== label) els.avatar.setAttribute("aria-label", label);
  els.avatar.classList.toggle("touchable", Boolean(touch));
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

/** A tool grog uses, or used: what with, once known. */
function toolRow(item) {
  const row = el("div", `tool${item.running ? " running" : ""}`);
  row.innerHTML = icon(item.icon);
  row.append(el("span", "what", item.label));
  if (item.detail) row.append(el("span", "with", item.detail));
  return row;
}

/** How a turn ended: when it started, how long it took, and how it went if not well. */
function endRow(turn) {
  const { status, took, error } = turn.end;
  const row = el("div", `end ${status}`);
  row.append(el("time", null, format("clock", turn.at)), el("span", null, took));
  if (status === "stopped") row.append(el("span", "how", "Stopped"));
  if (status === "failed") row.append(el("span", "how", error ? `Failed: ${error}` : "Failed"));
  return row;
}

/**
 * A turn of the chat (view.js chat), in its `li`: what was said for it, a
 * word at a time while it's heard, then its answer, and how it ended. What's
 * new in it since it was last drawn fades in, unless `quiet`.
 */
function drawTurn(li, turn, quiet) {
  let said = li.querySelector(":scope > .said");
  let reply = li.querySelector(":scope > .reply");
  if (!said) {
    said = el("div", "said");
    reply = el("div", "reply");
    li.append(said, reply);
  }
  said.hidden = !turn.said;
  said.classList.toggle("live", turn.live);
  if (turn.live || said.querySelector(".word")) drawWords(said, turn.said);
  else setText(said, turn.said);
  const before = Number(li.dataset.items ?? 0);
  const rows = turn.items.map((item, i) => {
    const row = item.kind === "text" ? el("p", "text", item.text) : toolRow(item);
    if (!quiet && i >= before) row.classList.add("fresh");
    return row;
  });
  if (turn.end) {
    const row = endRow(turn);
    if (!quiet && !li.dataset.ended) row.classList.add("fresh");
    li.dataset.ended = "1";
    rows.push(row);
  }
  li.dataset.items = String(turn.items.length);
  reply.replaceChildren(...rows);
}

/**
 * What was said and done, a turn per item: only those that changed are
 * drawn again, and those already there stay in the page (moving one would
 * play its animations again). What grog-voice heard becomes the turn it's
 * for. It keeps to its end, unless someone scrolled back.
 */
function drawChat(t) {
  const quiet = !els.chat.dataset.drawn;
  once("chat", { turns: chat(state, history, t, offline), timezone: state?.timezone }, ({ turns }) => {
    if (state) els.chat.dataset.drawn = "1";
    const byKey = new Map([...els.chat.children].map((li) => [li.dataset.key, li]));
    let heard = turns.some((turn) => turn.key === "heard") ? null : byKey.get("heard");
    const lis = turns.map((turn, i) => {
      let li = byKey.get(turn.key);
      if (!li && heard && turn.said && i === turns.length - 1) {
        li = heard;
        heard = null;
      }
      li ??= el("li", quiet ? "turn" : "turn fresh");
      li.dataset.key = turn.key;
      const json = JSON.stringify(turn);
      if (li.dataset.json !== json) {
        drawTurn(li, turn, quiet);
        li.dataset.json = json;
      }
      return li;
    });
    for (const li of [...els.chat.children]) if (!lis.includes(li)) li.remove();
    let before = null;
    for (const li of lis) {
      const next = before ? before.nextSibling : els.chat.firstChild;
      if (next !== li) els.chat.insertBefore(li, next);
      before = li;
    }
    if (following) els.chat.scrollTop = els.chat.scrollHeight;
  });
}

/** Back to the chat's end, keeping to it. */
function follow() {
  following = true;
  els["chat-end"].hidden = true;
  els.chat.scrollTop = els.chat.scrollHeight;
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

/**
 * Pairing a computer: what to run on it, then its code to check, with Allow
 * and Deny; in place of what was said and done, while it goes.
 */
function drawPairing(t) {
  const p = offline ? null : pairing(state, t);
  document.body.classList.toggle("adding-computer", Boolean(p));
  once("pairing", p, (v) => {
    els.pairing.hidden = !v;
    if (!v) return;
    els.pairing.classList.toggle("failed", v.failed);
    setText(els["pairing-title"], v.title);
    setText(els["pairing-detail"], v.detail);
    setText(els["pairing-hint"], v.hint);
    setText(els["pairing-command"], v.command);
    setText(els["pairing-code"], v.code);
    setText(els["pairing-note"], v.note);
    setText(els["pairing-expires"], v.expires);
    els["pairing-buttons"].replaceChildren(
      ...v.buttons.map((b) => {
        const button = el("button", `choice${b.primary ? " primary" : ""}`, b.label);
        button.type = "button";
        button.dataset.action = b.id;
        return button;
      }),
    );
  });
}

/** How an update goes, under the time; while grog restarts into it, its block says so. */
function drawNotice(t) {
  once("notice", offline ? null : updateNotice(state, t), (n) => {
    els.notice.hidden = !n;
    if (!n) return;
    els.notice.classList.toggle("failed", n.failed);
    els.notice.innerHTML = icon(n.icon);
    els.notice.append(el("span", "what", n.label));
    if (n.detail) els.notice.append(el("span", "more", n.detail));
  });
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

function iconButton(className, name, label, data = {}) {
  const button = el("button", className);
  button.type = "button";
  button.innerHTML = icon(name);
  button.setAttribute("aria-label", label);
  Object.assign(button.dataset, data);
  return button;
}

/** The volume: quieter, how loud, louder, and mute. */
function volumeItem(item) {
  const li = el("li", `item volume${item.muted ? " muted" : ""}`);
  const track = el("div", "track");
  const fill = el("div", "fill");
  fill.style.transform = `scaleX(${(item.level ?? 0) / 100})`;
  track.append(fill);
  li.append(
    el("span", "text", item.label),
    iconButton("round", "minus", "Quieter", { action: "volume", step: "-1" }),
    track,
    el("span", "value", item.level === null ? "…" : `${item.level}%`),
    iconButton("round", "plus", "Louder", { action: "volume", step: "1" }),
    iconButton(`round${item.muted ? " on" : ""}`, "muted", item.muted ? "Unmute" : "Mute", {
      action: "volume",
      step: "mute",
    }),
  );
  return li;
}

/** An action: a switch, or what it does, armed when it waits for a second tap. */
function actionItem(item) {
  const li = el("li", "item");
  const button = el("button", `row${item.armed ? " armed" : ""}`);
  button.type = "button";
  button.dataset.action = item.id;
  const text = el("span", "text", item.label);
  if (item.detail && !item.armed) text.append(el("span", "more", item.detail));
  button.innerHTML = icon(item.icon);
  button.append(text);
  if (item.kind === "toggle") {
    button.setAttribute("role", "switch");
    button.setAttribute("aria-checked", String(item.on));
    button.append(el("span", `switch${item.on ? " on" : ""}`));
  }
  li.append(button);
  return li;
}

/** The actions, when they show: everyday ones, then those that can't be undone. */
function drawSheet() {
  const can = !offline && Boolean(state?.actions?.length);
  if (els["open-actions"].hidden === can) els["open-actions"].hidden = !can;
  if (!can) shut();
  els.sheet.classList.toggle("open", sheetOpen);
  if (els.sheet.inert === sheetOpen) els.sheet.inert = !sheetOpen;
  const v = version(state);
  setText(els["sheet-version"], v ? `Version ${v}` : "");
  once("menu", menu(state, armed), (items) => {
    const list = (some) => {
      const ul = el("ul", "group");
      ul.append(...some.map((item) => (item.kind === "volume" ? volumeItem(item) : actionItem(item))));
      return ul;
    };
    els.items.replaceChildren(list(items.filter((i) => !i.confirm)), list(items.filter((i) => i.confirm)));
  });
}

function draw() {
  const t = now();
  drawMode(t);
  drawBrand(t);
  drawSignIn(t);
  drawPairing(t);
  drawNotice(t);
  drawChat(t);
  drawPipeline(t);
  drawUsage(t);
  drawSheet();
  tick();
}

/**
 * Asks grog for `action` (see view.js request): its answer, or null after
 * showing what went wrong (`failed`, in the actions by default).
 */
async function ask(action, step = null, failed = showError) {
  const { method, path, body } = request(action, state, step);
  try {
    const resp = await fetch(path, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const answer = await resp.json().catch(() => ({}));
    if (!resp.ok) throw new Error(answer.error ?? `grog answered ${resp.status}`);
    return answer;
  } catch (e) {
    failed(e.message);
    return null;
  }
}

/** What went wrong pairing, for a moment. */
function showPairingError(message) {
  setText(els["pairing-error"], message);
  later("pairing-error", SHEET_ERROR_MS, () => setText(els["pairing-error"], ""));
}

function later(name, ms, f) {
  clearTimeout(timers[name]);
  timers[name] = ms === null ? undefined : setTimeout(f, ms);
}

function showError(message) {
  setText(els["sheet-error"], message);
  later("error", SHEET_ERROR_MS, () => setText(els["sheet-error"], ""));
}

/** The actions stay open while touched. */
function touched() {
  later("sheet", SHEET_MS, closeSheet);
}

function openSheet() {
  sheetOpen = true;
  armed = null;
  touched();
  // How loud it is now: grog only knows once it asked.
  if (state?.actions?.includes("volume")) ask("volume");
  draw();
}

/** The actions go, and so does what was waiting for a second tap. */
function shut() {
  sheetOpen = false;
  armed = null;
  later("sheet", null);
  later("armed", null);
}

function closeSheet() {
  shut();
  draw();
}

function arm(action) {
  armed = action;
  later("armed", CONFIRM_MS, () => {
    armed = null;
    draw();
  });
  draw();
}

async function choose(button) {
  const action = button.dataset.action;
  const step = button.dataset.step ?? null;
  touched();
  const item = menu(state).find((i) => i.id === action);
  if (item?.confirm && armed !== action) {
    arm(action);
    return;
  }
  armed = null;
  later("armed", null);
  const answer = await ask(action, step === null || step === "mute" ? step : Number(step));
  if (answer && closes(action)) closeSheet();
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
  if (next.history) history = next.history;
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
// Scrolled back by someone (not by what it shows growing), the chat stays
// there, until it's left alone a while.
for (const type of ["wheel", "touchmove", "pointerdown"]) {
  els.chat.addEventListener(type, () => (touchedAt = Date.now()), { passive: true });
}
els.chat.addEventListener("scroll", () => {
  const atEnd = els.chat.scrollHeight - els.chat.scrollTop - els.chat.clientHeight <= END_PX;
  if (atEnd) following = true;
  else if (Date.now() - touchedAt < TOUCHED_MS) following = false;
  else if (following) els.chat.scrollTop = els.chat.scrollHeight;
  els["chat-end"].hidden = following;
  later("follow", following ? null : FOLLOW_MS, follow);
});
// Words take more room once their fonts are in.
document.fonts.ready.then(() => {
  if (following) els.chat.scrollTop = els.chat.scrollHeight;
});
els["chat-end"].innerHTML = icon("down");
els["chat-end"].addEventListener("click", follow);
// Touching grog's face talks to it, or stops it.
els.avatar.addEventListener("click", () => {
  const touch = tap(state, now(), offline);
  if (!touch) return;
  eye.poke();
  ask(touch.action);
});
els["open-actions"].innerHTML = icon("menu");
els["close-actions"].innerHTML = icon("close");
els["open-actions"].addEventListener("click", openSheet);
// Scrolling the actions is touching them: they stay open.
els.sheet.addEventListener("scroll", () => sheetOpen && touched(), { passive: true });
els["close-actions"].addEventListener("click", closeSheet);
els.items.addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button) choose(button);
});
// Allowing the computer asking to pair, or not, or no longer letting one.
els["pairing-buttons"].addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button) ask(button.dataset.action, null, showPairingError);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && sheetOpen) closeSheet();
});
els["signin-qr"].addEventListener("load", () => {
  els["signin-qr"].hidden = false;
});
draw();
connect();
// The clock, countdowns, and what times out (errors, what was heard).
setInterval(draw, 1000);
setInterval(drift, DRIFT_MS);

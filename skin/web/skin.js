// grog's skin: watches what grog is up to (GET /v1/events, server-sent
// events, each with the whole state) and shows it. What to show comes from
// view.js; this only draws it, and asks grog what someone touched: its face
// (to talk to it, or stop it), its actions (POST /v1/actions/<name>, its
// speakers too), and its music (POST /v1/music, and what to play from GET
// /v1/music/library). With ?kiosk (the fullscreen window), there's no
// mouse pointer. In the kiosk, it also says when the screen is to be on or
// off (view.js screenOn).

import { Face } from "./face.js";
import { ICONS } from "./icons.js";
import {
  account,
  activeTurn,
  canJoin,
  card,
  chat,
  closes,
  CONFIRM_MS,
  elapsed,
  face,
  FOLLOW_MS,
  isNight,
  joinRequest,
  keyboard,
  menu,
  mode,
  musicSheet,
  needsPassword,
  nowPlaying,
  physicalKey,
  pipeline,
  request,
  screenOn,
  speakersSheet,
  stale,
  tap,
  timeFormats,
  typed,
  updateNotice,
  usage,
  version,
  wifiSheet,
  wordChanges,
} from "./view.js";

// The event types grog sends: see brain/src/grog/events.clj.
const EVENTS = ["snapshot", "voice", "turn", "tool", "tool-detail", "text", "done", "failed", "status"].concat([
  "usage",
  "restarting",
  "mic",
  "speaker",
  "volume",
  "stop",
  "forgot",
  "update",
  "connect",
  "music",
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
    .concat(["card", "card-title", "card-detail", "card-question", "card-qr", "card-hint", "card-link", "card-hint2", "card-code"])
    .concat(["card-command", "card-expires", "card-note", "card-error", "card-help", "card-help-text"])
    .concat(["card-help-url", "card-buttons", "card-asked"])
    .concat(["open-actions", "close-actions", "sheet", "items", "sheet-error", "sheet-version", "notice"])
    .concat(["player", "open-music", "player-title", "player-artist", "player-time", "player-error"])
    .concat(["player-controls", "player-volume", "music-down", "music-up"])
    .concat(["music-sheet", "close-music", "music-note", "music-items", "music-error"])
    .concat(["speakers-sheet", "close-speakers", "speakers-note", "speakers-items", "speakers-error"])
    .concat(["wifi-sheet", "close-wifi", "wifi-note", "wifi-items", "wifi-error"])
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
// The music sheet: whether it shows, what grog answered for it (null while
// it's on its way), and how many times it was asked, the last answer
// winning.
let musicOpen = false;
let library = null;
let libraryAsks = 0;
// The speakers sheet: whether it shows, what grog last answered for it
// (null while it's on its way), and how many times it was asked, the last
// answer winning.
let speakersOpen = false;
let speakers = null;
let speakersAsks = 0;
// The Wi-Fi sheet: whether it shows, what grog last answered for it (null
// while it's on its way), how many times it was asked, the last answer
// winning, the password being typed for a network (view.js typed()),
// which only ever goes to grog, when joining, and the network it joins
// without one (saved, or open), if any.
let wifiOpen = false;
let wifi = null;
let wifiAsks = 0;
let form = null;
let joining = null;
// When someone last touched the screen (grog's clock), and whether the
// kiosk was last told to have it on: null before it was told anything.
let touchedScreenAt = 0;
let screenTold = null;

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

/** The QR code `src` in `img`, shown once it loads: none without one. */
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
 * Connecting something to grog (view.js card): the link to its page and its
 * QR code, what to do now, a code when there's one, Allow and Deny when it's
 * asked, Cancel; in place of what was said and done, while it shows.
 */
function drawCard(t) {
  const c = offline ? null : card(state, t);
  document.body.classList.toggle("connecting", Boolean(c));
  once("card", c, (v) => {
    els.card.hidden = !v;
    if (!v) return;
    els.card.classList.toggle("failed", v.failed);
    els.card.classList.toggle("with-qr", Boolean(v.qr));
    setText(els["card-title"], v.title);
    setText(els["card-detail"], v.detail);
    setText(els["card-question"], v.question);
    setText(els["card-hint"], v.hint);
    setText(els["card-link"], v.link);
    setText(els["card-hint2"], v.hint2);
    setText(els["card-code"], v.code);
    setText(els["card-command"], v.command);
    setText(els["card-expires"], v.expires);
    setText(els["card-note"], v.note);
    setText(els["card-error"], v.error);
    showQr(els["card-qr"], v.qr);
    els["card-help"].hidden = !v.help;
    setText(els["card-help-text"], v.help?.text ?? "");
    setText(els["card-help-url"], v.help?.shortUrl ?? "");
    els["card-buttons"].replaceChildren(
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
  // One of a group, which plays (speakers).
  if (item.chosen !== undefined) {
    button.setAttribute("role", "radio");
    button.setAttribute("aria-checked", String(item.chosen));
    if (item.chosen) {
      const mark = el("span", "chosen");
      mark.innerHTML = icon("done");
      button.append(mark);
    }
  }
  button.disabled = Boolean(item.disabled);
  li.append(button);
  return li;
}

/** The actions, when they show: everyday ones, what's connected, then going away. */
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
    const integrations = items.filter((i) => i.group === "integrations");
    els.items.replaceChildren(
      list(items.filter((i) => i.group === "everyday")),
      ...(integrations.length ? [el("h3", "group-title", "Integrations"), list(integrations)] : []),
      list(items.filter((i) => i.group === "power")),
    );
  });
}

/**
 * What grog's player plays (view.js nowPlaying), in a bar under the rest,
 * in place of the steps of the last turn: the song, its time, and its
 * buttons. Its time changes once a second while it plays, and nothing else
 * moves.
 */
function drawPlayer(t) {
  const p = nowPlaying(state, t, offline);
  document.body.classList.toggle("music", Boolean(p));
  if (els.player.hidden === Boolean(p)) els.player.hidden = !p;
  if (!p) return;
  setText(els["player-title"], p.title);
  setText(els["player-artist"], p.artist);
  setText(els["player-time"], p.time);
  setText(els["player-volume"], p.volume === null ? "" : String(p.volume));
  once("player", p.buttons, (buttons) => {
    els["player-controls"].replaceChildren(
      ...buttons.map((b) => {
        const main = b.action === "music-pause" || b.action === "music-resume";
        return iconButton(`round${main ? " main" : ""}`, b.icon, b.label, { action: b.action });
      }),
    );
  });
}

/** What to play (view.js musicSheet), when it shows: grog's player has to be there. */
function drawMusic() {
  if (musicOpen && (offline || !state?.music)) shutMusic();
  els["music-sheet"].classList.toggle("open", musicOpen);
  if (els["music-sheet"].inert === musicOpen) els["music-sheet"].inert = !musicOpen;
  once("music", musicSheet(library, state), (v) => {
    setText(els["music-note"], v.message);
    setText(els["music-error"], v.error);
    els["music-items"].replaceChildren(...v.items.map((item) => actionItem({ ...item, kind: "button" })));
  });
}

/** Which speakers the music and grog's voice play on (view.js speakersSheet), when it shows. */
function drawSpeakers() {
  if (speakersOpen && offline) shutSpeakers();
  els["speakers-sheet"].classList.toggle("open", speakersOpen);
  if (els["speakers-sheet"].inert === speakersOpen) els["speakers-sheet"].inert = !speakersOpen;
  once("speakers", speakersSheet(speakers, state), (v) => {
    setText(els["speakers-note"], v.message);
    if (v.error) showSpeakersError(v.error);
    els["speakers-items"].replaceChildren(
      ...v.groups.flatMap((g) => {
        const ul = el("ul", "group");
        ul.setAttribute("role", "radiogroup");
        ul.setAttribute("aria-label", g.title);
        ul.append(...g.items.map((item) => actionItem({ ...item, kind: "button" })));
        return [el("h3", "group-title", g.title), ul];
      }),
    );
  });
}

/** The password being typed for a network: its field, and the keyboard typing it. */
function passwordPanel(f) {
  const panel = el("div", "password");
  const top = el("div", "password-top");
  const cancel = el("button", "text-button", "Cancel");
  cancel.type = "button";
  cancel.dataset.wifi = "cancel";
  cancel.disabled = f.busy;
  top.append(el("span", "password-title", f.title), cancel);
  const field = el("div", `field${f.field ? "" : " empty"}`, f.field || "Password");
  // Where what's typed goes: after it, or before the word saying what to type.
  if (f.field) field.append(el("span", "caret"));
  else field.prepend(el("span", "caret"));
  const show = iconButton("round", f.shown ? "eye-off" : "eye", f.shown ? "Hide it" : "Show it", { wifi: "show" });
  show.disabled = f.busy;
  const line = el("div", "field-line");
  line.append(field, show);
  const note = el("div", `password-note${f.error ? " failed" : ""}`, f.busy ? "Joining…" : f.error || f.hint);
  const keys = el("div", "keyboard");
  f.keys.forEach((row, r) => {
    const div = el("div", "keys");
    row.forEach((key, c) => {
      const button = el("button", `key ${key.kind}${key.on ? " on" : ""}`);
      button.type = "button";
      Object.assign(button.dataset, { row: String(r), col: String(c) });
      if (key.icon) {
        button.innerHTML = icon(key.icon);
        button.setAttribute("aria-label", key.label);
      } else {
        button.textContent = key.label;
      }
      // Nothing typed while it joins.
      button.disabled = f.busy || (key.kind === "done" && !f.join);
      div.append(button);
    });
    keys.append(div);
  });
  panel.append(top, line, note, keys);
  return panel;
}

/** Its networks, or the password being typed for one (view.js wifiSheet), when it shows. */
function drawWifi() {
  if (wifiOpen && offline && !form?.busy) shutWifi();
  els["wifi-sheet"].classList.toggle("open", wifiOpen);
  if (els["wifi-sheet"].inert === wifiOpen) els["wifi-sheet"].inert = !wifiOpen;
  once("wifi", wifiSheet(wifi, form, joining), (v) => {
    setText(els["wifi-note"], v.message);
    if (v.error) showWifiError(v.error);
    if (v.form) {
      els["wifi-items"].replaceChildren(passwordPanel(v.form));
      // The end of what's typed shows, however long it is.
      const field = els["wifi-items"].querySelector(".field");
      field.scrollLeft = field.scrollWidth;
      return;
    }
    const ul = el("ul", "group");
    ul.append(...v.items.map((item) => actionItem({ ...item, kind: "button" })));
    const buttons = el("div", "sheet-buttons");
    buttons.append(
      ...v.buttons.map((b) => {
        const button = el("button", "text-button", b.label);
        button.type = "button";
        button.dataset.action = b.id;
        button.disabled = b.disabled;
        return button;
      }),
    );
    els["wifi-items"].replaceChildren(ul, buttons);
  });
}

/**
 * Tells the kiosk (the fullscreen window, kiosk.js) whether the screen is to
 * be on (view.js screenOn), when that changes, once grog said how long it
 * stays on. Elsewhere, in a browser, nothing.
 */
function drawScreen(t) {
  const kiosk = window.webkit?.messageHandlers?.screen;
  if (!kiosk || !state) return;
  const on = screenOn(state, t, touchedScreenAt);
  if (on === screenTold) return;
  screenTold = on;
  kiosk.postMessage(on);
}

/** Someone touched the screen: it stays on a while. */
function touchedScreen() {
  touchedScreenAt = now();
  drawScreen(touchedScreenAt);
}

function draw() {
  const t = now();
  drawMode(t);
  drawBrand(t);
  drawCard(t);
  drawNotice(t);
  drawChat(t);
  drawPipeline(t);
  drawPlayer(t);
  drawUsage(t);
  drawSheet();
  drawMusic();
  drawSpeakers();
  drawWifi();
  drawScreen(t);
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

/** What went wrong asking grog from the card, for a moment. */
function showCardError(message) {
  setText(els["card-asked"], message);
  later("card-asked", SHEET_ERROR_MS, () => setText(els["card-asked"], ""));
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
  shutMusic();
  shutSpeakers();
  shutWifi();
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

/** What went wrong asking grog from the music's bar, there, red, for a moment. */
function showPlayerError(message) {
  setText(els["player-error"], message);
  els.player.classList.add("failed");
  later("player-error", SHEET_ERROR_MS, () => {
    setText(els["player-error"], "");
    els.player.classList.remove("failed");
  });
}

function showMusicError(message) {
  setText(els["music-error"], message);
  later("music-error", SHEET_ERROR_MS, () => setText(els["music-error"], ""));
}

/** What grog answers for the user's playlists: {items}, or {error, connect}. */
async function askLibrary() {
  try {
    const resp = await fetch("/v1/music/library?kind=playlists");
    const answer = await resp.json().catch(() => ({}));
    if (resp.ok) return answer;
    return { error: answer.error ?? `grog answered ${resp.status}`, connect: answer.connect };
  } catch (e) {
    return { error: e.message };
  }
}

/** The music sheet stays open while touched. */
function touchedMusic() {
  later("music", SHEET_MS, closeMusic);
}

/** What to play: liked songs and the user's playlists, asked as it opens. */
async function openMusic() {
  shut();
  shutSpeakers();
  shutWifi();
  musicOpen = true;
  library = null;
  touchedMusic();
  draw();
  const asked = ++libraryAsks;
  const answer = await askLibrary();
  if (asked === libraryAsks && musicOpen) {
    library = answer;
    draw();
  }
}

function shutMusic() {
  musicOpen = false;
  later("music", null);
}

function closeMusic() {
  shutMusic();
  draw();
}

async function chooseMusic(button) {
  touchedMusic();
  button.classList.add("busy");
  const answer = await ask(button.dataset.action, null, showMusicError);
  button.classList.remove("busy");
  if (answer) closeMusic();
}

function showSpeakersError(message) {
  setText(els["speakers-error"], message);
  later("speakers-error", SHEET_ERROR_MS, () => setText(els["speakers-error"], ""));
}

/** The speakers sheet stays open while touched. */
function touchedSpeakers() {
  later("speakers", SHEET_MS, closeSpeakers);
}

/**
 * Asks grog for `action` (speakers, or picking one: see view.js request),
 * which answers which speakers there are: the sheet shows them, or why
 * grog couldn't say.
 */
async function askSpeakers(action) {
  const asked = ++speakersAsks;
  let failed = "";
  const answer = await ask(action, null, (message) => (failed = message));
  if (asked !== speakersAsks || !speakersOpen) return;
  if (answer) speakers = answer;
  else if (!speakers?.speakers) speakers = { error: failed };
  else showSpeakersError(failed);
  draw();
}

/** Which speakers there are, asked as it opens. */
function openSpeakers() {
  shut();
  shutMusic();
  shutWifi();
  speakersOpen = true;
  speakers = null;
  touchedSpeakers();
  draw();
  askSpeakers("speakers");
}

function shutSpeakers() {
  speakersOpen = false;
  later("speakers", null);
}

function closeSpeakers() {
  shutSpeakers();
  draw();
}

/** Picks a speaker: the sheet stays, showing it picked once grog did. */
async function chooseSpeaker(button) {
  touchedSpeakers();
  button.classList.add("busy");
  await askSpeakers(button.dataset.action);
  button.classList.remove("busy");
}

function showWifiError(message) {
  setText(els["wifi-error"], message);
  later("wifi-error", SHEET_ERROR_MS, () => setText(els["wifi-error"], ""));
}

/** The Wi-Fi sheet stays open while touched, and while it joins a network. */
function touchedWifi() {
  later("wifi", form?.busy || joining !== null ? null : SHEET_MS, closeWifi);
}

/**
 * Asks grog of its Wi-Fi (`req`, see view.js request and joinRequest):
 * which networks there are, the sheet shows them, or why grog couldn't
 * say; null when it failed, `failed` saying why.
 */
async function askWifi(req, failed) {
  const asked = ++wifiAsks;
  let why = "";
  let answer = null;
  try {
    const resp = await fetch(req.path, {
      method: req.method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req.body),
    });
    answer = await resp.json().catch(() => ({}));
    if (!resp.ok) {
      why = answer.error ?? `grog answered ${resp.status}`;
      answer = null;
    }
  } catch (e) {
    why = e.message;
  }
  if (asked !== wifiAsks || !wifiOpen) return null;
  if (answer) wifi = answer;
  else (failed ?? ((m) => (wifi?.networks ? showWifiError(m) : (wifi = { error: m }))))(why);
  return answer;
}

/** Which networks there are, asked as it opens. */
async function openWifi() {
  shut();
  shutMusic();
  shutSpeakers();
  wifiOpen = true;
  wifi = null;
  form = null;
  touchedWifi();
  draw();
  await askWifi(request("wifi", state));
  draw();
}

function shutWifi() {
  wifiOpen = false;
  form = null;
  joining = null;
  later("wifi", null);
}

function closeWifi() {
  shutWifi();
  draw();
}

/**
 * A network tapped: joined (saved, or open), the sheet staying while it
 * joins, or its password asked first; or looking again, or Wi-Fi turned on.
 */
async function chooseWifi(button) {
  if (joining !== null) return;
  const action = button.dataset.action;
  const network = (wifi?.networks ?? []).find((n) => `wifi-join:${n.ssid}` === action);
  if (network && needsPassword(network)) {
    form = { ssid: network.ssid, text: "", page: "letters", shift: false, shown: false };
    touchedWifi();
    draw();
    return;
  }
  button.classList.add("busy");
  if (network) joining = network.ssid;
  touchedWifi();
  draw();
  const failedToJoin = (message) => {
    // A saved password that no longer works: type it again.
    if (network.security === "password") form = { ssid: network.ssid, text: "", error: message };
    else showWifiError(message);
  };
  await askWifi(request(action, state), network ? failedToJoin : null);
  if (network && joining === network.ssid) joining = null;
  button.classList.remove("busy");
  touchedWifi();
  draw();
}

/** Tries the password typed: once joined, the networks again; if not, why, with what was typed kept. */
async function join() {
  if (!canJoin(form)) return;
  const { ssid, text } = form;
  form = { ...form, busy: true, error: "" };
  touchedWifi();
  draw();
  const answer = await askWifi(joinRequest(ssid, text), (message) => {
    if (form) form = { ...form, busy: false, error: message };
  });
  if (answer) form = null;
  touchedWifi();
  draw();
}

/** What a key of the on-screen keyboard (or a physical one: `key`) does. */
function press(key) {
  if (!form || form.busy || !key) return;
  touchedWifi();
  if (key.kind === "done") {
    join();
    return;
  }
  form = typed(form, key);
  draw();
}

async function choose(button) {
  const action = button.dataset.action;
  const step = button.dataset.step ?? null;
  if (action === "music") {
    openMusic();
    return;
  }
  if (action === "speakers") {
    openSpeakers();
    return;
  }
  if (action === "wifi") {
    openWifi();
    return;
  }
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
for (const type of ["pointerdown", "keydown", "wheel"]) {
  document.addEventListener(type, touchedScreen, { capture: true, passive: true });
}
// Touched while it was off: the kiosk turned it on, keeping that touch from
// the page, and says so.
window.addEventListener("screen-touched", touchedScreen);
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
// Words take more room once their fonts are in, each loading once something
// first needs it (a turn's times, say).
document.fonts.addEventListener("loadingdone", () => {
  if (following) els.chat.scrollTop = els.chat.scrollHeight;
});
// What shows under or over the chat comes and goes (the music's bar, the
// steps, a card): it keeps to its end, its box smaller or bigger.
new ResizeObserver(() => {
  if (following) els.chat.scrollTop = els.chat.scrollHeight;
}).observe(els.chat);
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
// Allowing what asks to connect, or not, or no longer connecting it.
els["card-buttons"].addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button) ask(button.dataset.action, null, showCardError);
});
// The music: its buttons, and choosing what plays.
els["open-music"].innerHTML = icon("list");
els["music-down"].innerHTML = icon("volume-down");
els["music-up"].innerHTML = icon("volume-up");
els["close-music"].innerHTML = icon("close");
els["open-music"].addEventListener("click", openMusic);
els.player.addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button) ask(button.dataset.action, null, showPlayerError);
});
els["close-music"].addEventListener("click", closeMusic);
els["music-sheet"].addEventListener("scroll", () => musicOpen && touchedMusic(), { passive: true });
els["music-items"].addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button) chooseMusic(button);
});
// The speakers: which the music and grog's voice play on.
els["close-speakers"].innerHTML = icon("close");
els["close-speakers"].addEventListener("click", closeSpeakers);
els["speakers-sheet"].addEventListener("scroll", () => speakersOpen && touchedSpeakers(), { passive: true });
els["speakers-items"].addEventListener("click", (e) => {
  const button = e.target.closest("button[data-action]");
  if (button && !button.disabled) chooseSpeaker(button);
});
// The Wi-Fi: its networks, and the password typed for one.
els["close-wifi"].innerHTML = icon("close");
els["close-wifi"].addEventListener("click", closeWifi);
els["wifi-sheet"].addEventListener("scroll", () => wifiOpen && touchedWifi(), { passive: true });
els["wifi-items"].addEventListener("click", (e) => {
  const key = e.target.closest("button[data-row]");
  if (key && form) {
    press(keyboard(form.page, form.shift)[Number(key.dataset.row)]?.[Number(key.dataset.col)]);
    return;
  }
  const what = e.target.closest("button[data-wifi]")?.dataset.wifi;
  if (what && form && !form.busy) {
    touchedWifi();
    form = what === "show" ? { ...form, shown: !form.shown } : null;
    draw();
    return;
  }
  const button = e.target.closest("button[data-action]");
  if (button && !button.disabled) chooseWifi(button);
});
document.addEventListener("keydown", (e) => {
  // Typing the password on a keyboard of its own, the Wi-Fi sheet's is there too.
  if (wifiOpen && form && !form.busy && !e.ctrlKey && !e.altKey && !e.metaKey) {
    const key = physicalKey(e.key);
    if (key) {
      e.preventDefault();
      press(key);
      return;
    }
    if (e.key === "Escape") {
      form = null;
      draw();
      return;
    }
  }
  if (e.key === "Escape" && sheetOpen) closeSheet();
  if (e.key === "Escape" && musicOpen) closeMusic();
  if (e.key === "Escape" && speakersOpen) closeSpeakers();
  if (e.key === "Escape" && wifiOpen) closeWifi();
});
els["card-qr"].addEventListener("load", () => {
  els["card-qr"].hidden = false;
});
draw();
connect();
// The clock, countdowns, and what times out (errors, what was heard).
setInterval(draw, 1000);
setInterval(drift, DRIFT_MS);

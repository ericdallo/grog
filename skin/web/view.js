// What the skin shows, out of grog's state (GET /v1/events, see
// brain/src/grog/events.clj) and the time: pure functions, tested with
// `node --test`. Times are epoch milliseconds, but the usage's (seconds).

// grog-voice says what it's doing at least every 10 s.
export const VOICE_STALE_MS = 35_000;
// How long an error shows.
export const ERROR_MS = 6_000;
// How long what grog-voice heard for grog shows, without a turn for it.
export const CAPTIONS_MS = 20_000;
// Scrolled back, the chat goes back to its end once left alone this long.
export const FOLLOW_MS = 30_000;
// The steps of the last turn fade after this long.
export const STALE_MS = 45_000;
// Right after grog starts, a voice that hasn't said anything yet may be on its
// way.
export const VOICE_GRACE_MS = 60_000;
// Thinking this long, it's still thinking.
export const STILL_MS = 8_000;
// How long grog is pleased once a turn went well.
export const PLEASED_MS = 2_500;
// Nobody talked to grog for this long: it dozes.
export const DOZING_MS = 30 * 60_000;
// Night, in grog's time zone: from 22:00 to 7:00.
const NIGHT_FROM = 22;
const NIGHT_TO = 7;
// Once grog said it's going away (asked to restart, say), the skin says so
// for this long while it can't reach it.
export const GOING_MS = 3 * 60_000;
// What can't be undone (restarting, signing out) takes a second tap within
// this long.
export const CONFIRM_MS = 4_000;
// How much a tap on the volume's buttons changes it, in points.
export const VOLUME_STEP = 10;
// How much a tap on the music's volume buttons changes it, in points.
export const MUSIC_STEP = 10;
// An update says how it goes as it goes: silent this long, it's gone.
export const UPDATE_MS = 30 * 60_000;
// How long how it went shows, once it's over; failing, longer.
export const UPDATED_MS = 20_000;
export const UPDATE_FAILED_MS = 60_000;
// How long how connecting something went shows, once it's over.
export const OUTCOME_MS = 10_000;
// Once getting a code to sign in with failed, grog tries again in a minute
// (brain/src/grog/signin.clj).
export const RETRY_MS = 60_000;

// Each tool grog has: what it's doing with it (label), what it did (done).
const TOOLS = {
  computer: { label: "Using a computer", done: "Used a computer", short: "Computer", icon: "computer" },
  connect: { label: "Opening a connection", done: "Opened a connection", short: "Connect", icon: "key" },
  update: { label: "Looking for an update", done: "Looked for an update", short: "Update", icon: "update" },
  web_search: { label: "Searching the web", done: "Searched the web", short: "Web", icon: "web" },
  volume: { label: "Adjusting the volume", done: "Adjusted the volume", short: "Volume", icon: "volume" },
  system_status: { label: "Checking the machine", done: "Checked the machine", short: "Machine", icon: "machine" },
  shell: { label: "Running a command", done: "Ran a command", short: "Shell", icon: "shell" },
  memory: { label: "Remembering", done: "Remembered", short: "Memory", icon: "memory" },
  settings: { label: "Changing its settings", done: "Changed its settings", short: "Settings", icon: "settings" },
  microphone: { label: "Switching its mic", done: "Switched its mic", short: "Mic", icon: "no-mic" },
  power: { label: "Getting ready to restart", done: "Got ready to restart", short: "Power", icon: "power" },
  lights: { label: "Seeing to the lights", done: "Saw to the lights", short: "Lights", icon: "bulb" },
  music: { label: "Seeing to the music", done: "Saw to the music", short: "Music", icon: "music" },
};

// What grog says going away (POST /v1/actions/<action>) is about.
const GOING = {
  restart: { label: "Restarting", detail: "back in a moment" },
  reboot: { label: "Restarting the machine", detail: "back in a minute or two" },
  "power-off": { label: "Turning off", detail: "someone has to turn it on again" },
  update: { label: "Updating", detail: "back in a moment" },
};

// What an update under way is doing (brain/src/grog/updater.clj), and once
// it switches, grog restarts into the new version.
const UPDATING = {
  fetching: "Looking for an update",
  building: "Building the update",
  waiting: "Updating once nobody's talking",
  switching: "Restarting into the update",
  verifying: "Restarting into the update",
};
const SWITCHING = ["switching", "verifying"];

// What grog-voice says while it waits for someone to talk.
const WAITING = ["listening", "awake", "asked", "error"];

const MUTED = { mode: "muted", label: "Mic off", detail: "tap to turn it on" };

/** `s` with its first letter in upper case. */
export function capitalize(s) {
  const str = String(s ?? "");
  return str.charAt(0).toUpperCase() + str.slice(1);
}

// How long an MCP server's tool label gets with its server's prefix.
const CONTEXT_MAX = 32;

/** An MCP tool's name in words: "create_issue" or "createIssue" is "Create issue". */
function words(name) {
  const spaced = String(name)
    .replace(/([a-z0-9])([A-Z])(?=[a-z])/g, (_, a, b) => `${a} ${b.toLowerCase()}`)
    .replace(/[_.\-\s]+/g, " ")
    .trim();
  return capitalize(spaced || "tool");
}

/**
 * A tool by its name: {label, done, short, icon}. An MCP server's tools
 * are named <prefix>__<tool> (brain/src/grog/mcp_tools.clj): the tool in
 * words, with its prefix when there's room ("Create issue · github").
 */
export function tool(name) {
  if (TOOLS[name]) return TOOLS[name];
  const str = String(name ?? "tool");
  const at = str.indexOf("__");
  if (at > 0 && at + 2 < str.length) {
    const short = words(str.slice(at + 2));
    const prefix = str.slice(0, at);
    const label = short.length + prefix.length + 3 <= CONTEXT_MAX ? `${short} · ${prefix}` : short;
    return { label, done: label, short, icon: "tool" };
  }
  const label = capitalize(str.replaceAll("_", " "));
  return { label, done: label, short: label, icon: "tool" };
}

/** What grog-voice is doing, or null: it never said, or not lately. */
export function voiceState(state, now) {
  const voice = state?.voice;
  if (!voice || now - voice.seen > VOICE_STALE_MS) return null;
  return voice.state;
}

/** The turn under way, or null. */
export function activeTurn(state) {
  const turn = state?.turn;
  return turn && !turn.doneAt ? turn : null;
}

function lastStep(turn) {
  return turn.steps?.[turn.steps.length - 1] ?? {};
}

/** Whether the last turn failed (not stopped), or grog-voice had an error, just now. */
function failed(state, now) {
  const turn = state.turn;
  const voice = state.voice;
  return Boolean(
    (turn?.doneAt && turn.ok === false && !turn.stopped && now - turn.doneAt < ERROR_MS) ||
      (voice?.errorAt && now - voice.errorAt < ERROR_MS),
  );
}

/**
 * What grog said it's going away for (asked to restart, say, or switching
 * to an update), lately: {label, detail}, or null. It says so even once it
 * can't be reached.
 */
export function goingAway(state, now) {
  const going = state?.restarting;
  if (going?.action && now - going.at <= GOING_MS) return GOING[going.action] ?? GOING.restart;
  const update = state?.update;
  if (SWITCHING.includes(update?.stage) && now - update.at <= GOING_MS) return GOING.update;
  return null;
}

/** A revision as short as git shows it, still saying when it's dirty; or null. */
export function shortRevision(rev) {
  if (!rev) return null;
  const s = String(rev);
  return s.length > 7 && s.endsWith("-dirty") ? `${s.slice(0, 7)}-dirty` : s.slice(0, 7);
}

/** The revision of grog running, short, or null when it doesn't know. */
export function version(state) {
  return shortRevision(state?.revision);
}

/**
 * How grog's update goes, for a line on the screen: {label, detail, icon,
 * failed}, while one is under way and for a while once it's over; null
 * otherwise.
 */
export function updateNotice(state, now) {
  const u = state?.update;
  if (!u?.stage || !Number.isFinite(u.at)) return null;
  const age = now - u.at;
  const to = shortRevision(u.to) ?? "";
  if (UPDATING[u.stage]) {
    return age <= UPDATE_MS ? { label: UPDATING[u.stage], detail: to, icon: "update", failed: false } : null;
  }
  switch (u.stage) {
    case "done":
      return age <= UPDATED_MS ? { label: to ? "Updated to" : "Updated", detail: to, icon: "done", failed: false } : null;
    case "current":
      return age <= UPDATED_MS ? { label: "Already up to date", detail: to, icon: "done", failed: false } : null;
    case "failed":
      return age <= UPDATE_FAILED_MS ? { label: "Couldn't update", detail: u.error ?? "", icon: "failed", failed: true } : null;
    default:
      return null;
  }
}

/**
 * Whether grog-voice is still saying the last turn's answer: it has been
 * answering since before that turn ended, in its conversation.
 */
function saying(state) {
  const { turn, voice } = state;
  return Boolean(turn?.doneAt && turn.conversation === voice.conversation && voice.since <= turn.doneAt);
}

/**
 * What grog is doing now, for its block of color and the words in it:
 * {mode, label, detail, icon}. `offline`: grog can't be reached, `state`
 * being the last it said.
 */
export function mode(state, now, offline = false) {
  const going = goingAway(state, now);
  if (going) return { mode: "restarting", ...going };
  if (!state || offline) return { mode: "offline", label: "Offline", detail: "waiting for grog" };
  const voice = voiceState(state, now);
  const turn = activeTurn(state);
  if (turn) {
    const step = lastStep(turn);
    if (step.step === "tool") {
      const t = tool(step.name);
      return { mode: "tool", label: t.label, detail: "", icon: t.icon };
    }
    if (step.step === "answering" || step.step === "speaking") {
      return voice === "speaking"
        ? { mode: "speaking", label: "Speaking", detail: "" }
        : { mode: "answering", label: "Answering", detail: "" };
    }
    return now - step.at >= STILL_MS
      ? { mode: "still", label: "Still thinking", detail: "" }
      : { mode: "thinking", label: "Thinking", detail: "" };
  }
  if (voice === "speaking") return { mode: "speaking", label: "Speaking", detail: "" };
  if (voice === "answering") {
    // Before its turn starts, grog thinks; after it ends, grog-voice gets its
    // answer ready to say.
    return saying(state)
      ? { mode: "answering", label: "Answering", detail: "" }
      : { mode: "thinking", label: "Thinking", detail: "" };
  }
  if (failed(state, now)) {
    return { mode: "error", label: "Something went wrong", detail: state.turn?.error ?? "it couldn't answer" };
  }
  // Switched off, which grog-voice may not have said yet.
  if (voice === "muted" || (state.mic === "off" && WAITING.includes(voice))) return MUTED;
  const name = state.name ?? "";
  const waking = { mode: "loading", label: "Waking up", detail: "loading its ears and voice" };
  switch (voice) {
    case "hearing":
      return { mode: "hearing", label: "Hearing you", detail: "" };
    case "awake":
      return { mode: "awake", label: "Go ahead", detail: "listening, no need to call" };
    case "asked":
      return { mode: "asked", label: "Your turn", detail: "it asked something: just answer" };
    case "loading":
      return waking;
    case "no-mic":
      return { mode: "no-mic", label: "No microphone", detail: "plug one in" };
  }
  if (state.auth === "none") {
    const signing = state.connect?.status === "open" && state.connect.integration === state.provider;
    return { mode: "login", label: "Needs a login", detail: signing ? "sign in from your phone" : "grog login, or an API key" };
  }
  if (voice === "listening" || voice === "error") {
    return { mode: "idle", label: "Listening", detail: name ? `say “${name}”` : "" };
  }
  // A voice that stopped saying...
  if (state.voice) return { mode: "deaf", label: "Can't hear", detail: "its voice isn't running" };
  // ...or none yet, right after grog started: it may be on its way. Or there's none.
  return now - state.startedAt < VOICE_GRACE_MS ? waking : { mode: "idle", label: "Ready", detail: "" };
}

/**
 * grog's face for what it's doing (a mode()): {state, icon}, its state one of
 * its poses (skin.css), its pupil turned into `icon` when there's one.
 * Listening, it's pleased just after a turn that went well, dozes once nobody
 * talked to it for a long while, and is sleepy at `night`.
 */
export function face(m, state, now, night = false) {
  switch (m?.mode) {
    case "idle":
      return { state: resting(state, now, night), icon: null };
    case "answering":
      return { state: "writing", icon: null };
    case "tool":
      return { state: "tool", icon: m.icon ?? "tool" };
    case "no-mic":
      return { state: "no-mic", icon: "no-mic" };
    case "muted":
      return { state: "muted", icon: "no-mic" };
    case "login":
      return { state: "login", icon: "key" };
    case "restarting":
      return { state: "offline", icon: null };
    default:
      return { state: m?.mode ?? "offline", icon: null };
  }
}

function resting(state, now, night) {
  if (pleased(state, now)) return "pleased";
  const since = idleSince(state);
  if (since !== null && now - since >= DOZING_MS) return "dozing";
  return night ? "sleepy" : "idle";
}

/**
 * Whether the last turn went well and is over just now: said, when
 * grog-voice said it, or else done.
 */
function pleased(state, now) {
  const turn = state?.turn;
  if (!turn?.doneAt || turn.ok === false) return false;
  const end = turn.steps?.some((s) => s.step === "speaking") ? turn.spokenAt : turn.doneAt;
  return now >= end && now - end < PLEASED_MS;
}

/** Since when nobody talked to grog, as far as it knows, or null. */
function idleSince(state) {
  const turn = state?.turn;
  const times = [state?.startedAt, turn?.doneAt, turn?.spokenAt, state?.voice?.since].filter(Number.isFinite);
  return times.length ? Math.max(...times) : null;
}

// What grog-voice says while someone talks to grog, its name heard
// (voice/grog_voice/reporter.py).
const TALKING = ["hearing", "awake", "asked", "answering", "speaking"];

/**
 * Whether the screen showing the skin is on: while someone talks to grog, a
 * card waits for someone, or grog is going away; and for
 * `state.screenOffMinutes` (the `skin.screenOffMinutes` setting) after
 * anything else happened (see happened()), someone touching the screen
 * (`touchedAt`) included. Off otherwise, to save energy: the kiosk turns it
 * off. On for good without the setting, and before grog said anything.
 */
export function screenOn(state, now, touchedAt = 0) {
  const minutes = state?.screenOffMinutes;
  if (!state || !Number.isFinite(minutes) || minutes <= 0) return true;
  if (TALKING.includes(voiceState(state, now)) || waiting(state, now) || goingAway(state, now)) return true;
  return now - happened(state, touchedAt) < minutes * 60_000;
}

/** Whether a card waits for someone: a session open, but the one grog keeps up itself to sign in. */
function waiting(state, now) {
  const c = state.connect;
  return c?.status === "open" && !c.kept && (c.until ?? 0) > now;
}

/**
 * When something last happened, as `state` says, or someone touched the
 * screen (`touchedAt`): grog started, a turn went on, grog-voice changed
 * what it does or heard something for grog, connecting something went on,
 * an update did, grog said it's going away. Not grog's music playing, nor
 * the codes grog renews by itself to sign in with, until someone does.
 */
function happened(state, touchedAt) {
  const { turn, connect: c } = state;
  const times = [
    state.startedAt,
    touchedAt,
    turn && lastStep(turn).at,
    turn?.doneAt,
    turn?.spokenAt,
    state.voice?.since,
    state.heard?.at,
    c?.kept && c.status !== "done" ? null : c?.at,
    state.update?.at,
    state.restarting?.at,
  ];
  return Math.max(0, ...times.filter(Number.isFinite));
}

// Faces with the eye closed: asleep, or waking up.
const SHUT = ["offline", "loading"];
// Called, or waiting for what's said next...
const CALLED = ["hearing", "awake", "asked"];
// ...and back to rest without a turn: for nothing.
const RESTING = ["idle", "sleepy", "dozing"];
// What comes once it got what was said.
const ON_IT = ["thinking", "still", "writing", "speaking"];

/**
 * What the face does on the way from state `from` to `to` (face()'s), their
 * pupils turned into `fromIcon` and `toIcon`: wake (its eye opens), swap (its
 * pupil and an icon change behind a blink), pop (called), flinch (it failed),
 * hop (it went well), nod (it got what was said), never-mind (called for
 * nothing), or null.
 */
export function reaction(from, to, fromIcon = null, toIcon = null) {
  if (!from || (from === to && fromIcon === toIcon)) return null;
  if (SHUT.includes(to)) return null;
  if (SHUT.includes(from)) return "wake";
  if (fromIcon !== toIcon) return "swap";
  if (to === "awake") return "pop";
  if (to === "error") return "flinch";
  if (to === "pleased") return "hop";
  if (from === "hearing" && ON_IT.includes(to)) return "nod";
  if (CALLED.includes(from) && RESTING.includes(to)) return "never-mind";
  return null;
}

/** Whether `hour` (0 to 23, in grog's time zone) is at night. */
export function isNight(hour) {
  return hour >= NIGHT_FROM || hour < NIGHT_TO;
}

/** Seconds, as the skin says them: 1.8 s. */
export function seconds(ms) {
  return `${(Math.max(0, ms) / 1000).toFixed(1)} s`;
}

/** How long the turn under way has taken so far, or "" without one. */
export function elapsed(state, now) {
  const start = activeTurn(state)?.steps?.[0]?.at;
  return start === undefined ? "" : seconds(now - start);
}

/** Whether the last turn is over, long enough ago for its steps to fade. */
export function stale(state, now) {
  const turn = state?.turn;
  return Boolean(turn?.doneAt && now - turn.doneAt > STALE_MS);
}

const STEPS = {
  heard: { label: "Heard", icon: "voice" },
  thinking: { label: "Thinking", icon: "thinking" },
  answering: { label: "Answering", icon: "answering" },
  speaking: { label: "Speaking", icon: "speaking" },
};

/**
 * The steps of the current or last turn, each {label, icon, offset: since
 * the first, status: done, active or failed}, ending with how it went once
 * it's over. What it says before using a tool ("Let me check...") isn't a
 * step of its own. `offline`: grog isn't there to say, so nothing is active.
 */
export function pipeline(turn, offline = false) {
  const all = turn?.steps ?? [];
  const steps = all.filter((s, i) => !(s.step === "answering" && all[i + 1]?.step === "tool"));
  if (!steps.length) return [];
  const start = steps[0].at;
  const out = steps.map((s, i) => {
    const kind = s.step === "tool" ? tool(s.name) : (STEPS[s.step] ?? { label: s.step, icon: "tool" });
    return {
      label: s.step === "tool" ? kind.short : kind.label,
      icon: kind.icon,
      offset: `+${seconds(s.at - start)}`,
      status: !turn.doneAt && !offline && i === steps.length - 1 ? "active" : "done",
    };
  });
  if (turn.doneAt) {
    // grog-voice may start speaking once grog is done.
    const end = Math.max(turn.doneAt, steps[steps.length - 1].at);
    const how = turn.stopped
      ? { label: "Stopped", icon: "stop", status: "done" }
      : turn.ok === false
        ? { label: "Failed", icon: "failed", status: "failed" }
        : { label: "Done", icon: "done", status: "done" };
    out.push({ label: how.label, icon: how.icon, offset: seconds(end - start), status: how.status });
  }
  return out;
}

/** A turn (grog's state's turn, or one of its history), for the chat: see chat(). */
function chatTurn(turn, captions, offline) {
  const items = (turn.parts ?? []).flatMap((p) => {
    if (p.tool) {
      const kind = tool(p.tool);
      const running = !p.doneAt && !turn.doneAt && !offline;
      const detail = captions ? (p.detail ?? "") : "";
      return [{ kind: "tool", icon: kind.icon, label: running ? kind.label : kind.done, detail, running }];
    }
    const text = captions ? String(p.text ?? "").trim() : "";
    return text ? [{ kind: "text", text }] : [];
  });
  const start = turn.steps?.[0]?.at ?? turn.doneAt ?? 0;
  const status = turn.stopped ? "stopped" : turn.ok === false ? "failed" : "done";
  return {
    key: `${turn.conversation}:${turn.n}`,
    at: start,
    said: captions ? (turn.said ?? turn.question ?? "") : "",
    live: false,
    items,
    end: turn.doneAt
      ? { status, took: seconds(turn.doneAt - start), error: captions && status === "failed" ? (turn.error ?? "") : "" }
      : null,
  };
}

/**
 * What was said and done, oldest first: the turns before the last
 * (`history`, as grog last sent it), the last, and after it, what
 * grog-voice hears for grog as it comes, for a while. Each {key, at, said,
 * live, items, end}: `said`, what was said for it (as heard, or typed);
 * `items`, its answer in order, {kind: "text", text} and {kind: "tool", icon,
 * label, detail, running}; `end`, once it's over, {status: done, failed or
 * stopped, took, error}; `live`, what's heard isn't all there yet. What was
 * said (said, text, details, errors) only shows with captions. `offline`:
 * grog isn't there to say, so nothing runs.
 */
export function chat(state, history = [], now = 0, offline = false) {
  if (!state) return [];
  const { turn, heard, captions } = state;
  const turns = [...(history ?? []), ...(turn ? [turn] : [])].map((t) => chatTurn(t, captions, offline));
  const between = !turn || (turn.doneAt && heard?.at > turn.doneAt);
  if (captions && heard?.text && between && now - heard.at < CAPTIONS_MS) {
    turns.push({ key: "heard", at: heard.at, said: heard.text, live: !heard.final, items: [], end: null });
  }
  return turns;
}

/**
 * How to go from the words `shown` to those of `text`, a word at a time:
 * {words: [{word, change}], gone}, change being stay (already there),
 * replace (heard better now) or append; `gone`: how many of the last shown
 * words go.
 */
export function wordChanges(shown, text) {
  const next = String(text ?? "").split(/\s+/).filter(Boolean);
  return {
    words: next.map((word, i) => ({
      word,
      change: shown[i] === word ? "stay" : i < shown.length ? "replace" : "append",
    })),
    gone: Math.max(0, shown.length - next.length),
  };
}

/**
 * What touching grog's face does, by what it's doing: {action, label}, the
 * action one of grog's (POST /v1/actions/<action>): to listen without its
 * name (its mic off, grog turns it on first), or to stop answering. null
 * when it does nothing: grog, or its voice, isn't there or ready.
 */
export function tap(state, now, offline = false) {
  if (!state || offline || goingAway(state, now)) return null;
  const voice = voiceState(state, now);
  const can = (action) => (state.actions ?? []).includes(action);
  if (!voice || voice === "loading" || voice === "no-mic") return null;
  if (voice === "answering" || voice === "speaking") return can("stop") ? { action: "stop", label: "Stop" } : null;
  if (!can("listen")) return null;
  if (voice === "muted" || state.mic === "off") return { action: "listen", label: "Turn the mic on and listen" };
  return { action: "listen", label: state.name ? `Talk to ${state.name}` : "Talk" };
}

/**
 * What can be connected to grog, and what is (brain/src/grog/integrations.clj),
 * for the actions sheet: each integration connects (connect:<id>), a single
 * connection disconnects (disconnect:<id>, a second tap), and what can have
 * several lists each, with its own.
 */
/**
 * What integration `i` is, under its name: what it's for, and for MCP
 * servers whether the user added it (grog didn't check it) and whether it's
 * in the cloud.
 */
export function integrationDetail(i) {
  if (!i?.mcp) return i?.about ?? "";
  const about = i.added ? "added by you, not checked by grog" : (i.about ?? "");
  return [about, i.cloud ? "in the cloud" : ""].filter(Boolean).join(" · ");
}

/** What connection `c` is: its name, and what it has ("12 tools"). */
function connectionDetail(c, fallback) {
  return [c.name, c.detail].filter(Boolean).join(" · ") || fallback;
}

function integrationItems(integrations) {
  return (integrations ?? []).flatMap((i) => {
    const icon = i.icon ?? "tool";
    const [one] = i.connections ?? [];
    const disconnect = (c, label, detail) => ({
      id: `disconnect:${c.id}`,
      kind: "button",
      label,
      icon,
      detail,
      confirm: "Tap again to disconnect",
    });
    if (i.multiple) {
      return [
        { id: `connect:${i.id}`, kind: "button", label: i.heading ?? i.title, icon, detail: integrationDetail(i) },
        ...(i.connections ?? []).map((c) => disconnect(c, c.name ?? c.id, c.detail ?? "")),
      ];
    }
    if (one?.status === "connected") return [disconnect(one, i.title, connectionDetail(one, "connected"))];
    const detail = one ? "needs signing in again" : integrationDetail(i);
    return [{ id: `connect:${i.id}`, kind: "button", label: i.heading ?? i.title, icon, detail }];
  });
}

/**
 * What the actions sheet lists, as grog's `state` allows (its actions, and
 * what can be connected to it), in order: {id, kind, group, label, icon,
 * detail, confirm, armed}. kind: volume ({level, muted}, null levels while
 * unknown), toggle ({on}), or button; group: everyday, integrations, or
 * power; `confirm`: it can't be undone, and takes a second tap: `armed`,
 * the one waiting for it, asks for it.
 */
export function menu(state, armed = null) {
  const can = (action) => (state?.actions ?? []).includes(action);
  const items = [];
  const everyday = (item) => items.push({ group: "everyday", ...item });
  const power = (item) => items.push({ group: "power", ...item });
  if (can("volume")) {
    const volume = state.volume ?? {};
    everyday({ id: "volume", kind: "volume", label: "Volume", level: volume.level ?? null, muted: Boolean(volume.muted) });
  }
  if (can("mic")) everyday({ id: "mic", kind: "toggle", label: "Microphone", icon: "voice", on: state.mic !== "off" });
  // Which ones the music and its voice play on (speakersSheet()).
  if (can("speakers")) {
    const picked = state.speaker?.label;
    const detail = picked ? `${voiceOf(state)} on ${picked}` : "where the music and the voice play";
    everyday({ id: "speakers", kind: "button", label: "Speakers", icon: "speaker", detail });
  }
  // The networks it sees, and joining one (wifiSheet()).
  if (can("wifi")) everyday({ id: "wifi", kind: "button", label: "Wi-Fi", icon: "wifi", detail: "the network it's on, and joining another" });
  if (can("new-conversation")) {
    everyday({ id: "new-conversation", kind: "button", label: "New conversation", icon: "answering", detail: "what was said is forgotten" });
  }
  // grog's player answers: what it plays can be chosen (musicSheet()).
  if (state?.music) everyday({ id: "music", kind: "button", label: "Music", icon: "music", detail: "your liked songs and playlists" });
  if (can("connect")) items.push(...integrationItems(state.integrations).map((item) => ({ group: "integrations", ...item })));
  if (can("update")) {
    const label = state.name ? `Update ${state.name}` : "Update";
    const detail = "to the latest version pushed";
    power({ id: "update", kind: "button", label, icon: "update", detail, confirm: "Tap again to update" });
  }
  if (can("restart")) {
    const label = state.name ? `Restart ${state.name}` : "Restart";
    power({ id: "restart", kind: "button", label, icon: "restart", confirm: "Tap again to restart" });
  }
  if (can("reboot")) {
    power({ id: "reboot", kind: "button", label: "Restart the machine", icon: "reboot", confirm: "Tap again to restart it" });
  }
  if (can("power-off")) {
    power({
      id: "power-off",
      kind: "button",
      label: "Turn the machine off",
      icon: "power",
      detail: "someone has to turn it on again",
      confirm: "Tap again to turn it off",
    });
  }
  return items.map(({ confirm, ...item }) => ({
    ...item,
    confirm: Boolean(confirm),
    armed: item.id === armed && Boolean(confirm),
    ...(item.id === armed && confirm ? { label: confirm } : {}),
  }));
}

/**
 * The request asking grog for `action` (an item of menu(), a button of
 * card(), or what tap() says): {method, path, body}. For the volume, `step`
 * is -1 or 1 (quieter or louder), "mute" (toggles it), or nothing (to tell
 * it).
 */
export function request(action, state, step = null) {
  if (action.startsWith("connect:")) {
    return { method: "POST", path: "/v1/actions/connect", body: { integration: action.slice("connect:".length) } };
  }
  if (action.startsWith("disconnect:")) {
    return { method: "DELETE", path: `/v1/connections/${action.slice("disconnect:".length)}`, body: {} };
  }
  if (action.startsWith("music-play:")) {
    const [kind, ...id] = action.slice("music-play:".length).split(":");
    return { method: "POST", path: "/v1/music", body: { action: "play", kind, id: id.join(":") } };
  }
  if (action.startsWith("speaker-music:")) {
    return { method: "POST", path: "/v1/actions/speakers", body: { music: action.slice("speaker-music:".length) } };
  }
  if (action.startsWith("speaker-voice:")) {
    return { method: "POST", path: "/v1/actions/speakers", body: { voice: action.slice("speaker-voice:".length) || null } };
  }
  if (action.startsWith("wifi-join:")) return joinRequest(action.slice("wifi-join:".length));
  if (action === "wifi-rescan") return { method: "POST", path: "/v1/actions/wifi", body: { rescan: true } };
  if (action === "wifi-on") return { method: "POST", path: "/v1/actions/wifi", body: { on: true } };
  if (MUSIC[action]) return { method: "POST", path: "/v1/music", body: MUSIC[action] };
  switch (action) {
    case "volume": {
      const body = step === "mute" ? { mute: !state?.volume?.muted } : step ? { change: step * VOLUME_STEP } : {};
      return { method: "POST", path: "/v1/actions/volume", body };
    }
    case "mic":
      return { method: "POST", path: "/v1/actions/mic", body: { on: state?.mic === "off" } };
    case "connect-allow":
      return { method: "POST", path: "/v1/connect/allow", body: {} };
    case "connect-deny":
      return { method: "POST", path: "/v1/connect/deny", body: {} };
    case "connect-cancel":
      return { method: "DELETE", path: "/v1/connect", body: {} };
    default:
      return { method: "POST", path: `/v1/actions/${action}`, body: {} };
  }
}

/** Whether `action` (an item of menu()) closes the sheet once grog took it. */
export function closes(action) {
  return !["volume", "mic"].includes(action);
}

// What the music's buttons ask of grog (POST /v1/music, see
// brain/src/grog/music.clj); playing one of the user's playlists is
// music-play:<kind>:<id>.
const MUSIC = {
  "music-previous": { action: "previous" },
  "music-pause": { action: "pause" },
  "music-resume": { action: "resume" },
  "music-next": { action: "next" },
  "music-down": { action: "volume", change: -MUSIC_STEP },
  "music-up": { action: "volume", change: MUSIC_STEP },
  "music-liked": { action: "play", kind: "liked" },
  "music-shuffle": { action: "play", kind: "liked", shuffle: true },
};

/** `s` seconds as a song's time: 3:05, or 1:02:03. */
export function songTime(s) {
  const total = Math.max(0, Math.floor(s));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const sec = String(total % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

/** `s` without spaces around it, or "" when it isn't a string. */
function trimmed(s) {
  return typeof s === "string" ? s.trim() : "";
}

/**
 * What grog's player plays (state.music, see brain/src/grog/events.clj),
 * for the bar under what was said and done, while it plays or is paused:
 * {title, artist, time, playing, volume, buttons: [{action, icon, label}]}.
 * Its time goes on from where the song was when grog last said (its `at`)
 * while it plays, and stays while it's paused. null while nothing plays,
 * without a player, or grog isn't there to say.
 */
export function nowPlaying(state, now, offline = false) {
  const m = state?.music;
  if (offline || !m || !["playing", "paused"].includes(m.state)) return null;
  const playing = m.state === "playing";
  const duration = Number.isFinite(m.durationS) && m.durationS > 0 ? m.durationS : null;
  let position = Number.isFinite(m.positionS) ? m.positionS : null;
  if (position !== null && playing && Number.isFinite(m.at)) position += Math.max(0, now - m.at) / 1000;
  if (position !== null && duration !== null) position = Math.min(position, duration);
  const time =
    position === null ? "" : duration === null ? songTime(position) : `${songTime(position)} / ${songTime(duration)}`;
  return {
    title: trimmed(m.title) || trimmed(m.album) || "Untitled",
    artist: trimmed(m.artist),
    time,
    playing,
    volume: Number.isFinite(m.volume) ? Math.round(m.volume) : null,
    buttons: [
      { action: "music-previous", icon: "previous", label: "Previous" },
      playing
        ? { action: "music-pause", icon: "pause", label: "Pause" }
        : { action: "music-resume", icon: "play", label: "Play" },
      { action: "music-next", icon: "next", label: "Next" },
    ],
  };
}

/** How many songs a playlist has, in words, or "" when it isn't known. */
function songs(count) {
  if (!Number.isFinite(count)) return "";
  return count === 1 ? "1 song" : `${count} songs`;
}

/**
 * What to play, for the music sheet, out of what grog answered `GET
 * /v1/music/library?kind=playlists` (`library`: {items}, or {error,
 * connect} when it couldn't; null while it's on its way), and what can be
 * connected to it (`state`'s integrations): {loading, items: [{id, label,
 * icon, detail}], message, error}. Liked songs first, then the user's
 * playlists, each a request() (music-play:my-playlist:<id>: played with
 * their account, as they may be private). Without the account, why, and
 * connecting it instead.
 */
export function musicSheet(library, state = null) {
  const sheet = { loading: false, items: [], message: "", error: "" };
  if (!library) return { ...sheet, loading: true, message: "Loading…" };
  if (library.connect) {
    const integration = (state?.integrations ?? []).find((i) => i.id === library.connect);
    const item = {
      id: `connect:${library.connect}`,
      label: integration?.heading ?? integration?.title ?? "Connect it",
      icon: integration?.icon ?? "music",
      detail: integration?.about ?? "",
    };
    return { ...sheet, items: [item], message: capitalize(library.error ?? "") };
  }
  const liked = [
    { id: "music-liked", label: "Liked songs", icon: "heart", detail: "" },
    { id: "music-shuffle", label: "Liked songs, shuffled", icon: "shuffle", detail: "" },
  ];
  if (library.error) return { ...sheet, items: liked, error: library.error };
  const playlists = (library.items ?? [])
    .filter((p) => p?.id && p.title)
    .map((p) => ({ id: `music-play:my-playlist:${p.id}`, label: p.title, icon: "list", detail: songs(p.count) }));
  return { ...sheet, items: [...liked, ...playlists], message: playlists.length ? "" : "No playlists of your own yet" };
}

// Each kind of speaker grog-voice tells (voice/grog_voice/speakers.py): its
// icon, and what it is.
const SPEAKER_KINDS = {
  bluetooth: { icon: "bluetooth", detail: "Bluetooth" },
  usb: { icon: "speaker", detail: "USB" },
  hdmi: { icon: "screen", detail: "HDMI" },
  headphones: { icon: "headphones", detail: "Headphones" },
  speaker: { icon: "speaker", detail: "Built in" },
};

/** "Grog's voice", by grog's name, or "Its voice". */
function voiceOf(state) {
  return state?.name ? `${state.name}'s voice` : "Its voice";
}

/**
 * Which speakers the music and grog's voice play on, out of what grog
 * answered `POST /v1/actions/speakers` (`answer`: {speakers, voice}, or
 * {error} when it couldn't; null while it's on its way): {loading, groups:
 * [{title, items: [{id, label, icon, detail, chosen, disabled}]}], message,
 * error}. The music's: each speaker, the default one chosen. The voice's:
 * with the music (its own), then each speaker, the one picked chosen,
 * shown even while it isn't there. Each a request().
 */
export function speakersSheet(answer, state = null) {
  const sheet = { loading: false, groups: [], message: "", error: "" };
  if (!answer) return { ...sheet, loading: true, message: "Looking for speakers…" };
  if (!answer.speakers) return { ...sheet, error: capitalize(answer.error ?? "") };
  const speakers = answer.speakers.filter((s) => s?.name && s.label);
  const picked = answer.voice ?? null;
  const item = (prefix, s, chosen) => ({
    id: `${prefix}:${s.name}`,
    label: s.label,
    icon: SPEAKER_KINDS[s.kind]?.icon ?? "speaker",
    detail: SPEAKER_KINDS[s.kind]?.detail ?? "",
    chosen,
    disabled: false,
  });
  const voice = [
    { id: "speaker-voice:", label: "With the music", icon: "music", detail: "on the same speaker", chosen: !picked, disabled: false },
    ...speakers.map((s) => item("speaker-voice", s, picked?.name === s.name)),
  ];
  if (picked && !speakers.some((s) => s.name === picked.name)) {
    voice.push({ ...item("speaker-voice", picked, true), detail: "not there now: with the music meanwhile", disabled: true });
  }
  return {
    ...sheet,
    groups: [
      { title: "Music", items: speakers.map((s) => item("speaker-music", s, Boolean(s.default))) },
      { title: voiceOf(state), items: voice },
    ],
    message: speakers.length ? "" : "No speakers found",
  };
}

/** How many bars a network's signal (0 to 100) shows: its icon is wifi-1 to wifi-4. */
export function bars(signal) {
  if (!Number.isFinite(signal)) return 1;
  return signal >= 75 ? 4 : signal >= 50 ? 3 : signal >= 25 ? 2 : 1;
}

/** What joining network `ssid` asks of grog, with its `password` once typed. */
export function joinRequest(ssid, password = null) {
  const body = password === null ? { connect: ssid } : { connect: ssid, password };
  return { method: "POST", path: "/v1/actions/wifi", body };
}

/** Whether joining `network` (of grog's answer) takes its password first: a protected one, not saved. */
export function needsPassword(network) {
  return network?.security === "password" && !network.saved;
}

// What a network's row says, by what it asks to join it.
const SECURITY = { open: "Open", password: "Password", enterprise: "Wants more than a password" };

// A WPA password takes 8 characters at least, 63 at most (or 64 hex digits).
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 64;

// The on-screen keyboard's pages, each rows of keys: characters (a string
// is a row of them), and shift, back, space, done (joining) and the other
// pages. Between them, every printable ASCII character.
const PAGES = {
  letters: ["qwertyuiop", "asdfghjkl", ["shift", ..."zxcvbnm", "back"], ["digits", "space", "done"]],
  digits: ["1234567890", '-/:;()$&@"', ["symbols", ...".,?!'", "back"], ["letters", "space", "done"]],
  symbols: ["[]{}#%^*+=", "_\\|~<>`", ["digits", ...".,?!'", "back"], ["letters", "space", "done"]],
};
const SPECIAL = {
  shift: { icon: "shift", label: "Shift" },
  back: { icon: "backspace", label: "Delete" },
  space: { label: "space" },
  done: { label: "Join" },
  digits: { label: "123" },
  symbols: { label: "#+=" },
  letters: { label: "abc" },
};

/**
 * The keyboard's rows on `page` (letters, digits or symbols), its letters
 * capitals with `shift`: [[{key, kind, label, icon, on}]], kind being char
 * (`key` what it types), or a special key's name (shift, back, space, done,
 * or the page it goes to).
 */
export function keyboard(page = "letters", shift = false) {
  return (PAGES[page] ?? PAGES.letters).map((row) =>
    [...row].map((key) => {
      if (key.length > 1) return { key, kind: key, icon: null, on: key === "shift" && shift, ...SPECIAL[key] };
      const ch = shift ? key.toUpperCase() : key;
      return { key: ch, kind: "char", label: ch, icon: null, on: false };
    }),
  );
}

/**
 * The password being typed, once `key` (of keyboard(), or a physical
 * keyboard's, see physicalKey()) was: {ssid, text, page, shift, shown,
 * busy, error}. Shift lasts a letter; a page changes; done changes nothing
 * here (joining is the skin's). What's typed stops at PASSWORD_MAX.
 */
export function typed(form, key) {
  const f = { text: "", page: "letters", shift: false, shown: false, ...form, error: "" };
  const room = [...f.text].length < PASSWORD_MAX;
  switch (key?.kind) {
    case "char":
      return room ? { ...f, text: f.text + key.key, shift: false } : f;
    case "space":
      return room ? { ...f, text: `${f.text} ` } : f;
    case "back":
      return { ...f, text: [...f.text].slice(0, -1).join("") };
    case "shift":
      return { ...f, shift: !f.shift };
    case "letters":
    case "digits":
    case "symbols":
      return { ...f, page: key.kind, shift: false };
    default:
      return f;
  }
}

/** A key of keyboard() for what a physical keyboard's `key` (KeyboardEvent.key) types, or null. */
export function physicalKey(key) {
  if (key === "Backspace") return { key, kind: "back" };
  if (key === "Enter") return { key, kind: "done" };
  if (key === " ") return { key, kind: "space" };
  return typeof key === "string" && /^[\x21-\x7e]$/.test(key) ? { key, kind: "char" } : null;
}

/** Whether the password typed in `form` can be tried: long enough, and nothing on its way. */
export function canJoin(form) {
  return [...(form?.text ?? "")].length >= PASSWORD_MIN && !form?.busy;
}

/** What the password form shows: its title, the field (dots unless shown), the keyboard. */
function passwordForm(form) {
  const text = form.text ?? "";
  const length = [...text].length;
  return {
    ssid: form.ssid,
    title: `Password for ${form.ssid}`,
    field: form.shown ? text : "•".repeat(length),
    hint: length && length < PASSWORD_MIN ? `At least ${PASSWORD_MIN} characters` : "",
    shown: Boolean(form.shown),
    busy: Boolean(form.busy),
    error: capitalize(form.error ?? ""),
    join: canJoin(form),
    keys: keyboard(form.page, form.shift),
  };
}

/**
 * The Wi-Fi sheet, out of what grog answered `POST /v1/actions/wifi`
 * (`answer`: {radio, devices, networks}, or {error}; null while it's on its
 * way), or the password being typed for a network (`form`, see typed()),
 * while it joins network `joining` (saved, or open) or not (null):
 * {loading, message, error, items: [{id, label, icon, detail, chosen,
 * disabled}], buttons: [{id, label, disabled}], form}. Its networks, the
 * one it's on first, each joined by a tap (wifi-join:<ssid>), those wanting
 * more than a password greyed out, and the others while it joins one;
 * looking again, or turning Wi-Fi on while it's off.
 */
export function wifiSheet(answer, form = null, joining = null) {
  const sheet = { loading: false, message: "", error: "", items: [], buttons: [], form: null };
  if (form) return { ...sheet, form: passwordForm(form) };
  if (!answer) return { ...sheet, loading: true, message: "Looking for networks…" };
  if (!answer.networks) return { ...sheet, error: capitalize(answer.error ?? "") };
  if (answer.radio === "off") {
    return { ...sheet, message: "Wi-Fi is off", buttons: [{ id: "wifi-on", label: "Turn it on", disabled: false }] };
  }
  if (!answer.devices?.length) return { ...sheet, message: "No Wi-Fi here" };
  const items = answer.networks
    .filter((n) => n?.ssid)
    .map((n) => ({
      id: `wifi-join:${n.ssid}`,
      label: n.ssid,
      icon: `wifi-${bars(n.signal)}`,
      detail:
        n.ssid === joining ? "Joining…" : n.active ? "Connected" : n.saved ? "Saved" : (SECURITY[n.security] ?? ""),
      chosen: Boolean(n.active),
      disabled: n.security === "enterprise" || (joining !== null && n.ssid !== joining),
    }));
  return {
    ...sheet,
    items,
    buttons: [{ id: "wifi-rescan", label: "Look again", disabled: joining !== null }],
    message: items.length ? "" : "No networks in sight",
  };
}

/**
 * Formats for grog's time zone (an IANA name), or this browser's without
 * one or with one it doesn't know: {clock: 14:05, date: Saturday, October
 * 17, hour: 14}, in English like the rest of the skin.
 */
export function timeFormats(timezone) {
  const make = (timeZone) => ({
    clock: new Intl.DateTimeFormat("en", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }),
    date: new Intl.DateTimeFormat("en", { timeZone, weekday: "long", month: "long", day: "numeric" }),
    hour: new Intl.DateTimeFormat("en", { timeZone, hour: "numeric", hourCycle: "h23" }),
  });
  try {
    return make(timezone || undefined);
  } catch {
    return make(undefined);
  }
}

/** Dollars: cents to spare when it's little. */
export function usd(x) {
  const n = Number(x) || 0;
  return `$${n.toFixed(n > 0 && n < 0.1 ? 4 : 2)}`;
}

/** `s` seconds, roughly: 45m, 2h 10m, 3d 4h. */
export function duration(s) {
  s = Math.max(0, Math.floor(s));
  if (s < 60) return "<1m";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return m ? `${h}h ${m}m` : `${h}h`;
  }
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  return h ? `${d}d ${h}h` : `${d}d`;
}

/** How much of a limit used `percent` is: ok, warn (70 %) or high (90 %). */
export function level(percent) {
  if (percent >= 90) return "high";
  if (percent >= 70) return "warn";
  return "ok";
}

/** The subscription's plan, as its name: ChatGPT Plus; or null. */
function plan(quota) {
  return quota?.plan ? `ChatGPT ${capitalize(quota.plan)}` : null;
}

/**
 * What grog consumed (brain/src/grog/usage.clj): with the subscription, how
 * much of its limits is used {kind, title, windows: [{label, percent, level,
 * resetsIn}]}; with an API key, what it spent {kind, month, today, unpriced,
 * chart: [{date, height}]}. null otherwise.
 */
export function usage(u, now) {
  const nowS = Math.floor(now / 1000);
  if (u?.auth === "subscription") {
    const windows = (u.quota?.windows ?? []).map((w) => {
      const reset = w.resetsAt && w.resetsAt > nowS;
      // Past its reset, nothing used until it's asked again.
      const percent = w.resetsAt && !reset ? 0 : Math.min(100, Math.max(0, Math.round(w.usedPercent)));
      return {
        label: w.label,
        percent,
        level: level(percent),
        resetsIn: reset ? duration(w.resetsAt - nowS) : null,
      };
    });
    return { kind: "subscription", title: plan(u.quota) ?? "Subscription", windows };
  }
  if (u?.auth === "api-key") {
    const days = u.days ?? [];
    const top = Math.max(0, ...days.map((d) => d.usd ?? 0));
    return {
      kind: "api-key",
      month: usd(u.month?.usd),
      today: usd(u.today?.usd),
      unpriced: u.month?.unpriced ?? 0,
      chart: days.map((d) => ({ date: d.date, height: top > 0 ? (d.usd ?? 0) / top : 0 })),
    };
  }
  return null;
}

/** `url` without its scheme: auth.openai.com/codex/device. */
export function shortUrl(url) {
  return String(url ?? "").replace(/^https?:\/\//, "");
}

/** The QR code of `text`, as grog makes it (GET /v1/qr), or null without text. */
export function qrSrc(text) {
  return text ? `/v1/qr?text=${encodeURIComponent(text)}` : null;
}

/** Where `url` goes: github.com; the URL as it is when it isn't one. */
export function host(url) {
  try {
    return new URL(url).host || String(url ?? "");
  } catch {
    return String(url ?? "");
  }
}

/** A pairing code as it's shown: 482 913. */
export function spacedCode(code) {
  const s = String(code ?? "");
  return s.length === 6 ? `${s.slice(0, 3)} ${s.slice(3)}` : s;
}

const CANCEL = { id: "connect-cancel", label: "Cancel", primary: false };

/** A card with `fields`, and nothing in the others. */
function cardOf(fields) {
  return {
    stage: "",
    title: "",
    detail: "",
    question: "",
    qr: null,
    hint: "",
    link: "",
    hint2: "",
    code: "",
    command: "",
    note: "",
    error: "",
    expires: "",
    help: null,
    failed: false,
    buttons: [],
    ...fields,
  };
}

function helpOf(help) {
  return help?.text ? { text: help.text, url: help.url ?? "", shortUrl: shortUrl(help.url) } : null;
}

/** What to do at the step of `c`, a session open (see card()). */
function openCard(c, now) {
  const step = c.step ?? {};
  const base = {
    stage: step.kind ?? "starting",
    title: c.heading ?? "",
    expires: `closes in ${duration(((c.until ?? 0) - now) / 1000)}`,
    buttons: c.kept ? [] : [CANCEL],
  };
  // grog's page for it, on a phone: where it says what to do.
  const page = c.link ? { qr: qrSrc(c.link), hint: "On your phone or computer, open", link: shortUrl(c.link) } : {};
  switch (step.kind) {
    case "device-code":
      return cardOf({
        ...base,
        ...(c.link
          ? { ...page, hint2: `or go to ${shortUrl(step.url)} and enter the code` }
          : { qr: qrSrc(step.url), hint: "On your phone or computer, open", link: shortUrl(step.url), hint2: "and enter the code" }),
        code: step.code ?? "",
        help: helpOf(step.help),
      });
    case "oauth":
      return cardOf({
        ...base,
        ...page,
        hint2: "and sign in there",
        note: c.link ? "" : "grog login --browser, on this machine, says where",
      });
    case "form":
      return cardOf({ ...base, ...page, hint2: "and fill it in there" });
    case "script":
      return cardOf({
        ...base,
        hint: "In a terminal on it, run",
        command: step.command ?? "",
        note: step.address ? `Or open ${step.address} in its browser. It needs an SSH server.` : "",
      });
    case "code-compare":
      return cardOf({
        ...base,
        title: step.name ? `${step.name} wants to pair` : base.title,
        detail: step.user ? `as ${step.user}` : "",
        hint: "Allow it if it shows the same code",
        code: spacedCode(step.code),
        buttons: [
          { id: "connect-allow", label: "Allow", primary: true },
          { id: "connect-deny", label: "Deny", primary: false },
        ],
      });
    case "confirm":
      return cardOf({
        ...base,
        question: step.question ?? "",
        note: step.detail ?? "",
        buttons: [
          { id: "connect-allow", label: "Allow", primary: true },
          { id: "connect-deny", label: "Deny", primary: false },
        ],
      });
    case "link":
      return cardOf({
        ...base,
        question: step.message ?? "",
        qr: qrSrc(step.url),
        hint: step.url ? "Scan it to open a page on" : "",
        link: host(step.url),
        buttons: [{ id: "connect-allow", label: "Done", primary: true }, CANCEL],
      });
    case "wait":
      return cardOf({ ...base, note: step.note ?? "", buttons: [] });
    default:
      return cardOf({ ...base, note: c.kept ? "Getting a code…" : "Getting ready…" });
  }
}

// What grog asks at its card, for a tool (brain/src/grog/asks.clj) or a
// server (elicit.clj): sessions that connect nothing.
const ASKING = ["ask", "elicit"];

/** How the session `c` ended, or null when there's nothing to say. */
function outcomeCard(c) {
  const base = { stage: c.status, title: c.heading ?? "" };
  // What grog keeps up to sign in has its own card (signInCard).
  if (c.kept && c.status !== "done") return null;
  switch (c.status) {
    case "done":
      // What was asked at grog connected nothing.
      if (c.integration === "ask" || c.step?.kind === "confirm") return cardOf({ ...base, note: "allowed" });
      if (ASKING.includes(c.integration) || c.step?.kind === "link") return cardOf({ ...base, note: "done" });
      return cardOf({ ...base, title: `Connected: ${c.connected ?? c.title ?? ""}` });
    case "denied":
      return cardOf({ ...base, note: `${c.step?.name ?? "It"} wasn't allowed` });
    case "expired":
      return cardOf({ ...base, note: "time's up" });
    case "failed":
      return cardOf({ ...base, note: c.error ?? "", failed: true });
    default:
      return null;
  }
}

/**
 * How to sign in while grog has nothing to pay with, between the sign-ins it
 * keeps up for it (brain/src/grog/signin.clj): getting a code, or after one
 * failed, trying again.
 */
function signInCard(state, now) {
  const c = state.connect;
  const integration = (state.integrations ?? []).find((i) => i.id === state.provider);
  const failed = c?.status === "failed" && c.integration === state.provider && now - (c.at ?? 0) < RETRY_MS;
  return cardOf({
    stage: "sign-in",
    title: integration?.heading ?? "Sign in",
    note: failed ? "Couldn't get a code: trying again in a minute" : "Getting a code…",
    error: failed ? (c.error ?? "") : "",
  });
}

/**
 * Connecting something to grog (brain/src/grog/connect.clj), in place of what
 * was said and done: while a session is open, what to do now; how it ended,
 * for a while; and how to sign in while grog has nothing to pay with. null
 * otherwise. {stage, title, detail, question, qr, hint, link, hint2, code,
 * command, note, error, expires, help, failed, buttons}: `stage`, its step's
 * kind (or starting) while it's open, else how it ended, or sign-in;
 * `question`, what's asked at grog (or why a link is to be opened); `qr`,
 * where its QR code is; `link`, grog's page for it, without its scheme (or
 * where a link step's page is); `help`, what
 * may have to be done first ({text, url, shortUrl}); `buttons`, what can be
 * asked then ({id, label, primary}, see request()).
 */
export function card(state, now) {
  const c = state?.connect;
  if (!state) return null;
  if (c?.status === "open" && (c.until ?? 0) > now) return openCard(c, now);
  const outcome = c?.status && c.status !== "open" && now - (c.at ?? 0) <= OUTCOME_MS ? outcomeCard(c) : null;
  if (outcome) return outcome;
  return state.auth === "none" ? signInCard(state, now) : null;
}

/** How grog is paid, for the line under its name. */
export function account(state) {
  switch (state?.auth) {
    case "subscription":
      return plan(state.usage?.quota) ?? "ChatGPT";
    case "api-key":
      return "API key";
    default:
      return "no credentials";
  }
}

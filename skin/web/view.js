// What the skin shows, out of grog's state (GET /v1/events, see
// brain/src/grog/events.clj) and the time: pure functions, tested with
// `node --test`. Times are epoch milliseconds, but the usage's (seconds).

// grog-voice says what it's doing at least every 10 s.
export const VOICE_STALE_MS = 35_000;
// How long an error shows.
export const ERROR_MS = 6_000;
// How long the captions of the last turn stay once it's over.
export const CAPTIONS_MS = 20_000;
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

const TOOLS = {
  web_search: { label: "Searching the web", short: "Web", icon: "web" },
  volume: { label: "Adjusting the volume", short: "Volume", icon: "volume" },
  system_status: { label: "Checking the machine", short: "Machine", icon: "machine" },
  shell: { label: "Running a command", short: "Shell", icon: "shell" },
  memory: { label: "Remembering", short: "Memory", icon: "memory" },
  settings: { label: "Changing its settings", short: "Settings", icon: "settings" },
};

/** `s` with its first letter in upper case. */
export function capitalize(s) {
  const str = String(s ?? "");
  return str.charAt(0).toUpperCase() + str.slice(1);
}

/** A tool by its name: {label, short, icon}. */
export function tool(name) {
  if (TOOLS[name]) return TOOLS[name];
  const label = capitalize(String(name ?? "tool").replaceAll("_", " "));
  return { label, short: label, icon: "tool" };
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

/** Whether the last turn failed, or grog-voice had an error, just now. */
function failed(state, now) {
  const turn = state.turn;
  const voice = state.voice;
  return Boolean(
    (turn?.doneAt && turn.ok === false && now - turn.doneAt < ERROR_MS) ||
      (voice?.errorAt && now - voice.errorAt < ERROR_MS),
  );
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
 * {mode, label, detail, icon}.
 */
export function mode(state, now) {
  if (!state) return { mode: "offline", label: "Offline", detail: "waiting for grog" };
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
  if (state.auth === "none") return { mode: "login", label: "Needs a login", detail: "grog login, or an API key" };
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
    case "login":
      return { state: "login", icon: "key" };
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
    out.push({
      label: turn.ok === false ? "Failed" : "Done",
      icon: turn.ok === false ? "failed" : "done",
      offset: seconds(end - start),
      status: turn.ok === false ? "failed" : "done",
    });
  }
  return out;
}

/**
 * What was said in the current or last turn, when captions are on and it's
 * recent: {question, answer, live}, or null. Before a turn and after the
 * last one, what grog-voice hears for grog, as it comes (live while it isn't
 * all there); during a turn, its question, as it was said when grog-voice
 * heard it. The answer's end, at most `max` characters, cut at a word.
 */
export function captions(state, now, max = 260) {
  if (!state?.captions) return null;
  const { turn, heard } = state;
  const between = !turn || (turn.doneAt && heard?.at > turn.doneAt);
  if (heard?.text && between && now - heard.at < CAPTIONS_MS) {
    return { question: heard.text, answer: "", live: !heard.final };
  }
  if (!turn || (turn.doneAt && now - turn.doneAt > CAPTIONS_MS)) return null;
  const question = turn.said ?? turn.question ?? "";
  if (!question && !turn.answer) return null;
  let answer = (turn.answer ?? "").trim();
  if (answer.length > max) {
    const cut = answer.slice(-max);
    answer = `…${cut.slice(cut.indexOf(" ") + 1)}`;
  }
  return { question, answer, live: false };
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

/** The last turns: {at, icon, label, took, ok}, newest first. */
export function recent(state) {
  return (state?.recent ?? []).map((r) => {
    const tools = (r.tools ?? []).map((t) => tool(t));
    return {
      at: r.at,
      icon: tools[0]?.icon ?? (r.conversation === state.voice?.conversation ? "voice" : "answering"),
      label: tools.length ? [...new Set(tools.map((t) => t.short))].join(" · ") : "Answer",
      took: seconds(r.ms ?? 0),
      ok: r.ok !== false,
    };
  });
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

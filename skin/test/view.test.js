import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  account,
  CAPTIONS_MS,
  capitalize,
  captions,
  closes,
  DOZING_MS,
  duration,
  elapsed,
  ERROR_MS,
  face,
  GOING_MS,
  isNight,
  level,
  menu,
  mode,
  pairCommand,
  PAIRED_MS,
  pairing,
  PAIRING_PORT,
  pipeline,
  PLEASED_MS,
  qrFile,
  reaction,
  recent,
  request,
  seconds,
  shortRevision,
  signIn,
  STALE_MS,
  stale,
  STILL_MS,
  tap,
  timeFormats,
  tool,
  UPDATE_FAILED_MS,
  UPDATE_MS,
  UPDATED_MS,
  updateNotice,
  usage,
  usd,
  version,
  VOICE_GRACE_MS,
  VOICE_STALE_MS,
  VOLUME_STEP,
  wordChanges,
} from "../web/view.js";

const NOW = 1_000_000;

function state(extra = {}) {
  return {
    name: "Grog",
    auth: "api-key",
    captions: false,
    voice: { state: "listening", since: NOW - 5000, seen: NOW - 1000, conversation: "voice" },
    turn: null,
    recent: [],
    usage: null,
    ...extra,
  };
}

function turn(steps, extra = {}) {
  return { conversation: "voice", n: 1, steps: steps.map(([step, at, name]) => ({ step, at, name })), ...extra };
}

describe("what grog is doing", () => {
  it("listens, saying what to call it", () => {
    assert.deepEqual(mode(state(), NOW), { mode: "idle", label: "Listening", detail: "say “Grog”" });
  });

  it("is offline before grog says anything", () => {
    assert.equal(mode(null, NOW).mode, "offline");
  });

  it("hears someone talk to it, and wakes up when called", () => {
    const voice = (s) => state({ voice: { state: s, since: NOW, seen: NOW, conversation: "voice" } });
    assert.deepEqual(mode(voice("hearing"), NOW), { mode: "hearing", label: "Hearing you", detail: "" });
    assert.deepEqual(mode(voice("awake"), NOW), { mode: "awake", label: "Go ahead", detail: "listening, no need to call" });
    assert.deepEqual(mode(voice("asked"), NOW), {
      mode: "asked",
      label: "Your turn",
      detail: "it asked something: just answer",
    });
    assert.equal(mode(voice("loading"), NOW).mode, "loading");
    assert.equal(mode(voice("no-mic"), NOW).mode, "no-mic");
  });

  it("is ready without a voice, once grog has been up a while", () => {
    assert.deepEqual(mode(state({ voice: null }), NOW), { mode: "idle", label: "Ready", detail: "" });
    assert.equal(mode(state({ voice: null, startedAt: NOW - VOICE_GRACE_MS }), NOW).label, "Ready");
  });

  it("is waking up right after grog started, before its voice says anything", () => {
    assert.deepEqual(mode(state({ voice: null, startedAt: NOW - 5000 }), NOW), {
      mode: "loading",
      label: "Waking up",
      detail: "loading its ears and voice",
    });
  });

  it("can't hear once its voice stopped saying", () => {
    const stale = state({ voice: { state: "hearing", since: 0, seen: NOW - VOICE_STALE_MS - 1 } });
    assert.deepEqual(mode(stale, NOW), { mode: "deaf", label: "Can't hear", detail: "its voice isn't running" });
  });

  it("needs a login without credentials, but still hears who calls it", () => {
    assert.deepEqual(mode(state({ auth: "none" }), NOW), {
      mode: "login",
      label: "Needs a login",
      detail: "grog login, or an API key",
    });
    assert.equal(
      mode(state({ auth: "none", login: { status: "pending", method: "device", code: "ABCD-12345" } }), NOW).detail,
      "sign in from your phone",
    );
    const hearing = { state: "hearing", since: NOW, seen: NOW, conversation: "voice" };
    assert.equal(mode(state({ auth: "none", voice: hearing }), NOW).mode, "hearing");
  });

  it("thinks, uses its tools, answers and speaks", () => {
    const at = (steps, voice = "answering") =>
      mode(state({ turn: turn(steps), voice: { state: voice, since: NOW, seen: NOW } }), NOW);
    assert.deepEqual(at([["thinking", NOW - 1000]]), { mode: "thinking", label: "Thinking", detail: "" });
    assert.deepEqual(at([["thinking", 1], ["tool", 2, "web_search"]]), {
      mode: "tool",
      label: "Searching the web",
      detail: "",
      icon: "web",
    });
    assert.equal(at([["thinking", 1], ["answering", 2]]).mode, "answering");
    assert.equal(at([["thinking", 1], ["answering", 2], ["speaking", 3]], "speaking").mode, "speaking");
  });

  it("is still thinking after a while", () => {
    const thinking = (ago) => mode(state({ turn: turn([["thinking", NOW - ago]]) }), NOW);
    assert.equal(thinking(STILL_MS - 1).mode, "thinking");
    assert.deepEqual(thinking(STILL_MS), { mode: "still", label: "Still thinking", detail: "" });
  });

  it("keeps speaking once the answer is all there", () => {
    const done = turn([["thinking", 1], ["answering", 2]], { doneAt: NOW - 100, ok: true });
    assert.equal(mode(state({ turn: done, voice: { state: "speaking", since: NOW, seen: NOW } }), NOW).mode, "speaking");
  });

  it("thinks while grog-voice waits for an answer, before the turn starts", () => {
    const voice = { state: "answering", since: NOW - 300, seen: NOW, conversation: "voice" };
    assert.equal(mode(state({ voice }), NOW).mode, "thinking", "no turn yet");
    const earlier = turn([["thinking", NOW - 9000]], { doneAt: NOW - 5000, ok: true });
    assert.deepEqual(mode(state({ voice, turn: earlier }), NOW), { mode: "thinking", label: "Thinking", detail: "" });
  });

  it("answers while grog-voice gets the last turn's answer ready to say", () => {
    const voice = { state: "answering", since: NOW - 3000, seen: NOW, conversation: "voice" };
    const done = turn([["thinking", NOW - 2500], ["answering", NOW - 1500]], { doneAt: NOW - 1000, ok: true });
    assert.deepEqual(mode(state({ voice, turn: done }), NOW), { mode: "answering", label: "Answering", detail: "" });
    const typed = { ...done, conversation: "default" };
    assert.equal(mode(state({ voice, turn: typed }), NOW).mode, "thinking", "another conversation's");
  });

  it("says when something went wrong, for a while", () => {
    const failed = turn([["thinking", 1]], { doneAt: NOW - 1000, ok: false });
    assert.equal(mode(state({ turn: failed }), NOW).mode, "error");
    assert.equal(mode(state({ turn: failed }), NOW + 10_000).mode, "idle");
    assert.equal(mode(state({ turn: { ...failed, error: "No credentials." } }), NOW).detail, "No credentials.");
  });

  it("says when grog-voice had an error, for a while", () => {
    const voice = { state: "error", since: NOW - 1000, seen: NOW - 1000, conversation: "voice", errorAt: NOW - 1000 };
    assert.deepEqual(mode(state({ voice }), NOW), {
      mode: "error",
      label: "Something went wrong",
      detail: "it couldn't answer",
    });
    assert.equal(mode(state({ voice }), NOW - 1000 + ERROR_MS).mode, "idle");
    assert.equal(mode(state({ voice: { ...voice, state: "listening" } }), NOW).mode, "error", "back to listening");
  });

  it("listens after grog-voice's error state with no error said", () => {
    const voice = { state: "error", since: NOW, seen: NOW, conversation: "voice" };
    assert.deepEqual(mode(state({ voice }), NOW), { mode: "idle", label: "Listening", detail: "say “Grog”" });
  });
});

describe("the turn under way", () => {
  it("how long it has taken so far", () => {
    assert.equal(elapsed(state({ turn: turn([["heard", NOW - 2300], ["thinking", NOW - 1000]]) }), NOW), "2.3 s");
    assert.equal(elapsed(state({ turn: turn([["heard", NOW - 2300]], { doneAt: NOW }) }), NOW), "", "over");
    assert.equal(elapsed(state({ turn: turn([]) }), NOW), "", "no steps yet");
    assert.equal(elapsed(state(), NOW), "");
    assert.equal(elapsed(null, NOW), "");
  });

  it("its steps fade a while after it's over", () => {
    const done = turn([["thinking", 1]], { doneAt: NOW, ok: true });
    assert.equal(stale(state({ turn: done }), NOW + STALE_MS), false);
    assert.equal(stale(state({ turn: done }), NOW + STALE_MS + 1), true);
    assert.equal(stale(state({ turn: turn([["thinking", 1]]) }), NOW + STALE_MS * 2), false, "under way");
    assert.equal(stale(null, NOW), false);
  });
});

describe("its face", () => {
  const idle = { mode: "idle" };

  it("as what grog does, its pupil an icon for a tool, no mic or no login", () => {
    assert.deepEqual(face({ mode: "thinking" }, state(), NOW), { state: "thinking", icon: null });
    assert.deepEqual(face({ mode: "answering" }, state(), NOW), { state: "writing", icon: null });
    const volume = mode(state({ turn: turn([["tool", NOW, "volume"]]) }), NOW);
    assert.deepEqual(face(volume, state(), NOW), { state: "tool", icon: "volume" });
    assert.deepEqual(face({ mode: "tool" }, state(), NOW), { state: "tool", icon: "tool" });
    assert.deepEqual(face({ mode: "no-mic" }, state(), NOW), { state: "no-mic", icon: "no-mic" });
    assert.deepEqual(face({ mode: "login" }, state(), NOW), { state: "login", icon: "key" });
    assert.deepEqual(face(mode(null, NOW), null, NOW), { state: "offline", icon: null });
  });

  it("listening, at rest, or sleepy at night", () => {
    assert.equal(face(idle, state(), NOW).state, "idle");
    assert.equal(face(idle, state(), NOW, true).state, "sleepy");
  });

  it("pleased just after a typed turn went well", () => {
    const done = turn([["thinking", NOW - 3000]], { conversation: "default", doneAt: NOW - 1000, ok: true });
    assert.equal(face(idle, state({ turn: done }), NOW).state, "pleased");
    assert.equal(face(idle, state({ turn: done }), NOW, true).state, "pleased", "at night too");
    assert.equal(face(idle, state({ turn: done }), NOW - 1000 + PLEASED_MS).state, "idle", "for a moment");
    assert.equal(face(idle, state({ turn: { ...done, ok: false } }), NOW).state, "idle", "not when it failed");
  });

  it("pleased once a voice turn is said, not when it was done", () => {
    const said = turn([["thinking", NOW - 9000], ["answering", NOW - 8000], ["speaking", NOW - 7500]], {
      doneAt: NOW - 7000,
      ok: true,
    });
    assert.equal(face(idle, state({ turn: { ...said, doneAt: NOW - 500 } }), NOW).state, "idle", "not said yet");
    assert.equal(face(idle, state({ turn: { ...said, spokenAt: NOW - 500 } }), NOW).state, "pleased");
  });

  it("dozing once nobody talked to it for a long while", () => {
    const listening = { state: "listening", since: NOW - DOZING_MS, seen: NOW, conversation: "voice" };
    const quiet = state({ startedAt: NOW - DOZING_MS - 10_000, voice: listening });
    assert.equal(face(idle, quiet, NOW).state, "dozing");
    assert.equal(face(idle, quiet, NOW, true).state, "dozing", "at night too");
    const done = turn([["thinking", NOW - 70_000]], { doneAt: NOW - 60_000, ok: true });
    assert.equal(face(idle, { ...quiet, turn: done }, NOW).state, "idle", "not soon after a turn");
    assert.equal(face(idle, state({ voice: null }), NOW).state, "idle", "nor when it doesn't know");
  });
});

describe("what its face does on the way to the next", () => {
  it("wakes up, swaps its pupil and an icon behind a blink, and reacts", () => {
    const cases = [
      [null, "idle", null, null, null],
      ["idle", "idle", null, null, null],
      ["idle", "offline", null, null, null],
      ["offline", "loading", null, null, null],
      ["loading", "idle", null, null, "wake"],
      ["offline", "login", null, "key", "wake"],
      ["thinking", "tool", null, "web", "swap"],
      ["tool", "tool", "web", "volume", "swap"],
      ["tool", "thinking", "web", null, "swap"],
      ["idle", "no-mic", null, "no-mic", "swap"],
      ["hearing", "awake", null, null, "pop"],
      ["thinking", "error", null, null, "flinch"],
      ["speaking", "pleased", null, null, "hop"],
      ["hearing", "thinking", null, null, "nod"],
      ["hearing", "idle", null, null, "never-mind"],
      ["asked", "sleepy", null, null, "never-mind"],
      ["awake", "hearing", null, null, null],
      ["pleased", "idle", null, null, null],
    ];
    for (const [from, to, fromIcon, toIcon, want] of cases) {
      assert.equal(reaction(from, to, fromIcon, toIcon), want, `${from} to ${to}`);
    }
  });
});

describe("the steps of a turn", () => {
  it("each with the time since the first, the last one active", () => {
    const steps = pipeline(turn([["heard", 1000], ["thinking", 2800], ["tool", 3400, "web_search"]]));
    assert.deepEqual(
      steps.map(({ label, icon, offset, status }) => [label, icon, offset, status]),
      [
        ["Heard", "voice", "+0.0 s", "done"],
        ["Thinking", "thinking", "+1.8 s", "done"],
        ["Web", "web", "+2.4 s", "active"],
      ],
    );
  });

  it("ending with how it went", () => {
    const done = pipeline(turn([["thinking", 1000], ["answering", 2000]], { doneAt: 5200, ok: true }));
    assert.deepEqual(done.at(-1), { label: "Done", icon: "done", offset: "4.2 s", status: "done" });
    assert.equal(done[1].status, "done");
    const failed = pipeline(turn([["thinking", 1000]], { doneAt: 1500, ok: false }));
    assert.deepEqual(failed.at(-1), { label: "Failed", icon: "failed", offset: "0.5 s", status: "failed" });
  });

  it("done once it's spoken, when grog-voice speaks after grog is done", () => {
    const steps = pipeline(
      turn([["thinking", 1000], ["answering", 2000], ["speaking", 3500]], { doneAt: 2900, ok: true }),
    );
    assert.deepEqual(
      steps.map((s) => [s.label, s.offset]),
      [
        ["Thinking", "+0.0 s"],
        ["Answering", "+1.0 s"],
        ["Speaking", "+2.5 s"],
        ["Done", "2.5 s"],
      ],
    );
  });

  it("none active while grog is offline", () => {
    const steps = pipeline(turn([["thinking", 1000], ["answering", 2000]]), true);
    assert.deepEqual(
      steps.map((s) => s.status),
      ["done", "done"],
    );
  });

  it("what it says before a tool isn't a step", () => {
    const steps = pipeline(turn([["thinking", 0], ["answering", 100], ["tool", 200, "web_search"], ["answering", 900]]));
    assert.deepEqual(
      steps.map((s) => s.label),
      ["Thinking", "Web", "Answering"],
    );
  });

  it("none without a turn", () => {
    assert.deepEqual(pipeline(null), []);
  });
});

describe("captions", () => {
  const said = turn([["thinking", 1]], { question: "will it rain?", answer: " Sunny all day. " });

  it("only when they're on", () => {
    assert.equal(captions(state({ turn: said }), NOW), null);
    assert.deepEqual(captions(state({ turn: said, captions: true }), NOW), {
      question: "will it rain?",
      answer: "Sunny all day.",
      live: false,
    });
  });

  it("what's said to grog as it comes, before the turn", () => {
    const heard = { text: "Grog, will it", final: false, at: NOW - 500 };
    assert.deepEqual(captions(state({ captions: true, heard }), NOW), {
      question: "Grog, will it",
      answer: "",
      live: true,
    });
    const last = turn([["thinking", NOW - 60_000]], { question: "hi", answer: "Hi!", doneAt: NOW - 50_000, ok: true });
    assert.equal(captions(state({ captions: true, heard, turn: last }), NOW).question, "Grog, will it", "after the last");
    assert.equal(captions(state({ captions: true, heard: { ...heard, final: true } }), NOW).live, false, "all of it");
    const old = { ...heard, at: NOW - CAPTIONS_MS - 1 };
    assert.equal(captions(state({ captions: true, heard: old }), NOW), null, "not for long");
    assert.equal(captions(state({ heard }), NOW), null, "only with captions");
  });

  it("the voice turn's question, as it was said", () => {
    const heard = { text: "Grog, will it rain?", final: true, at: NOW - 2000 };
    const voiced = turn([["heard", NOW - 4000], ["thinking", NOW - 1500]], {
      said: "Grog, will it rain?",
      question: "will it rain?",
      answer: "No.",
    });
    assert.deepEqual(captions(state({ captions: true, heard, turn: voiced }), NOW), {
      question: "Grog, will it rain?",
      answer: "No.",
      live: false,
    });
    const typed = { ...voiced, said: undefined, conversation: "default" };
    assert.equal(captions(state({ captions: true, heard, turn: typed }), NOW).question, "will it rain?", "typed");
  });

  it("not what's heard during a turn: the same words, heard again", () => {
    const voiced = turn([["heard", NOW - 4000], ["thinking", NOW - 1500]], { said: "Grog, will it rain?" });
    const heard = { text: "Grog, will it rain", final: true, at: NOW - 1000 };
    assert.deepEqual(captions(state({ captions: true, heard, turn: voiced }), NOW), {
      question: "Grog, will it rain?",
      answer: "",
      live: false,
    });
    const done = { ...voiced, answer: "No.", doneAt: NOW - 500, ok: true };
    assert.equal(captions(state({ captions: true, heard, turn: done }), NOW).answer, "No.", "heard before it ended");
    const next = { text: "Grog, and", final: false, at: NOW - 100 };
    assert.deepEqual(captions(state({ captions: true, heard: next, turn: done }), NOW), {
      question: "Grog, and",
      answer: "",
      live: true,
    });
  });

  it("for a while after the turn", () => {
    const over = { ...said, doneAt: NOW - 30_000, ok: true };
    assert.equal(captions(state({ turn: over, captions: true }), NOW), null);
  });

  it("the end of long answers, cut at a word", () => {
    const long = { ...said, answer: "one two three four five six" };
    assert.equal(captions(state({ turn: long, captions: true }), NOW, 12).answer, "…five six");
    const word = { ...said, answer: "supercalifragilistic" };
    assert.equal(captions(state({ turn: word, captions: true }), NOW, 5).answer, "…istic", "no space in it");
  });
});

describe("words as they come", () => {
  it("those already there stay, others are replaced or appended", () => {
    assert.deepEqual(wordChanges(["Grog,", "will", "eat"], "Grog, will it rain?"), {
      words: [
        { word: "Grog,", change: "stay" },
        { word: "will", change: "stay" },
        { word: "it", change: "replace" },
        { word: "rain?", change: "append" },
      ],
      gone: 0,
    });
  });

  it("and the last ones go when there are fewer", () => {
    assert.deepEqual(wordChanges(["Grog,", "will", "it"], "  Grog,  "), {
      words: [{ word: "Grog,", change: "stay" }],
      gone: 2,
    });
    assert.deepEqual(wordChanges([], ""), { words: [], gone: 0 });
  });
});

describe("the last turns", () => {
  it("their tools, how long they took, and whether they went well", () => {
    const s = state({
      recent: [
        { at: 3, conversation: "voice", ms: 4200, tools: ["web_search", "web_search"], ok: true },
        { at: 2, conversation: "default", ms: 900, tools: [], ok: false },
        { at: 1, conversation: "voice", ms: 1100, tools: [], ok: true },
      ],
    });
    assert.deepEqual(recent(s), [
      { at: 3, icon: "web", label: "Web", took: "4.2 s", ok: true },
      { at: 2, icon: "answering", label: "Answer", took: "0.9 s", ok: false },
      { at: 1, icon: "voice", label: "Answer", took: "1.1 s", ok: true },
    ]);
  });

  it("all of them, without a voice too", () => {
    const recentTurns = [4, 3, 2, 1].map((at) => ({ at, conversation: "voice", ms: 1000, tools: [], ok: true }));
    const s = state({ voice: undefined, recent: recentTurns });
    assert.deepEqual(
      recent(s).map((r) => [r.at, r.icon]),
      [
        [4, "answering"],
        [3, "answering"],
        [2, "answering"],
        [1, "answering"],
      ],
    );
    assert.deepEqual(recent(null), []);
  });
});

describe("what it consumed", () => {
  it("with the subscription, how much of its limits is used", () => {
    const nowS = NOW / 1000;
    const u = {
      auth: "subscription",
      today: { turns: 4 },
      quota: {
        plan: "plus",
        windows: [
          { label: "5h", usedPercent: 23.4, resetsAt: nowS + 7800 },
          { label: "7d", usedPercent: 140, resetsAt: nowS + 273600 },
          { label: "1d", usedPercent: 50, resetsAt: nowS - 1 },
        ],
      },
    };
    assert.deepEqual(usage(u, NOW), {
      kind: "subscription",
      title: "ChatGPT Plus",
      windows: [
        { label: "5h", percent: 23, level: "ok", resetsIn: "2h 10m" },
        { label: "7d", percent: 100, level: "high", resetsIn: "3d 4h" },
        { label: "1d", percent: 0, level: "ok", resetsIn: null },
      ],
    });
    assert.deepEqual(usage({ auth: "subscription" }, NOW), { kind: "subscription", title: "Subscription", windows: [] });
  });

  it("with an API key, what it spent", () => {
    const u = {
      auth: "api-key",
      today: { usd: 0.0312, turns: 3 },
      month: { usd: 1.249, unpriced: 2 },
      days: [
        { date: "2026-10-15", usd: 0.5 },
        { date: "2026-10-16", usd: 0.25 },
      ],
    };
    assert.deepEqual(usage(u, NOW), {
      kind: "api-key",
      month: "$1.25",
      today: "$0.0312",
      unpriced: 2,
      chart: [
        { date: "2026-10-15", height: 1 },
        { date: "2026-10-16", height: 0.5 },
      ],
    });
  });

  it("how much of a limit is used", () => {
    assert.deepEqual([0, 69, 70, 89, 90, 100].map(level), ["ok", "ok", "warn", "warn", "high", "high"]);
  });

  it("nothing without credentials", () => {
    assert.equal(usage({ auth: "none" }, NOW), null);
    assert.equal(usage(null, NOW), null);
  });
});

describe("words and numbers", () => {
  it("tools by name", () => {
    assert.equal(tool("volume").label, "Adjusting the volume");
    assert.deepEqual(tool("new_thing"), { label: "New thing", short: "New thing", icon: "tool" });
    assert.deepEqual([capitalize("plus"), capitalize(""), capitalize(null)], ["Plus", "", ""]);
  });

  it("seconds, never below zero", () => {
    assert.deepEqual([seconds(1840), seconds(0), seconds(-250)], ["1.8 s", "0.0 s", "0.0 s"]);
  });

  it("the time and date in grog's time zone, in English", () => {
    const at = Date.UTC(2026, 9, 17, 0, 5);
    const utc = timeFormats("UTC");
    assert.equal(utc.clock.format(at), "00:05");
    assert.equal(utc.date.format(at), "Saturday, October 17");
    const sp = timeFormats("America/Sao_Paulo");
    assert.equal(sp.clock.format(at), "21:05");
    assert.equal(sp.date.format(at), "Friday, October 16");
    const here = new Intl.DateTimeFormat().resolvedOptions().timeZone;
    assert.equal(timeFormats(undefined).clock.resolvedOptions().timeZone, here, "the browser's without one");
    assert.equal(timeFormats("Nowhere/Atlantis").clock.resolvedOptions().timeZone, here, "or one it doesn't know");
  });

  it("night, from 22:00 to 7:00 in grog's time zone", () => {
    assert.deepEqual([21, 22, 0, 6, 7, 12].map(isNight), [false, true, true, true, false, false]);
    const at = Date.UTC(2026, 9, 17, 0, 5);
    assert.equal(Number(timeFormats("UTC").hour.format(at)), 0);
    assert.equal(Number(timeFormats("America/Sao_Paulo").hour.format(at)), 21);
  });

  it("dollars and durations", () => {
    assert.deepEqual([usd(0), usd(0.00042), usd(0.1), usd(12.345)], ["$0.00", "$0.0004", "$0.10", "$12.35"]);
    assert.deepEqual(
      [30, 2700, 7200, 7800, 259200, 273600].map(duration),
      ["<1m", "45m", "2h", "2h 10m", "3d", "3d 4h"],
    );
  });

  it("how grog is paid", () => {
    assert.equal(account(state({ auth: "subscription", usage: { quota: { plan: "pro" } } })), "ChatGPT Pro");
    assert.equal(account(state({ auth: "subscription" })), "ChatGPT");
    assert.equal(account(state()), "API key");
    assert.equal(account(state({ auth: "none" })), "no credentials");
  });
});

describe("signing in", () => {
  const help = {
    text: "The first time, ChatGPT has to allow it.",
    url: "https://chatgpt.com/#settings/Security",
  };
  const waiting = {
    status: "pending",
    method: "device",
    label: "ChatGPT",
    code: "ABCD-12345",
    url: "https://auth.openai.com/codex/device",
    expiresAt: NOW + 14 * 60_000 + 30_000,
    help,
  };

  it("with a code, where to enter it, and what may have to be done first", () => {
    assert.deepEqual(signIn(state({ auth: "none", login: waiting }), NOW), {
      title: "Sign in with ChatGPT",
      code: "ABCD-12345",
      url: "https://auth.openai.com/codex/device",
      shortUrl: "auth.openai.com/codex/device",
      qr: "qr/auth-openai-com-codex-device.svg",
      expires: "expires in 14m",
      help: {
        text: "The first time, ChatGPT has to allow it.",
        url: "https://chatgpt.com/#settings/Security",
        shortUrl: "chatgpt.com/#settings/Security",
      },
    });
    assert.equal(signIn(state({ auth: "none", login: { ...waiting, help: null } }), NOW).help, null);
  });

  it("says what's going on without a code", () => {
    const note = (login) => signIn(state({ auth: "none", login }), NOW).note;
    assert.equal(note(null), "Getting a code…");
    assert.equal(note({ status: "expired", method: "device", label: "ChatGPT" }), "Getting a new code…");
    assert.equal(note({ status: "exchanging", method: "device", label: "ChatGPT" }), "Signing in…");
    assert.equal(note({ status: "pending", method: "browser", label: "ChatGPT" }), "Waiting for the browser");
    assert.deepEqual(signIn(state({ auth: "none", login: { status: "error", label: "ChatGPT", error: "Offline." } }), NOW), {
      title: "Sign in with ChatGPT",
      note: "Couldn't get a code: trying again in a minute",
      error: "Offline.",
    });
  });

  it("only while grog has nothing to pay with", () => {
    assert.equal(signIn(state({ login: waiting }), NOW), null);
    assert.equal(signIn(state({ auth: "subscription", login: waiting }), NOW), null);
    assert.equal(signIn(null, NOW), null);
  });

  it("QR codes are named after their page", () => {
    assert.deepEqual(
      [qrFile("https://auth.openai.com/codex/device"), qrFile("http://Example.com/A_b?c=d#e"), qrFile(""), qrFile(null)],
      ["qr/auth-openai-com-codex-device.svg", "qr/example-com-a-b-c-d-e.svg", null, null],
    );
  });
});

const voiceIn = (s) => ({ state: s, since: NOW, seen: NOW, conversation: "voice" });

describe("going away, asked to", () => {
  it("says it, also once grog can't be reached, for a while", () => {
    const going = state({ restarting: { action: "restart", at: NOW - 1000 } });
    assert.deepEqual(mode(going, NOW), { mode: "restarting", label: "Restarting", detail: "back in a moment" });
    assert.equal(mode(going, NOW, true).mode, "restarting", "offline");
    assert.equal(mode(going, NOW - 1000 + GOING_MS + 1, true).mode, "offline", "not forever");
    assert.equal(mode(state({ restarting: { action: "reboot", at: NOW } }), NOW).label, "Restarting the machine");
    const off = mode(state({ restarting: { action: "power-off", at: NOW } }), NOW);
    assert.deepEqual([off.label, off.detail], ["Turning off", "someone has to turn it on again"]);
  });

  it("its eye closes", () => {
    const going = state({ restarting: { action: "restart", at: NOW } });
    assert.deepEqual(face(mode(going, NOW), going, NOW), { state: "offline", icon: null });
  });

  it("offline otherwise", () => {
    assert.equal(mode(state(), NOW, true).mode, "offline");
  });
});

describe("its mic off", () => {
  it("says so, and how to turn it on", () => {
    const muted = state({ voice: voiceIn("muted") });
    assert.deepEqual(mode(muted, NOW), { mode: "muted", label: "Mic off", detail: "tap to turn it on" });
    assert.equal(mode(state({ mic: "off" }), NOW).mode, "muted", "before grog-voice says");
    assert.deepEqual(face(mode(muted, NOW), muted, NOW), { state: "muted", icon: "no-mic" });
    assert.equal(reaction("idle", "muted", null, "no-mic"), "swap");
  });
});

describe("a turn asked to stop", () => {
  it("didn't fail", () => {
    const stopped = turn([["thinking", NOW - 2000], ["answering", NOW - 1500]], {
      doneAt: NOW - 1000,
      ok: false,
      stopped: true,
    });
    assert.equal(mode(state({ turn: stopped }), NOW).mode, "idle");
    assert.deepEqual(pipeline(stopped).at(-1), { label: "Stopped", icon: "stop", offset: "1.0 s", status: "done" });
    assert.equal(recent(state({ recent: [{ at: NOW, ms: 900, tools: [], ok: false, stopped: true }] }))[0].ok, true);
  });
});

describe("touching its face", () => {
  const actions = ["listen", "stop", "mic"];

  it("talks to it, without its name", () => {
    assert.deepEqual(tap(state({ actions }), NOW), { action: "listen", label: "Talk to Grog" });
    assert.equal(tap(state({ actions, voice: voiceIn("asked") }), NOW).action, "listen");
  });

  it("stops it while it answers", () => {
    assert.deepEqual(tap(state({ actions, voice: voiceIn("speaking") }), NOW), { action: "stop", label: "Stop" });
    assert.equal(tap(state({ actions, voice: voiceIn("answering") }), NOW).action, "stop");
  });

  it("turns its mic on", () => {
    const on = { action: "listen", label: "Turn the mic on and listen" };
    assert.deepEqual(tap(state({ actions, voice: voiceIn("muted") }), NOW), on);
    assert.deepEqual(tap(state({ actions, mic: "off" }), NOW), on);
  });

  it("does nothing when it can't", () => {
    assert.equal(tap(null, NOW), null);
    assert.equal(tap(state({ actions }), NOW, true), null, "offline");
    assert.equal(tap(state(), NOW), null, "grog can't");
    assert.equal(tap(state({ actions, voice: null }), NOW), null, "no voice");
    assert.equal(tap(state({ actions, voice: voiceIn("loading") }), NOW), null);
    assert.equal(tap(state({ actions, voice: voiceIn("no-mic") }), NOW), null);
    assert.equal(tap(state({ actions, restarting: { action: "restart", at: NOW } }), NOW), null);
  });
});

describe("the actions sheet", () => {
  const all = ["listen", "stop", "mic", "new-conversation", "volume", "restart", "reboot", "power-off"];

  it("lists what grog can do there", () => {
    const items = menu(state({ actions: all, auth: "subscription", volume: { level: 40, muted: false } }));
    assert.deepEqual(
      items.map((i) => i.id),
      ["volume", "mic", "new-conversation", "sign-out", "restart", "reboot", "power-off"],
    );
    assert.deepEqual(items[0], {
      id: "volume",
      kind: "volume",
      label: "Volume",
      level: 40,
      muted: false,
      confirm: false,
      armed: false,
    });
    assert.deepEqual([items[1].kind, items[1].on], ["toggle", true]);
    assert.equal(items[4].label, "Restart Grog");
    assert.deepEqual(
      items.filter((i) => i.confirm).map((i) => i.id),
      ["sign-out", "restart", "reboot", "power-off"],
    );
    assert.deepEqual(
      menu(state({ actions: ["listen", "stop", "mic", "new-conversation"] })).map((i) => i.id),
      ["mic", "new-conversation"],
    );
    assert.deepEqual(menu(null), []);
  });

  it("the volume, unknown at first, and the mic as it is", () => {
    assert.equal(menu(state({ actions: ["volume"] }))[0].level, null);
    assert.equal(menu(state({ actions: ["mic"], mic: "off" }))[0].on, false);
  });

  it("asks for a second tap for what can't be undone", () => {
    const [restart] = menu(state({ actions: ["restart"] }), "restart");
    assert.deepEqual([restart.label, restart.armed], ["Tap again to restart", true]);
    assert.equal(menu(state({ actions: ["mic"] }), "mic")[0].armed, false, "not what can be undone");
  });
});

describe("what's asked of grog", () => {
  it("its actions", () => {
    assert.deepEqual(request("restart", state()), { method: "POST", path: "/v1/actions/restart", body: {} });
    assert.deepEqual(request("listen", state()), { method: "POST", path: "/v1/actions/listen", body: {} });
    assert.deepEqual(request("mic", state({ mic: "on" })).body, { on: false });
    assert.deepEqual(request("mic", state({ mic: "off" })).body, { on: true });
  });

  it("the volume", () => {
    const s = state({ volume: { level: 40, muted: true } });
    assert.deepEqual(request("volume", s, 1).body, { change: VOLUME_STEP });
    assert.deepEqual(request("volume", s, -1).body, { change: -VOLUME_STEP });
    assert.deepEqual(request("volume", s, "mute").body, { mute: false });
    assert.deepEqual(request("volume", s).body, {});
  });

  it("signing out", () => {
    assert.deepEqual(request("sign-out", state({ provider: "openai" })), {
      method: "DELETE",
      path: "/v1/auth/openai",
      body: {},
    });
  });

  it("which close the sheet", () => {
    assert.deepEqual(["volume", "mic", "new-conversation", "restart"].map(closes), [false, false, true, true]);
  });
});

describe("updating", () => {
  const rev = "1234abcdef1234abcdef1234abcdef1234abcdef";
  const update = (stage, extra = {}) => state({ update: { stage, at: NOW - 1000, to: rev, ...extra } });

  it("its version, short", () => {
    assert.equal(version(state({ revision: rev })), "1234abc");
    assert.equal(shortRevision(`${rev}-dirty`), "1234abc-dirty");
    assert.equal(version(state()), null);
  });

  it("says how it goes, under the time", () => {
    assert.deepEqual(updateNotice(update("building"), NOW), {
      label: "Building the update",
      detail: "1234abc",
      icon: "update",
      failed: false,
    });
    assert.equal(updateNotice(update("fetching"), NOW).label, "Looking for an update");
    assert.equal(updateNotice(update("waiting"), NOW).label, "Updating once nobody's talking");
    assert.equal(updateNotice(update("building"), NOW - 1000 + UPDATE_MS + 1), null, "silent too long");
    assert.equal(updateNotice(state(), NOW), null);
  });

  it("and how it went, for a while", () => {
    assert.deepEqual(updateNotice(update("done"), NOW), { label: "Updated to", detail: "1234abc", icon: "done", failed: false });
    assert.equal(updateNotice(update("done", { to: null }), NOW).label, "Updated");
    assert.equal(updateNotice(update("current"), NOW).label, "Already up to date");
    assert.equal(updateNotice(update("done"), NOW - 1000 + UPDATED_MS + 1), null);
    const failed = updateNotice(update("failed", { error: "Couldn't build it" }), NOW);
    assert.deepEqual([failed.label, failed.detail, failed.failed], ["Couldn't update", "Couldn't build it", true]);
    assert.equal(updateNotice(update("failed"), NOW - 1000 + UPDATE_FAILED_MS + 1), null);
  });

  it("restarting into it, its block says so, even offline", () => {
    for (const stage of ["switching", "verifying"]) {
      assert.deepEqual(mode(update(stage), NOW), { mode: "restarting", label: "Updating", detail: "back in a moment" });
      assert.equal(mode(update(stage), NOW, true).mode, "restarting");
    }
    assert.equal(mode(update("building"), NOW).mode, "idle", "it still listens while it builds");
    assert.equal(mode(update("switching"), NOW - 1000 + GOING_MS + 1, true).mode, "offline", "not forever");
  });

  it("from the actions sheet, with a second tap", () => {
    const items = menu(state({ actions: ["update", "restart"] }));
    assert.deepEqual(items.map((i) => i.id), ["update", "restart"]);
    assert.deepEqual([items[0].label, items[0].icon, items[0].confirm], ["Update Grog", "update", true]);
    assert.equal(menu(state({ actions: ["update"] }), "update")[0].label, "Tap again to update");
    assert.deepEqual(request("update", state()), { method: "POST", path: "/v1/actions/update", body: {} });
    assert.equal(closes("update"), true);
  });

  it("as a step of a turn", () => {
    assert.deepEqual(tool("update"), { label: "Looking for an update", short: "Update", icon: "update" });
  });
});

describe("pairing a computer", () => {
  const paired = (status, extra = {}) =>
    state({
      pairing: { status, until: NOW + 4 * 60_000, addresses: ["192.168.15.17"], port: PAIRING_PORT, at: NOW - 1000, ...extra },
    });

  it("says what to run on it, while grog lets one", () => {
    assert.deepEqual(pairing(paired("open"), NOW), {
      stage: "open",
      title: "Add a computer",
      detail: "",
      hint: "On it, run",
      command: "grog pair 192.168.15.17",
      code: "",
      expires: "closes in 4m",
      note: "It needs grog installed, and an SSH server.",
      failed: false,
      buttons: [{ id: "pair-cancel", label: "Cancel", primary: false }],
    });
    assert.equal(pairing(paired("open"), NOW + 4 * 60_000), null, "not once it's closed");
  });

  it("the port too, when it isn't the usual one", () => {
    assert.equal(pairCommand({ addresses: ["10.0.0.2"], port: 9000 }), "grog pair 10.0.0.2 --port 9000");
    assert.equal(pairCommand({ addresses: [], port: PAIRING_PORT }), "grog pair <grog's address>");
  });

  it("shows the code of the computer asking, to allow it or not", () => {
    const asking = pairing(paired("asking", { name: "deluxe", user: "greg", code: "718945" }), NOW);
    assert.deepEqual(
      [asking.title, asking.detail, asking.hint, asking.code],
      ["deluxe wants to pair", "as greg", "Allow it if it shows the same code", "718 945"],
    );
    assert.deepEqual(
      asking.buttons.map((b) => [b.id, b.label, b.primary]),
      [
        ["pair-allow", "Allow", true],
        ["pair-deny", "Deny", false],
      ],
    );
  });

  it("and how it went, for a moment", () => {
    const after = (status, extra) => pairing(paired(status, { name: "deluxe", ...extra }), NOW);
    assert.deepEqual([after("allowed").title, after("allowed").note], ["Pairing deluxe", "waiting for it to finish"]);
    assert.deepEqual([after("paired").title, after("paired").note], ["deluxe is paired", "ask Grog to act on it"]);
    assert.equal(after("denied").note, "deluxe wasn't allowed");
    assert.equal(after("expired").note, "no computer asked in time");
    const failed = after("failed", { error: "deluxe can't be reached" });
    assert.deepEqual([failed.title, failed.note, failed.failed], ["Couldn't pair", "deluxe can't be reached", true]);
    assert.equal(pairing(paired("paired", { name: "deluxe" }), NOW - 1000 + PAIRED_MS + 1), null);
    assert.equal(after("closed"), null, "nothing to say once someone closed it");
    assert.equal(pairing(state(), NOW), null);
  });

  it("from the actions sheet, and what its buttons ask", () => {
    const items = menu(state({ actions: ["new-conversation", "pair", "restart"] }));
    assert.deepEqual(items.map((i) => i.id), ["new-conversation", "pair", "restart"]);
    assert.deepEqual([items[1].label, items[1].icon, items[1].confirm], ["Add a computer", "computer", false]);
    assert.deepEqual(request("pair", state()), { method: "POST", path: "/v1/actions/pair", body: {} });
    assert.deepEqual(request("pair-allow", state()), { method: "POST", path: "/v1/pairing/allow", body: {} });
    assert.deepEqual(request("pair-deny", state()), { method: "POST", path: "/v1/pairing/deny", body: {} });
    assert.deepEqual(request("pair-cancel", state()), { method: "DELETE", path: "/v1/pairing", body: {} });
    assert.equal(closes("pair"), true, "the pairing shows instead");
  });

  it("acting on one, as a step of a turn", () => {
    assert.deepEqual(tool("computer"), { label: "Using a computer", short: "Computer", icon: "computer" });
  });
});

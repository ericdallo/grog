import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  account,
  CAPTIONS_MS,
  capitalize,
  captions,
  duration,
  elapsed,
  ERROR_MS,
  glyph,
  level,
  mode,
  pipeline,
  recent,
  seconds,
  STALE_MS,
  stale,
  timeFormats,
  tool,
  usage,
  usd,
  VOICE_STALE_MS,
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
    assert.equal(mode(voice("loading"), NOW).mode, "loading");
    assert.equal(mode(voice("no-mic"), NOW).mode, "no-mic");
  });

  it("is ready without a voice, or one that stopped saying", () => {
    assert.deepEqual(mode(state({ voice: null }), NOW), { mode: "idle", label: "Ready", detail: "" });
    const stale = state({ voice: { state: "hearing", since: 0, seen: NOW - VOICE_STALE_MS - 1 } });
    assert.deepEqual(mode(stale, NOW), { mode: "idle", label: "Ready", detail: "its voice isn't running" });
  });

  it("thinks, uses its tools, answers and speaks", () => {
    const at = (steps, voice = "answering") =>
      mode(state({ turn: turn(steps), voice: { state: voice, since: NOW, seen: NOW } }), NOW);
    assert.deepEqual(at([["thinking", 1]]), { mode: "thinking", label: "Thinking", detail: "" });
    assert.deepEqual(at([["thinking", 1], ["tool", 2, "web_search"]]), {
      mode: "tool",
      label: "Searching the web",
      detail: "",
      icon: "web",
    });
    assert.equal(at([["thinking", 1], ["answering", 2]]).mode, "answering");
    assert.equal(at([["thinking", 1], ["answering", 2], ["speaking", 3]], "speaking").mode, "speaking");
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

describe("what its block of color shows", () => {
  it("a sign of what grog is doing", () => {
    const modes = ["idle", "hearing", "awake", "thinking", "answering", "speaking", "loading", "error", "no-mic", "offline"];
    assert.deepEqual(
      modes.map((m) => glyph({ mode: m }).kind),
      ["mic", "waves", "waves", "dots", "lines", "eq", "loading", "failed", "no-mic", "offline"],
    );
  });

  it("the tool at work", () => {
    assert.deepEqual(glyph(mode(state({ turn: turn([["tool", 1, "volume"]]) }), NOW)), { kind: "tool", icon: "volume" });
    assert.deepEqual(glyph({ mode: "tool" }), { kind: "tool", icon: "tool" });
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

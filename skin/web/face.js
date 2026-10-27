// grog's face: Lid, a ring with a pupil and soft, arched lids, on its block
// of color. Each of its states is a pose of the same parts (skin.css); this
// draws it once, makes it do what it does in each state, and plays what goes
// on the way from one to the next (view.js says which). Only transform and
// opacity move: at rest, short things now and then (blinks, glances); loops
// only while something happens. With reduced motion, only its pose changes.

import { ICONS } from "./icons.js";
import { reaction } from "./view.js";

// How far down the upper lid goes to close the eye, whatever its pose.
const CLOSED = 130;
// When, in a 260 ms blink, the pupil is out of sight: it swaps with an icon
// then.
const SWAP_MS = 125;

// viewBox -100 -112 200 212, the eye at 0,0: a ring and the cuts that turn on
// it, a pupil with its glint, an icon in its place, and two lids, each an
// outer group posed by state and an inner one for blinks. Their covers, of
// the block's color, are clipped a little past the ring (no hint of it shows
// above them), their lines right at it. One on the page: fixed ids.
const SVG = `<svg class="face" viewBox="-100 -112 200 212">
  <defs><clipPath id="face-cover"><circle r="64"/></clipPath><clipPath id="face-line"><circle r="62.5"/></clipPath></defs>
  <g class="head"><g class="fx"><g class="talk"><g class="eye">
    <g class="look"><g class="pupil"><circle class="dot" r="23"/><circle class="glint" cx="9" cy="-9" r="7"/></g></g>
    <g class="sym"><g transform="scale(2.3) translate(-12 -12)"></g></g>
    <circle class="ring" r="56"/><circle class="cuts" r="56"/>
    <g clip-path="url(#face-cover)">
      <g class="lidT"><g class="blinkT"><path class="cover" d="M-90 -260H90V-36Q0 -96 -90 -36Z"/></g></g>
      <g class="lidB"><path class="cover" d="M-90 280H90V100Q0 30 -90 100Z"/></g>
    </g>
    <g clip-path="url(#face-line)">
      <g class="lidT"><g class="blinkT"><path class="line" d="M-90 -36Q0 -96 90 -36"/></g></g>
      <g class="lidB"><path class="line" d="M-90 100Q0 30 90 100"/></g>
    </g>
  </g></g></g></g>
</svg>`;

// The upper lid going down and back.
const BLINK = [
  { transform: "translateY(0)" },
  { transform: `translateY(${CLOSED}px)`, offset: 0.45 },
  { transform: "translateY(0)" },
];

const rand = (a, b) => a + Math.random() * (b - a);
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

/** grog's face, drawn in `slot`, set to a state at a time. */
export class Face {
  constructor(slot) {
    slot.innerHTML = SVG;
    this.svg = slot.querySelector("svg");
    this.quiet = matchMedia("(prefers-reduced-motion: reduce)").matches;
    this.timers = [];
    this.state = null;
    this.icon = null;
    // How far it looks to the right, at who talks to grog (px).
    this.look = 0;
    // Until when a reaction plays (performance.now()): no blinks meanwhile.
    this.busy = 0;
    if (!this.quiet) this.blinks();
  }

  /**
   * Goes to `state` (view.js's face()), its pupil turned into `icon` if any,
   * looking `look` px to the right (at who talks to grog), with what plays
   * on the way. The pupil and an icon swap behind a blink.
   */
  set(state, icon = null, look = 0) {
    if (state === this.state && icon === this.icon) {
      if (look !== this.look) {
        this.look = look;
        const [x, y] = GAZE[state] ?? [0, 0];
        this.gaze(x + look, y);
      }
      return;
    }
    const move = this.quiet ? null : reaction(this.state, state, this.icon, icon);
    this.state = state;
    this.icon = icon;
    this.look = look;
    this.stop();
    if (move === "swap") {
      this.react(".blinkT", BLINK, 260, "ease-in-out");
      this.later(SWAP_MS, () => this.pose(true));
    } else {
      this.pose(false);
      if (move) MOVES[move](this);
    }
  }

  /** A look at the clock, on the hour, when it's resting. */
  lookAtClock() {
    if (!this.quiet && (this.state === "idle" || this.state === "sleepy")) MOVES.clock(this);
  }

  /** Touched: a quick squash, before grog does what that asks. */
  poke() {
    MOVES.poke(this);
  }

  /** Its state's pose, and what it does there. `swap`: the pupil and the icon change at once. */
  pose(swap) {
    if (swap) this.svg.classList.add("swap");
    if (this.icon) this.svg.querySelector(".sym g").innerHTML = ICONS[this.icon] ?? ICONS.tool;
    this.svg.dataset.state = this.state;
    act(this, this.state);
    if (swap) {
      // Its styles worked out now, without transitions; then they're back.
      void getComputedStyle(this.svg.querySelector(".pupil")).opacity;
      this.svg.classList.remove("swap");
    }
  }

  /** Stops what the last state was doing: it settles back. */
  stop() {
    this.timers.forEach(clearTimeout);
    this.timers = [];
    for (const prop of ["--talk", "--relax", "--peek"]) this.svg.style.removeProperty(prop);
  }

  /** Calls `f` in `ms`, unless the state changed by then. */
  later(ms, f) {
    if (!this.quiet) this.timers.push(setTimeout(f, ms));
  }

  /** Calls `f` every `min` to `max` ms, while in this state. */
  loop(min, max, f) {
    const go = () =>
      this.later(rand(min, max), () => {
        f();
        go();
      });
    go();
  }

  prop(name, value) {
    this.svg.style.setProperty(name, value);
  }

  gaze(x, y) {
    this.prop("--gx", `${x}px`);
    this.prop("--gy", `${y}px`);
  }

  /** Plays a one-off animation on what `selector` finds, `ms` long. */
  play(selector, frames, ms, easing = "ease-out") {
    if (this.quiet) return;
    for (const el of this.svg.querySelectorAll(selector)) el.animate(frames, { duration: ms, easing });
  }

  /** A reaction: it plays, and it doesn't blink until it's over. */
  react(selector, frames, ms, easing) {
    this.busy = performance.now() + ms;
    this.play(selector, frames, ms, easing);
  }

  blink(ms = 210) {
    this.play(".blinkT", BLINK, ms, "ease-in-out");
  }

  /** Blinks now and then, as its state does (BLINKS), but while a reaction plays. */
  blinks() {
    const [min, max] = BLINKS[this.state]?.every ?? [2400, 6500];
    setTimeout(
      () => {
        const how = BLINKS[this.state];
        if (how && performance.now() > this.busy) {
          this.blink(how.ms);
          if (Math.random() < how.twice) setTimeout(() => this.blink(how.ms), how.ms + 90);
        }
        this.blinks();
      },
      rand(min, max),
    );
  }
}

// How it blinks by state: every so often (ms), how long a blink takes (ms),
// how often twice in a row. It doesn't in the others: it's busy (a tool,
// speaking), dozing, its eye is closed, or something else.
const BLINKS = {
  idle: { every: [2400, 6500], ms: 210, twice: 0.25 },
  pleased: { every: [2400, 6500], ms: 210, twice: 0.25 },
  sleepy: { every: [5000, 10000], ms: 400, twice: 0.1 },
  hearing: { every: [2400, 6500], ms: 210, twice: 0.25 },
  awake: { every: [2400, 6500], ms: 210, twice: 0.25 },
  asked: { every: [2400, 6500], ms: 210, twice: 0.25 },
  thinking: { every: [2400, 6500], ms: 210, twice: 0.25 },
  still: { every: [2000, 4500], ms: 240, twice: 0.3 },
  writing: { every: [3000, 7000], ms: 210, twice: 0.1 },
  deaf: { every: [1800, 4200], ms: 190, twice: 0.4 },
  login: { every: [2400, 6500], ms: 210, twice: 0.25 },
  muted: { every: [4000, 9000], ms: 300, twice: 0.1 },
};

// Where it looks by state, [x, y]; straight ahead in the others.
const GAZE = {
  hearing: [0, -2],
  awake: [0, -3],
  asked: [5, -4],
  pleased: [0, -4],
  error: [-4, 10],
  loading: [0, 4],
  offline: [0, 8],
};

/** What it does in `state`, besides its pose: it looks where it does, and at who talks to grog. */
function act(face, state) {
  const [x, y] = GAZE[state] ?? [0, 0];
  face.gaze(x + face.look, y);
  if (face.quiet) return;
  switch (state) {
    case "idle":
      face.loop(3000, 7500, () => glance(face, 13));
      face.loop(7000, 13000, () => relax(face, 12));
      break;
    case "sleepy":
      face.loop(8000, 15000, () => glance(face, 8));
      face.loop(9000, 16000, () => relax(face, 14));
      break;
    case "dozing":
      face.loop(6000, 12000, () => MOVES["nod-off"](face));
      break;
    case "deaf":
      face.loop(3500, 7000, () => glance(face, 14));
      break;
    case "thinking":
      sway(face, 14, -13, 1500, 2600);
      break;
    case "still":
      sway(face, 18, -15, 2200, 3400);
      break;
    case "writing":
      write(face);
      break;
    case "speaking":
      talk(face);
      break;
    case "loading":
      peeks(face);
      break;
    case "ringing":
      face.react(".fx", JINGLE, 640);
      face.loop(1500, 2000, () => face.react(".fx", JINGLE, 640));
      break;
  }
}

/** Looks somewhere for a moment, `d` away, then back. */
function glance(face, d) {
  const [x, y] = pick([
    [-d, 0],
    [d, 0],
    [-d, -d / 2],
    [d, d / 3],
    [0, -d / 2],
  ]);
  face.gaze(x, y);
  face.later(rand(700, 1700), () => face.gaze(0, 0));
}

/** Looks from one side to the other, again and again. */
function sway(face, x, y, min, max) {
  let side = 1;
  face.gaze(x, y);
  face.loop(min, max, () => {
    side = -side;
    face.gaze(side * x, y);
  });
}

/** Resting, its lid sinks `px` for a while. */
function relax(face, px) {
  face.prop("--relax", `${px}px`);
  face.later(rand(1200, 2200), () => face.prop("--relax", "0px"));
}

/** Writing: it reads what it writes, at its right, a line at a time. */
function write(face) {
  let i = 0;
  const step = () => {
    face.gaze(10 + (i % 3) * 7, -2 + (Math.floor(i / 3) % 3) * 5);
    i++;
  };
  step();
  face.loop(380, 560, step);
}

/** Speaking: --talk keeps changing, and the face bounces with it. */
function talk(face) {
  const go = () => face.prop("--talk", rand(0, 1).toFixed(2));
  go();
  face.loop(110, 170, go);
}

/** Waking up: asleep, it opens its eye for a moment now and then. */
function peeks(face) {
  const peek = () => {
    face.prop("--peek", "1");
    face.later(rand(1000, 1600), () => face.prop("--peek", "0"));
  };
  face.later(700, peek);
  face.loop(3000, 4600, peek);
}

// Dozing: its lid sinks slowly, its head with it, until it catches itself.
const SINK = [
  { transform: "translateY(0)", easing: "ease-in" },
  { transform: "translateY(28px)", offset: 0.8, easing: "cubic-bezier(0.3, 1.4, 0.5, 1)" },
  { transform: "translateY(0)" },
];
const DIP = [
  { transform: "translateY(0)", easing: "ease-in" },
  { transform: "translateY(7px)", offset: 0.8, easing: "cubic-bezier(0.3, 1.4, 0.5, 1)" },
  { transform: "translateY(0)" },
];
// Called: a quick squash and stretch.
const POP = [
  { transform: "scale(1)" },
  { transform: "scale(1.06, 0.9)", offset: 0.22 },
  { transform: "scale(0.97, 1.06)", offset: 0.55 },
  { transform: "scale(1)" },
];
// Something failed: it flinches.
const FLINCH = [
  { transform: "translate(0, 0) scale(1, 1)" },
  { transform: "translate(-7px, 3px) scale(1.07, 0.86)", offset: 0.18 },
  { transform: "translate(6px, 0) scale(0.97, 1.03)", offset: 0.4 },
  { transform: "translate(-4px, 0) scale(1, 1)", offset: 0.6 },
  { transform: "translate(2px, 0) scale(1, 1)", offset: 0.8 },
  { transform: "translate(0, 0) scale(1, 1)" },
];
// It went well: a little hop.
const HOP = [
  { transform: "translateY(0) scale(1, 1)" },
  { transform: "translateY(3px) scale(1.05, 0.93)", offset: 0.16 },
  { transform: "translateY(-11px) scale(0.97, 1.04)", offset: 0.46 },
  { transform: "translateY(2px) scale(1.03, 0.97)", offset: 0.76 },
  { transform: "translateY(0) scale(1, 1)" },
];
// A timer going off: it shakes like an alarm clock.
const JINGLE = [
  { transform: "rotate(0deg)" },
  { transform: "rotate(-8deg)", offset: 0.12 },
  { transform: "rotate(7deg)", offset: 0.3 },
  { transform: "rotate(-6deg)", offset: 0.48 },
  { transform: "rotate(4deg)", offset: 0.66 },
  { transform: "rotate(-2deg)", offset: 0.84 },
  { transform: "rotate(0deg)" },
];
// Touched: pressed in, and back.
const POKE = [
  { transform: "scale(1)" },
  { transform: "scale(0.92)", offset: 0.3 },
  { transform: "scale(1)" },
];
// Got it: a nod.
const NOD = [
  { transform: "translateY(0)" },
  { transform: "translateY(9px)", offset: 0.4 },
  { transform: "translateY(-2px)", offset: 0.75 },
  { transform: "translateY(0)" },
];
// Back: it tries to open its eye, gives up, then opens it (the upper lid, from
// closed).
const WAKE = [
  { transform: `translateY(${CLOSED}px)` },
  { transform: `translateY(${CLOSED}px)`, offset: 0.25 },
  { transform: `translateY(${CLOSED * 0.45}px)`, offset: 0.45 },
  { transform: `translateY(${CLOSED * 0.85}px)`, offset: 0.58 },
  { transform: `translateY(${CLOSED * 0.85}px)`, offset: 0.7 },
  { transform: "translateY(0)" },
];

// What plays on the way to a state (view.js's reaction()), nodding off while
// it dozes, and a look at the clock (up and right), on the hour.
const MOVES = {
  poke: (face) => face.react(".fx", POKE, 240),
  pop: (face) => face.react(".fx", POP, 460),
  flinch: (face) => face.react(".fx", FLINCH, 480),
  hop: (face) => face.react(".fx", HOP, 560),
  nod: (face) => face.react(".fx", NOD, 520, "ease-in-out"),
  wake: (face) => face.react(".blinkT", WAKE, 1700, "ease-in-out"),
  "never-mind": (face) => {
    face.react(".blinkT", BLINK, 420, "ease-in-out");
    face.gaze(-12, 7);
    face.later(1000, () => face.gaze(0, 0));
  },
  "nod-off": (face) => {
    face.react(".blinkT", SINK, 2600, "linear");
    face.play(".fx", DIP, 2600, "linear");
  },
  clock: (face) => {
    face.gaze(17, -15);
    face.later(1400, () => face.gaze(0, 0));
  },
};

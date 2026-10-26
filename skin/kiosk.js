// grog-skin: grog's skin fullscreen, in a window of its own (GTK 4 and
// WebKitGTK, which GNOME brings). Run it with `gjs -m kiosk.js [URL]`, URL
// being grog's (http://127.0.0.1:8484/ by default).
//
// It shows nothing but grog's page, tries again while grog isn't up, and, as
// GNOME starts in its overview, closes it: the skin is what the screen
// shows. The screen stays on while something happens, and goes off once
// nothing did for a while, as the page says (`skin.screenOffMinutes`), to
// save energy: touched, clicked or a key pressed then, it only wakes. F11
// toggles fullscreen, Ctrl+R reloads, Ctrl+Q quits. Once the GPU was reset
// (it hung), it exits failing, to be started again: the window can't draw on
// the GPU it had.

import Gdk from "gi://Gdk?version=4.0";
import Gio from "gi://Gio";
import GLib from "gi://GLib";
import GLibUnix from "gi://GLibUnix";
import Gtk from "gi://Gtk?version=4.0";
import WebKit from "gi://WebKit?version=6.0";
import System from "system";

Gio._promisify(Gio.DBusConnection.prototype, "call");
Gio._promisify(Gio.Subprocess.prototype, "communicate_utf8_async");

// Hears from the kernel's devices (libgudev): without it, the GPU isn't watched.
const GUdev = await import("gi://GUdev?version=1.0").then((m) => m.default).catch(() => null);

const base = (ARGV[0] ?? "http://127.0.0.1:8484/").replace(/\/?$/, "/");
const url = `${base}?kiosk`;
const RETRY_S = 2;

function later(seconds, f) {
  GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, seconds, () => {
    f();
    return GLib.SOURCE_REMOVE;
  });
}

/** Closes GNOME Shell's overview; elsewhere, nothing happens. */
function hideOverview() {
  Gio.DBus.session.call(
    "org.gnome.Shell",
    "/org/gnome/Shell",
    "org.freedesktop.DBus.Properties",
    "Set",
    new GLib.Variant("(ssv)", ["org.gnome.Shell", "OverviewActive", new GLib.Variant("b", false)]),
    null,
    Gio.DBusCallFlags.NONE,
    -1,
    null,
    (bus, result) => {
      try {
        bus.call_finish(result);
      } catch {
        // Not GNOME.
      }
    },
  );
}

/** Whether the machine has a GPU WebKit could draw with (a DRM render node). */
function hasGpu() {
  try {
    const dri = Gio.File.new_for_path("/dev/dri");
    const files = dri.enumerate_children("standard::name", Gio.FileQueryInfoFlags.NONE, null);
    for (let info = files.next_file(null); info; info = files.next_file(null)) {
      if (info.get_name().startsWith("renderD")) return true;
    }
  } catch {
    // No /dev/dri.
  }
  return false;
}

/**
 * Calls `f` once a GPU was reset, as i915 tells (RESET=1 on its card): the
 * window's renderer lost its GPU then, and draws nothing more, for good.
 * Returns what watches, to keep.
 */
function onGpuReset(f) {
  if (!GUdev) return null;
  const client = GUdev.Client.new(["drm"]);
  client.connect("uevent", (_client, action, device) => {
    if (action === "change" && device.get_property("RESET") === "1") f();
  });
  return client;
}

// GNOME's display settings (Mutter's), and its power save modes.
const MUTTER = ["org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig"];
const POWER_ON = 0;
const POWER_OFF = 3;

/** Mutter's power save mode, or null: not GNOME. */
async function powerSaveMode() {
  try {
    const args = new GLib.Variant("(ss)", [MUTTER[0], "PowerSaveMode"]);
    const reply = await Gio.DBus.session.call(...MUTTER, "org.freedesktop.DBus.Properties", "Get", args,
      new GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, -1, null);
    return reply.recursiveUnpack()[0];
  } catch {
    return null;
  }
}

/**
 * GNOME's way of turning the screen off and on: Mutter's power save mode.
 * Once it's on again by other means, `woken` is called. null: not GNOME, or
 * it can't (-1, a screen without power saving).
 */
async function mutterScreen(woken) {
  const mode = await powerSaveMode();
  if (mode === null || mode < 0) return null;
  const set = (on) => {
    const args = new GLib.Variant("(ssv)", [MUTTER[0], "PowerSaveMode", new GLib.Variant("i", on ? POWER_ON : POWER_OFF)]);
    return Gio.DBus.session.call(...MUTTER, "org.freedesktop.DBus.Properties", "Set", args, null,
      Gio.DBusCallFlags.NONE, -1, null);
  };
  // On as it starts: a window before it may have left it off.
  if (mode !== POWER_ON) await set(true);
  Gio.DBus.session.signal_subscribe(MUTTER[0], "org.freedesktop.DBus.Properties", "PropertiesChanged", MUTTER[1],
    null, Gio.DBusSignalFlags.NONE, (...signal) => {
      if (signal.at(-1).recursiveUnpack()[1].PowerSaveMode === POWER_ON) woken();
    });
  return { name: "GNOME", set };
}

/** Runs `argv`: what it printed, or throws saying why it failed. */
async function run(argv) {
  const proc = Gio.Subprocess.new(argv, Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE);
  const [out, err] = await proc.communicate_utf8_async(null, null);
  if (!proc.get_successful()) throw new Error(`${argv[0]}: ${err.trim() || "it failed"}`);
  return out;
}

/** The compositor's outputs, as wlr-randr says ([{name, enabled...}]), or null when it can't. */
async function outputs() {
  try {
    return JSON.parse(await run(["wlr-randr", "--json"]));
  } catch {
    return null;
  }
}

/**
 * The way of wlroots compositors (cage, sway...): their outputs, disabled
 * and enabled again, with wlr-randr (their output management, as cage has
 * no power management). The screen then gets no signal, as with power
 * saving. null: not one of them.
 */
async function wlrootsScreen() {
  if (!GLib.getenv("WAYLAND_DISPLAY") || (await outputs()) === null) return null;
  // The outputs it turned off, to turn on again (those still there).
  let off = [];
  const set = async (on) => {
    if (on && !off.length) return;
    const there = JSON.parse(await run(["wlr-randr", "--json"]));
    const names = on
      ? off.filter((name) => there.some((o) => o.name === name))
      : there.filter((o) => o.enabled).map((o) => o.name);
    if (names.length) await run(["wlr-randr", ...names.flatMap((name) => ["--output", name, on ? "--on" : "--off"])]);
    off = on ? [] : names;
  };
  return { name: "wlr-randr", set };
}

// What wakes the screen while it's off, and what ends that gesture: from
// the press to the release, the page gets none of it.
const STARTS = [Gdk.EventType.BUTTON_PRESS, Gdk.EventType.TOUCH_BEGIN, Gdk.EventType.KEY_PRESS, Gdk.EventType.SCROLL];
const ENDS = [Gdk.EventType.BUTTON_RELEASE, Gdk.EventType.TOUCH_END, Gdk.EventType.TOUCH_CANCEL, Gdk.EventType.KEY_RELEASE];
const GESTURE = STARTS.concat(ENDS, [Gdk.EventType.MOTION_NOTIFY, Gdk.EventType.TOUCH_UPDATE]);
// A gesture longer than this goes on to the page (a scroll has no end).
const GESTURE_US = 1_500_000;

/**
 * The screen, turned off and on as the page asks (`want`), one change at a
 * time, the last asked winning, through a backend ({name, set(on)}, set
 * once known: `use`). Off, or going off, the gesture waking it goes no
 * further than `input`, and `touched` tells the page.
 */
class Screen {
  constructor(touched) {
    this.touched = touched;
    this.backend = null;
    this.on = true;
    this.wanted = true;
    // What it's being turned to, or null.
    this.turning = null;
    // Until when (monotonic µs) input is the gesture waking it.
    this.waking = 0;
  }

  use(backend) {
    this.backend = backend;
    print(backend ? `Turning the screen off when nothing happens, with ${backend.name}` : "Can't turn this screen off");
    this.turn();
  }

  want(on) {
    this.wanted = on;
    this.turn();
  }

  /** Turned on by other means (GNOME, say): someone woke it. */
  woken() {
    if (this.on || this.turning !== null) return;
    this.on = this.wanted = true;
    print("The screen is on: woken");
    this.touched();
  }

  async turn() {
    if (!this.backend || this.turning !== null) return;
    try {
      while (this.on !== this.wanted) {
        this.turning = this.wanted;
        await this.backend.set(this.turning);
        this.on = this.turning;
        print(`The screen is ${this.on ? "on" : "off"}`);
      }
    } catch (e) {
      printerr(`Couldn't turn the screen ${this.turning ? "on" : "off"}: ${e.message}`);
      this.wanted = this.on;
    } finally {
      this.turning = null;
    }
  }

  /** Whether to stop an input event of `type` before the page gets it: the gesture waking the screen. */
  input(type) {
    if (!GESTURE.includes(type)) return false;
    const now = GLib.get_monotonic_time();
    if (this.backend && (!this.on || this.turning === false) && STARTS.includes(type)) {
      this.waking = now + GESTURE_US;
      this.want(true);
      this.touched();
    }
    if (now >= this.waking) return false;
    if (ENDS.includes(type)) this.waking = 0;
    return true;
  }
}

function makeView() {
  const view = new WebKit.WebView();
  // Without one (a VM's screen, say), WebKit's GPU path shows nothing: the
  // CPU draws it all then, which costs more.
  if (!hasGpu()) view.get_settings().set_hardware_acceleration_policy(WebKit.HardwareAccelerationPolicy.NEVER);
  view.set_background_color(new Gdk.RGBA({ red: 0.184, green: 0.208, blue: 0.259, alpha: 1 }));
  // grog's page only: nothing it links to opens.
  view.connect("decide-policy", (_view, decision, type) => {
    const navigation =
      type === WebKit.PolicyDecisionType.NAVIGATION_ACTION || type === WebKit.PolicyDecisionType.NEW_WINDOW_ACTION;
    if (navigation && !decision.get_navigation_action().get_request().get_uri().startsWith(base)) {
      decision.ignore();
      return true;
    }
    return false;
  });
  view.connect("context-menu", () => true);
  // grog isn't up yet, or restarts: again in a moment.
  view.connect("load-failed", () => {
    later(RETRY_S, () => view.load_uri(url));
    return true;
  });
  // Nor serving its skin yet (a 404, say).
  view.connect("load-changed", (_view, event) => {
    if (event !== WebKit.LoadEvent.FINISHED) return;
    const status = view.get_main_resource()?.get_response()?.get_status_code();
    if (status && status !== 200) later(RETRY_S * 3, () => view.load_uri(url));
  });
  view.connect("web-process-terminated", () => later(RETRY_S, () => view.load_uri(url)));
  return view;
}

const app = new Gtk.Application({ application_id: "io.github.ericdallo.grog.Skin" });

// Failing, so that whoever runs it (systemd, cage's service) starts it again.
const gpu = onGpuReset(() => {
  printerr("The GPU was reset: exiting, to be started again.");
  System.exit(1);
});

app.connect("activate", () => {
  const open = app.get_active_window();
  if (open) {
    open.present();
    return;
  }
  const view = makeView();
  const window = new Gtk.ApplicationWindow({
    application: app,
    title: "grog-skin",
    child: view,
    default_width: 1280,
    default_height: 720,
  });
  // The page's title: grog's name.
  view.connect("notify::title", () => window.set_title(view.get_title() || "grog-skin"));

  const keys = new Gtk.EventControllerKey();
  keys.connect("key-pressed", (_keys, keyval, _code, modifiers) => {
    const ctrl = (modifiers & Gdk.ModifierType.CONTROL_MASK) !== 0;
    if (keyval === Gdk.KEY_F11) {
      if (window.is_fullscreen()) window.unfullscreen();
      else window.fullscreen();
    } else if (ctrl && keyval === Gdk.KEY_r) {
      view.load_uri(url);
    } else if (ctrl && keyval === Gdk.KEY_q) {
      app.quit();
    } else {
      return false;
    }
    return true;
  });
  window.add_controller(keys);

  // The page says when the screen is to be on (skin.js), and hears when a
  // touch woke it, which goes no further.
  const screen = new Screen(() =>
    view.evaluate_javascript('dispatchEvent(new Event("screen-touched"))', -1, null, null, null, null),
  );
  const content = view.get_user_content_manager();
  content.register_script_message_handler("screen", null);
  content.connect("script-message-received::screen", (_content, value) => screen.want(value.to_boolean()));
  const input = new Gtk.EventControllerLegacy({ propagation_phase: Gtk.PropagationPhase.CAPTURE });
  input.connect("event", (_input, event) => screen.input(event.get_event_type()));
  window.add_controller(input);
  mutterScreen(() => screen.woken())
    .then((backend) => backend ?? wlrootsScreen())
    .then((backend) => screen.use(backend));
  // Stopped (SIGTERM), it leaves the screen on, if it can within a second.
  GLibUnix.signal_add(GLib.PRIORITY_DEFAULT, 15, () => {
    later(1, () => app.quit());
    Promise.resolve(screen.backend?.set(true))
      .catch(() => {})
      .finally(() => app.quit());
    return GLib.SOURCE_REMOVE;
  });

  window.fullscreen();
  window.present();
  hideOverview();
  // Once more, in case it was still opening when GNOME started.
  later(3, hideOverview);
  // GNOME doesn't turn the screen off itself while the skin shows: only it does.
  app.inhibit(window, Gtk.ApplicationInhibitFlags.IDLE, "Showing what grog is up to");
  view.load_uri(url);
});

// runAsync: this module awaited (GUdev), and run() would then keep promises
// (the screen's) from ever going on.
await app.runAsync([System.programInvocationName]);

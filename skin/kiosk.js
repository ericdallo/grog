// grog-skin: grog's skin fullscreen, in a window of its own (GTK 4 and
// WebKitGTK, which GNOME brings). Run it with `gjs -m kiosk.js [URL]`, URL
// being grog's (http://127.0.0.1:8484/ by default).
//
// It shows nothing but grog's page, tries again while grog isn't up, keeps
// the screen on, and, as GNOME starts in its overview, closes it: the skin is
// what the screen shows. F11 toggles fullscreen, Ctrl+R reloads, Ctrl+Q
// quits.

import Gdk from "gi://Gdk?version=4.0";
import Gio from "gi://Gio";
import GLib from "gi://GLib";
import Gtk from "gi://Gtk?version=4.0";
import WebKit from "gi://WebKit?version=6.0";
import System from "system";

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

function makeView() {
  const view = new WebKit.WebView();
  // Without one (a VM's screen, say), WebKit's GPU path shows nothing: the
  // CPU draws it all then, which costs more.
  if (!hasGpu()) view.get_settings().set_hardware_acceleration_policy(WebKit.HardwareAccelerationPolicy.NEVER);
  view.set_background_color(new Gdk.RGBA({ red: 0.043, green: 0.059, blue: 0.082, alpha: 1 }));
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

  window.fullscreen();
  window.present();
  hideOverview();
  // Once more, in case it was still opening when GNOME started.
  later(3, hideOverview);
  // The screen stays on while the skin shows.
  app.inhibit(window, Gtk.ApplicationInhibitFlags.IDLE, "Showing what grog is up to");
  view.load_uri(url);
});

app.run([System.programInvocationName]);

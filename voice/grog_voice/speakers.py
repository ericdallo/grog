"""The speakers of grog's session, PipeWire's sinks, for grog to show and pick
from (it asks, see commands.py, and is told through its POST
/v1/voice/speakers): grog-voice speaks on the one grog picked for it, and the
music plays on the session's default, which grog may change, the way `wpctl
set-default` does. WirePlumber keeps the default across restarts; grog
keeps its pick. What there is comes from pw-dump."""

import json
import logging
import subprocess
import time

log = logging.getLogger("grog_voice")

# How long WirePlumber may take to make a speaker the default, once asked,
# and how often it's looked at meanwhile.
DEFAULT_WAIT_S = 2.0
DEFAULT_POLL_S = 0.1

# What runs pw-dump and pw-metadata, at most.
RUN_TIMEOUT_S = 10


class SpeakersError(Exception):
    """The speakers couldn't be told or changed: why, for grog to say."""


def _info(o):
    return o.get("info") if isinstance(o.get("info"), dict) else {}


def _props(o):
    props = _info(o).get("props")
    return props if isinstance(props, dict) else {}


def _params(o, name):
    params = _info(o).get("params")
    values = params.get(name) if isinstance(params, dict) else None
    return [v for v in values if isinstance(v, dict)] if isinstance(values, list) else []


def default_name(dump):
    """The name of the session's default speaker in `dump` (pw-dump's
    objects), or None."""
    for o in dump:
        if (
            o.get("type") != "PipeWire:Interface:Metadata"
            or (o.get("props") or {}).get("metadata.name") != "default"
        ):
            continue
        for m in o.get("metadata") or []:
            if (
                isinstance(m, dict)
                and m.get("key") == "default.audio.sink"
                and isinstance(m.get("value"), dict)
            ):
                return m["value"].get("name")
    return None


def _route(device, port):
    """The route of `device` (its pw-dump object, or None) that a sink plays
    through (its card.profile.device, `port`), in the device's profile, and
    whether nothing is plugged into it (an HDMI port without a screen, say):
    (its Route or EnumRoute, unplugged)."""
    if device is None or not isinstance(port, int):
        return None, False
    active = next((r for r in _params(device, "Route") if r.get("device") == port), None)
    profile = next((p.get("index") for p in _params(device, "Profile")), None)
    routes = [
        r
        for r in _params(device, "EnumRoute")
        if port in (r.get("devices") or []) and (profile is None or profile in (r.get("profiles") or []))
    ]
    unplugged = bool(routes) and all(r.get("available") == "no" for r in routes)
    if active is not None:
        unplugged = unplugged and active.get("available") == "no"
    return active or (routes[0] if routes else None), unplugged


def _kind(props, device_props, route):
    """bluetooth, usb, hdmi, headphones, or speaker (built in)."""
    if props.get("device.api") == "bluez5":
        return "bluetooth"
    if device_props.get("device.bus") == "usb":
        return "usb"
    text = " ".join(
        str(v) for v in ((route or {}).get("name"), props.get("node.name"), props.get("node.nick"))
    ).lower()
    if "hdmi" in text or "displayport" in text:
        return "hdmi"
    if "headphone" in text or "headset" in text:
        return "headphones"
    return "speaker"


def _label(props, device_props, route, kind):
    """What a speaker is called: a Bluetooth or USB one by its device's name,
    a built-in one by its port ("Speaker", "HDMI / DisplayPort 1 Output")."""
    names = (
        [device_props.get("device.description"), props.get("node.description")]
        if kind in ("bluetooth", "usb")
        else [(route or {}).get("description"), props.get("node.nick"), props.get("node.description")]
    )
    return next((n.strip() for n in names if isinstance(n, str) and n.strip()), props["node.name"])


def listed(dump):
    """The speakers in `dump` (pw-dump's objects): [{name, label, kind,
    default}], those the session prefers first, without grog's own (its echo
    canceller's sink, below any real one: never a default) nor the ports
    nothing is plugged into."""
    devices = {o.get("id"): o for o in dump if o.get("type") == "PipeWire:Interface:Device"}
    default = default_name(dump)
    found = []
    for o in dump:
        props = _props(o)
        name = props.get("node.name")
        if o.get("type") != "PipeWire:Interface:Node" or props.get("media.class") != "Audio/Sink":
            continue
        priority = props.get("priority.session")
        priority = priority if isinstance(priority, int) and not isinstance(priority, bool) else 0
        if not isinstance(name, str) or not name.strip() or priority < 0:
            continue
        device = devices.get(props.get("device.id"))
        route, unplugged = _route(device, props.get("card.profile.device"))
        if unplugged:
            continue
        device_props = _props(device) if device is not None else {}
        kind = _kind(props, device_props, route)
        found.append(
            (
                -priority,
                len(found),
                {
                    "name": name,
                    "label": _label(props, device_props, route, kind),
                    "kind": kind,
                    "default": name == default,
                },
            )
        )
    return [speaker for *_, speaker in sorted(found, key=lambda f: f[:2])]


def _run(run, argv):
    """`argv`'s output, run by `run` (subprocess.run's signature)."""
    try:
        done = run(argv, capture_output=True, timeout=RUN_TIMEOUT_S)
    except (OSError, subprocess.SubprocessError) as e:
        raise SpeakersError(f"{argv[0]} didn't run ({type(e).__name__})") from e
    if done.returncode != 0:
        raise SpeakersError(f"{argv[0]} exited with {done.returncode}")
    return done.stdout


def dump(run=subprocess.run):
    """pw-dump's objects, now."""
    try:
        objects = json.loads(_run(run, ["pw-dump", "--no-colors"]))
    except ValueError as e:
        raise SpeakersError("pw-dump said something else than JSON") from e
    if not isinstance(objects, list):
        raise SpeakersError("pw-dump said something else than a list")
    return [o for o in objects if isinstance(o, dict)]


def make_default(name, run=subprocess.run):
    """Asks WirePlumber to make speaker `name` the session's default, as
    `wpctl set-default` does: it keeps it, and goes back to it when it's
    back."""
    value = json.dumps({"name": name})
    _run(
        run, ["pw-metadata", "-n", "default", "0", "default.configured.audio.sink", value, "Spa:String:JSON"]
    )


def answer(request, run=subprocess.run, sleep=time.sleep, wait_s=DEFAULT_WAIT_S, poll_s=DEFAULT_POLL_S):
    """What to tell grog for `request`: {"id"}, and maybe {"music": a
    speaker's name}, to make it the default first. {"id", "speakers"} (see
    listed), or {"id", "error"}."""
    music = request.get("music")
    try:
        speakers = listed(dump(run))
        if music is not None:
            if not any(s["name"] == music for s in speakers):
                raise SpeakersError("there's no such speaker now")
            make_default(music, run)
            for _ in range(max(1, round(wait_s / poll_s))):
                speakers = listed(dump(run))
                if any(s["default"] and s["name"] == music for s in speakers):
                    break
                sleep(poll_s)
            else:
                raise SpeakersError("WirePlumber didn't make it the default")
            log.info("music on %s", music)
    except SpeakersError as e:
        log.warning("speakers: %s", e)
        return {"id": request.get("id"), "error": str(e)}
    return {"id": request.get("id"), "speakers": speakers}

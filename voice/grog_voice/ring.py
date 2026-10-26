"""A ReSpeaker XVF3800's ring and mute button, when one is plugged in
(Seeed's USB 4-mic array, USB 2886:001a): its 12 LEDs show what grog-voice
does, and its mute and grog's mic go together, both ways. Without one,
nothing happens at all: grog works with any mic.

At rest the ring is off. While someone talks to grog, or it waits for them
(called, or asking), it points at them, as the XVF3800 knows where speech
comes from; while grog thinks it spins, while it speaks it stays lit green,
in the skin's colors (skin/web/skin.css); red a moment when something
went wrong, dim red while grog's mic is off, amber without a mic. grog's
mic off mutes its mics in hardware (its red LED lights), and its button
switches grog's mic off or on through grog (POST /v1/actions/mic), which
tells grog-voice back. Its own mute is off meanwhile, and on again once
grog-voice stops.

Through the XVF3800's control interface: USB vendor control transfers on
endpoint 0, as Seeed's python_control/xvf_host.py makes them, through
Linux's usbfs, standard library only (grog-voice's user needs its device:
the NixOS module's udev rule gives it). From a thread of its own, the only
one ever waiting on the USB, and on grog while it asks: only the latest
state counts."""

import ctypes
import errno
import fcntl
import glob
import logging
import os
import struct
import threading
import time

from .grog import GrogError

log = logging.getLogger("grog_voice")

VENDOR, PRODUCT = 0x2886, 0x001A
SYSFS = "/sys/bus/usb/devices"

# Its control commands: (resource, command, how many values, their struct
# format). Reads set 0x80 in the command, and answer a status byte first.
COMMANDS = {
    "VERSION": (48, 0, 3, "B"),
    "GPO_WRITE_VALUE": (20, 1, 2, "B"),
    "LED_EFFECT": (20, 12, 1, "B"),
    "LED_BRIGHTNESS": (20, 13, 1, "B"),
    "LED_SPEED": (20, 15, 1, "B"),
    "LED_COLOR": (20, 16, 1, "I"),
    "LED_DOA_COLOR": (20, 17, 2, "I"),
    "LED_RING_COLOR": (20, 19, 12, "I"),
    "GPI_EVENT_PENDING_ALL": (36, 7, 1, "I"),
    "MUTE_FUNCTION_ENABLE": (36, 8, 1, "B"),
}
OK, BUSY = 0, 64  # a read's status
READ_TRIES = 20
# LED_EFFECT's.
OFF, BREATH, SINGLE, DOA, RING = 0, 1, 3, 4, 5
# X0D30: high, its mics are muted and its red LED lights.
MUTE_PIN = 30
# The firmware it takes: LED_RING_COLOR, and its button's events with its
# own mute off.
RING_MODE, BUTTON = (2, 0, 7), (2, 1, 1)

# The skin's colors: hearing, awake or asked, thinking, red, amber. grog
# speaks in the green of awake or asked: the user found the skin's coral
# red on the ring.
HEARING, CALLED, THINKING, RED, AMBER = 0x7BED9F, 0x2ED573, 0x5352ED, 0xFF4757, 0xFFA502
SPEAKING = CALLED
# Its spinner's frames a second.
FPS = 12
# How often it looks for presses of its button (they wait for it), and how
# long red says something went wrong.
POLL_S, ERROR_S = 0.25, 2.0
# How long until it looks for one again: none plugged in; it went away or
# failed; grog-voice's user can't use it.
ABSENT_S, RETRY_S, DENIED_S = 5.0, 1.0, 60.0
# Transfers failing in a row before it takes it as gone (pressing its button
# can fail one).
FAILURES = 5
# How long until grog is asked again to switch its mic, as its button asked,
# while it doesn't say it did.
ASK_AGAIN_S = 3.0
GONE = {errno.ENODEV, errno.ENOENT, errno.ESHUTDOWN, errno.EBADF, errno.ENXIO}


class _ControlTransfer(ctypes.Structure):
    """usbfs's struct usbdevfs_ctrltransfer (linux/usbdevice_fs.h)."""

    _fields_ = [
        ("request_type", ctypes.c_uint8),
        ("request", ctypes.c_uint8),
        ("value", ctypes.c_uint16),
        ("index", ctypes.c_uint16),
        ("length", ctypes.c_uint16),
        ("timeout_ms", ctypes.c_uint32),
        ("data", ctypes.c_void_p),
    ]


# _IOWR('U', 0, struct usbdevfs_ctrltransfer)
USBDEVFS_CONTROL = (3 << 30) | (ctypes.sizeof(_ControlTransfer) << 16) | (ord("U") << 8)


class Usb:
    """A USB device's endpoint 0, through its usbfs node
    (/dev/bus/usb/BUS/DEVICE)."""

    def __init__(self, path):
        self._fd = os.open(path, os.O_RDWR | os.O_CLOEXEC)

    def control(self, request_type, value, index, data=b"", length=0, timeout_ms=1000):
        """A vendor control transfer (bRequest 0) sending `data`, or reading
        `length` bytes: what it read."""
        size = len(data) or length
        buffer = ctypes.create_string_buffer(bytes(data), size) if data else ctypes.create_string_buffer(size)
        transfer = _ControlTransfer(request_type, 0, value, index, size, timeout_ms, ctypes.addressof(buffer))
        got = fcntl.ioctl(self._fd, USBDEVFS_CONTROL, transfer)
        return buffer.raw[:got]

    def close(self):
        os.close(self._fd)


class Xvf:
    """An XVF3800's control commands (COMMANDS), over `usb`'s endpoint 0 (a
    Usb). `sleep` waits while it's busy answering."""

    def __init__(self, usb, sleep=time.sleep):
        self.usb, self._sleep = usb, sleep

    def read(self, name):
        """The values of command `name`. Raises OSError when it can't."""
        resource, command, count, kind = COMMANDS[name]
        size = struct.calcsize(kind) * count
        for _ in range(READ_TRIES):
            data = self.usb.control(0xC0, 0x80 | command, resource, length=1 + size)
            if data[:1] == bytes([OK]) and len(data) == 1 + size:
                return struct.unpack(f"<{count}{kind}", data[1:])
            if data[:1] != bytes([BUSY]):
                raise OSError(errno.EPROTO, f"{name} answered {data[:1].hex() or 'nothing'}")
            self._sleep(0.01)
        raise OSError(errno.EBUSY, f"{name} stayed busy")

    def write(self, name, values):
        """Sets command `name` to `values`. Raises OSError when it can't."""
        resource, command, count, kind = COMMANDS[name]
        self.usb.control(0x40, command, resource, data=struct.pack(f"<{count}{kind}", *values))

    def close(self):
        self.usb.close()


def _read(path):
    with open(path) as f:
        return f.read().strip()


def _why(e):
    """What an OSError says, without a path."""
    return e.strerror or type(e).__name__


def find(root=SYSFS):
    """The usbfs node of a ReSpeaker XVF3800 plugged in, or None."""
    for device in sorted(glob.glob(os.path.join(root, "*"))):
        try:
            ids = int(_read(f"{device}/idVendor"), 16), int(_read(f"{device}/idProduct"), 16)
            if ids == (VENDOR, PRODUCT):
                return (
                    f"/dev/bus/usb/{int(_read(f'{device}/busnum')):03d}/{int(_read(f'{device}/devnum')):03d}"
                )
        except (OSError, ValueError):
            continue
    return None


def open_respeaker(root=SYSFS):
    """A ReSpeaker XVF3800 plugged in, as an Xvf, or None. Raises OSError
    when it can't be used (its usbfs node is someone else's, say)."""
    path = find(root)
    return Xvf(Usb(path)) if path is not None else None


def scaled(color, k):
    """`color` (0xRRGGBB) at `k` of its brightness."""
    return sum(round((color >> shift & 0xFF) * k) << shift for shift in (16, 8, 0))


def breathing(color):
    return (
        ("LED_COLOR", (color,)),
        ("LED_BRIGHTNESS", (110,)),
        ("LED_SPEED", (8,)),
        ("LED_EFFECT", (BREATH,)),
    )


def solid(color):
    return (("LED_COLOR", (color,)), ("LED_EFFECT", (SINGLE,)))


def spinner(frame):
    """The colors of its 12 LEDs at `frame` of its spinner: one bright,
    going round, its tail fading."""
    head = frame % 12
    return tuple(scaled(THINKING, max(0.05, 0.7 * (1 - (head - i) % 12 / 4))) for i in range(12))


def look(state, frame=0, spins=True):
    """What the ring shows for grog-voice's `state` (see reporter.py), and
    `muted` or `error` for its own: the commands making it so, in order.
    While grog thinks, `frame` of its spinner, or without ring mode
    (`spins`), breathing."""
    if state in ("hearing", "awake", "asked"):
        color = HEARING if state == "hearing" else CALLED
        return (("LED_DOA_COLOR", (scaled(color, 0.06), scaled(color, 0.6))), ("LED_EFFECT", (DOA,)))
    if state == "answering":
        return (("LED_RING_COLOR", spinner(frame)), ("LED_EFFECT", (RING,))) if spins else breathing(THINKING)
    if state == "speaking":
        return solid(scaled(SPEAKING, 0.6))
    if state == "error":
        return solid(scaled(RED, 0.6))
    if state == "muted":
        return solid(scaled(RED, 0.35))
    if state == "no-mic":
        return solid(scaled(AMBER, 0.35))
    return (("LED_EFFECT", (OFF,)),)


class Ring:
    """A ReSpeaker XVF3800's ring and mute (see above), on a thread of its
    own: `show(state)` what grog-voice does, `mic(on)` whether grog's mic is
    on, as grog says. `set_mic(on)` asks grog to switch it, raising
    GrogError when it can't. `open_device()` opens the ReSpeaker (an Xvf),
    or returns None without one, raising OSError when it can't; `clock`
    tells the time in seconds. With `start` false, no thread: `tick()` does
    what it would, once."""

    def __init__(self, set_mic, *, open_device=open_respeaker, clock=time.monotonic, start=True):
        self._set_mic, self._open, self._clock = set_mic, open_device, clock
        self._state = "listening"
        self._error_until = 0.0
        self._mic_on = None  # as grog says: None until it does
        self._pressed = False  # its button, until grog said whether its mic is on
        self._asked = None  # the mute its button asked for, until grog says it switched
        self._ask_at = 0.0
        self._device = None
        self._version = (0, 0, 0)
        self._next_try = 0.0
        self._failures = 0
        self._shown = {}  # what it last wrote of each LED command
        self._pin = None  # its mute pin, as last written: it reads it back late
        self._spinning_since = None
        self._warned = set()
        self._wake = threading.Event()
        self._closed = threading.Event()
        self._thread = None
        if start:
            self._thread = threading.Thread(target=self._run, daemon=True, name="ring")
            self._thread.start()

    def show(self, state):
        if state == "error":
            self._error_until = self._clock() + ERROR_S
        self._state = state
        self._wake.set()

    def mic(self, on):
        self._mic_on = on
        self._wake.set()

    def close(self):
        """Stops: the ring goes off, and its button mutes it again by itself."""
        self._closed.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
        if self._device is not None:
            try:
                self._device.write("LED_EFFECT", (OFF,))
                if self._version >= BUTTON:
                    self._device.write("MUTE_FUNCTION_ENABLE", (1,))
            except OSError:
                pass
            self._drop()

    def _run(self):
        while not self._closed.is_set():
            self._wake.clear()
            try:
                self.tick()
            except Exception as e:  # a bug: the thread goes on, and only says its type
                self._warn("bug", f"the ReSpeaker's ring failed ({type(e).__name__}): going on")
            self._wake.wait(self._wait())

    def _wait(self):
        now = self._clock()
        if self._device is None:
            return min(ABSENT_S, max(POLL_S, self._next_try - now))
        if self._spinning_since is not None:
            return 1 / FPS
        return max(0.01, min(POLL_S, self._error_until - now)) if self._error_until > now else POLL_S

    def tick(self):
        """Opens it when it's there, takes presses of its button, and makes
        its mute and its ring what they should be now."""
        now = self._clock()
        if self._device is None and not self._connect(now):
            return
        try:
            self._button(now)
            self._mute()
            self._light(now)
            self._failures = 0
        except OSError as e:
            self._failed(e, now)

    def _connect(self, now):
        if now < self._next_try:
            return False
        try:
            device = self._open()
        except OSError as e:
            self._warn("open", f"can't use the ReSpeaker ({_why(e)}): its ring and button do nothing")
            self._next_try = now + DENIED_S
            return False
        if device is None:
            self._next_try = now + ABSENT_S
            return False
        try:
            version = tuple(device.read("VERSION"))
            if version >= BUTTON:
                device.write("MUTE_FUNCTION_ENABLE", (0,))
        except OSError as e:
            self._warn("setup", f"couldn't set the ReSpeaker up ({_why(e)}): trying again")
            device.close()
            self._next_try = now + RETRY_S
            return False
        self._device, self._version, self._failures = device, version, 0
        self._shown, self._pin = {}, None
        self._warned.difference_update({"open", "setup"})
        log.info(
            "a ReSpeaker XVF3800 (firmware %s): its ring shows what grog-voice does%s",
            ".".join(map(str, version)),
            ", its button switches grog's mic" if version >= BUTTON else "",
        )
        return True

    def _failed(self, e, now):
        self._failures += 1
        if e.errno in GONE or self._failures >= FAILURES:
            log.info("the ReSpeaker went away (%s)", _why(e))
            self._drop()
            self._next_try = now + RETRY_S

    def _drop(self):
        try:
            self._device.close()
        except OSError:
            pass
        self._device = None

    def _muted(self):
        """Whether grog's mic is off, or its button asked for that."""
        return self._asked if self._asked is not None else self._mic_on is False

    def _button(self, now):
        """Takes a press of its button: grog's mic the other way, as grog is
        asked, until it says it switched."""
        if self._version >= BUTTON and self._device.read("GPI_EVENT_PENDING_ALL")[0] & 1:
            self._pressed = True
        if self._pressed and self._mic_on is not None:
            self._pressed = False
            self._asked, self._ask_at = not self._muted(), now
        if self._asked is None:
            return
        if self._mic_on == (not self._asked):
            self._asked = None
        elif now >= self._ask_at:
            self._ask_at = now + ASK_AGAIN_S
            try:
                self._set_mic(not self._asked)
                self._warned.discard("ask")
            except GrogError as e:
                self._warn(
                    "ask", f"couldn't ask grog to switch its mic, as its button asked ({e}): trying again"
                )

    def _mute(self):
        """Its mics muted while grog's are off, once grog said."""
        if self._mic_on is None and self._asked is None:
            return
        muted = self._muted()
        if muted != self._pin:
            self._device.write("GPO_WRITE_VALUE", (MUTE_PIN, int(muted)))
            self._pin = muted

    def _light(self, now):
        state = "error" if now < self._error_until else "muted" if self._muted() else self._state
        if state == "answering":
            if self._spinning_since is None:
                self._spinning_since = now
            frame = int((now - self._spinning_since) * FPS)
        else:
            self._spinning_since, frame = None, 0
        for name, values in look(state, frame, spins=self._version >= RING_MODE):
            if self._shown.get(name) != values:
                self._device.write(name, values)
                self._shown[name] = values

    def _warn(self, key, message):
        """Logs `message` once, until `key` is cleared."""
        if key not in self._warned:
            self._warned.add(key)
            log.warning("%s", message)

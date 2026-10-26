import ctypes
import errno
import os
import shutil
import struct
import tempfile
import threading
import time
import unittest

from grog_voice import ring
from grog_voice.grog import GrogError
from grog_voice.ring import Ring, Xvf, find, look, scaled, spinner


class FakeUsb:
    """A fake XVF3800's endpoint 0: reads answer `answers`, in order (the
    status byte first); what's sent is in .sent."""

    def __init__(self, *answers):
        self.answers, self.sent = list(answers), []

    def control(self, request_type, value, index, data=b"", length=0):
        self.sent.append((request_type, value, index, bytes(data), length))
        return self.answers.pop(0) if request_type == 0xC0 else b""


class XvfTest(unittest.TestCase):
    def test_reads_a_command(self):
        usb = FakeUsb(b"\x00\x02\x01\x01")
        self.assertEqual((2, 1, 1), Xvf(usb).read("VERSION"))
        self.assertEqual([(0xC0, 0x80, 48, b"", 4)], usb.sent)

    def test_reads_again_while_it_is_busy(self):
        usb, slept = FakeUsb(b"\x40\x00\x00\x00\x00", b"\x00\x01\x00\x00\x00"), []
        self.assertEqual((1,), Xvf(usb, sleep=slept.append).read("GPI_EVENT_PENDING_ALL"))
        self.assertEqual(2, len(usb.sent))
        self.assertEqual([0.01], slept)

    def test_a_command_it_doesnt_know_fails(self):
        with self.assertRaises(OSError) as cm:
            Xvf(FakeUsb(b"\x41\x00\x00\x00")).read("VERSION")
        self.assertEqual(errno.EPROTO, cm.exception.errno)

    def test_writes_little_endian_values(self):
        usb = FakeUsb()
        xvf = Xvf(usb)
        xvf.write("LED_DOA_COLOR", (0x010203, 0x0A0B0C))
        xvf.write("GPO_WRITE_VALUE", (30, 1))
        self.assertEqual(
            [(0x40, 17, 20, struct.pack("<2I", 0x010203, 0x0A0B0C), 0), (0x40, 1, 20, b"\x1e\x01", 0)],
            usb.sent,
        )

    @unittest.skipUnless(ctypes.sizeof(ctypes.c_void_p) == 8, "64-bit only")
    def test_usbfs_control_transfer_as_linux_has_it(self):
        self.assertEqual(24, ctypes.sizeof(ring._ControlTransfer))
        self.assertEqual(16, ring._ControlTransfer.data.offset)
        self.assertEqual(0xC0185500, ring.USBDEVFS_CONTROL)


class FindTest(unittest.TestCase):
    def sysfs(self, *devices):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        for name, files in devices:
            os.makedirs(f"{root}/{name}")
            for key, value in files.items():
                with open(f"{root}/{name}/{key}", "w") as f:
                    f.write(f"{value}\n")
        return root

    def test_finds_its_usbfs_node(self):
        camera = {"idVendor": "3277", "idProduct": "0055", "busnum": 3, "devnum": 4}
        respeaker = {"idVendor": "2886", "idProduct": "001a", "busnum": 3, "devnum": 15}
        root = self.sysfs(("3-9", camera), ("3-2.4", respeaker), ("usb3", {}), ("3-2.4:1.0", {}))
        self.assertEqual("/dev/bus/usb/003/015", find(root))

    def test_none_without_one(self):
        root = self.sysfs(("3-9", {"idVendor": "3277", "idProduct": "0055", "busnum": 3, "devnum": 4}))
        self.assertIsNone(find(root))
        self.assertIsNone(ring.open_respeaker(root))


class LookTest(unittest.TestCase):
    def test_off_at_rest(self):
        for state in ("listening", "loading", "anything"):
            self.assertEqual((("LED_EFFECT", (ring.OFF,)),), look(state))

    def test_pointing_at_who_talks_in_the_skins_greens(self):
        self.assertEqual(
            (
                ("LED_DOA_COLOR", (scaled(ring.HEARING, 0.06), scaled(ring.HEARING, 0.6))),
                ("LED_EFFECT", (4,)),
            ),
            look("hearing"),
        )
        for state in ("awake", "asked"):
            self.assertEqual(scaled(ring.CALLED, 0.6), dict(look(state))["LED_DOA_COLOR"][1])

    def test_spinning_while_grog_thinks(self):
        shown = dict(look("answering", frame=0))
        self.assertEqual((ring.RING,), shown["LED_EFFECT"])
        colors = shown["LED_RING_COLOR"]
        self.assertEqual(12, len(colors))
        self.assertEqual(scaled(ring.THINKING, 0.7), colors[0], "its head")
        self.assertEqual(scaled(ring.THINKING, 0.05), colors[6], "its tail faded")
        self.assertEqual(spinner(0)[-1:] + spinner(0)[:-1], spinner(1), "going round")

    def test_breathing_while_thinking_without_ring_mode(self):
        shown = dict(look("answering", spins=False))
        self.assertEqual(((ring.THINKING,), (ring.BREATH,)), (shown["LED_COLOR"], shown["LED_EFFECT"]))

    def test_breathing_while_speaking(self):
        shown = dict(look("speaking"))
        self.assertEqual(((ring.SPEAKING,), (ring.BREATH,)), (shown["LED_COLOR"], shown["LED_EFFECT"]))

    def test_red_and_amber(self):
        self.assertEqual(ring.solid(scaled(ring.RED, 0.6)), look("error"))
        self.assertEqual(ring.solid(scaled(ring.RED, 0.35)), look("muted"))
        self.assertEqual(ring.solid(scaled(ring.AMBER, 0.35)), look("no-mic"))


class FakeDevice:
    """A ReSpeaker's commands: what's written in .written ((name, values)),
    what's read in .reads; press() presses its button, and .failing (errnos)
    makes the next transfers fail."""

    def __init__(self, version=(2, 1, 1)):
        self.version, self.written, self.reads = version, [], []
        self.pending, self.failing, self.closed = 0, [], False

    def read(self, name):
        self._transfer()
        self.reads.append(name)
        if name == "VERSION":
            return self.version
        pending, self.pending = self.pending, 0
        return (pending,)

    def write(self, name, values):
        self._transfer()
        self.written.append((name, tuple(values)))

    def close(self):
        self.closed = True

    def press(self):
        self.pending |= 1

    def _transfer(self):
        if self.failing:
            raise OSError(self.failing.pop(0), os.strerror(errno.EPROTO))


class Clock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now


def ringed(*devices, set_mic=None):
    """A Ring without its thread: `devices` are what opening it gives, in
    turn (the last one again), its clock in .clock, grog asked to switch its
    mic in .asked, its opening counted in .opened."""
    queue = list(devices)
    r_asked, r_opened = [], []

    def open_device():
        r_opened.append(1)
        device = queue[0] if len(queue) == 1 else queue.pop(0)
        if isinstance(device, Exception):
            raise device
        return device

    clock = Clock()
    r = Ring(set_mic or r_asked.append, open_device=open_device, clock=clock, start=False)
    r.clock, r.asked, r.opened = clock, r_asked, r_opened
    return r


def mute_pin(device):
    return [values for name, values in device.written if name == "GPO_WRITE_VALUE"]


class RingTest(unittest.TestCase):
    def test_without_one_nothing_happens(self):
        r = ringed(None)
        r.show("hearing")
        r.tick()
        r.tick()
        self.assertEqual(1, len(r.opened), "not looked for again right away")
        r.clock.now += ring.ABSENT_S
        r.tick()
        self.assertEqual(2, len(r.opened))

    def test_its_own_mute_goes_off_and_the_ring_with_it(self):
        device = FakeDevice()
        r = ringed(device)
        with self.assertLogs("grog_voice", "INFO") as logs:
            r.tick()
        self.assertEqual([("MUTE_FUNCTION_ENABLE", (0,)), ("LED_EFFECT", (ring.OFF,))], device.written)
        self.assertIn("a ReSpeaker XVF3800 (firmware 2.1.1)", logs.output[0])

    def test_the_latest_state_counts(self):
        device = FakeDevice()
        r = ringed(device)
        r.tick()
        device.written.clear()
        r.show("hearing")
        r.show("speaking")
        r.tick()
        self.assertEqual(list(look("speaking")), device.written)

    def test_only_what_changes_is_written(self):
        device = FakeDevice()
        r = ringed(device)
        r.show("speaking")
        r.tick()
        r.show("listening")
        r.tick()
        device.written.clear()
        r.show("speaking")
        r.tick()
        self.assertEqual([("LED_EFFECT", (ring.BREATH,))], device.written, "its color, as it was")

    def test_it_spins_while_grog_thinks(self):
        device = FakeDevice()
        r = ringed(device)
        r.tick()
        r.show("answering")
        r.tick()
        self.assertAlmostEqual(1 / ring.FPS, r._wait())
        device.written.clear()
        r.clock.now += 1.5 / ring.FPS
        r.tick()
        self.assertEqual([("LED_RING_COLOR", spinner(1))], device.written, "its next frame")
        r.show("speaking")
        r.tick()
        self.assertEqual(ring.POLL_S, r._wait())

    def test_older_firmware_breathes_and_leaves_its_button_alone(self):
        device = FakeDevice(version=(2, 0, 6))
        r = ringed(device)
        r.show("answering")
        with self.assertLogs("grog_voice", "INFO") as logs:
            r.tick()
        self.assertNotIn("button", logs.output[0])
        self.assertEqual(list(look("answering", spins=False)), device.written)
        self.assertEqual(["VERSION"], device.reads)

    def test_red_a_moment_when_something_went_wrong(self):
        device = FakeDevice()
        r = ringed(device)
        r.tick()
        r.show("error")
        r.show("listening")
        r.tick()
        self.assertEqual(look("error")[0], device.written[-2])
        r.clock.now += ring.ERROR_S
        r.tick()
        self.assertEqual(("LED_EFFECT", (ring.OFF,)), device.written[-1])

    def test_grogs_mic_off_mutes_it_in_hardware(self):
        device = FakeDevice()
        r = ringed(device)
        r.tick()
        self.assertEqual([], mute_pin(device), "left as it is until grog says")
        r.mic(False)
        r.tick()
        self.assertEqual([(30, 1)], mute_pin(device))
        self.assertEqual(look("muted")[0], device.written[-2])
        r.mic(True)
        r.tick()
        self.assertEqual([(30, 1), (30, 0)], mute_pin(device))
        self.assertEqual(("LED_EFFECT", (ring.OFF,)), device.written[-1])

    def test_its_button_switches_grogs_mic(self):
        device = FakeDevice()
        r = ringed(device)
        r.mic(True)
        r.tick()
        device.press()
        r.tick()
        self.assertEqual([False], r.asked)
        self.assertEqual([(30, 0), (30, 1)], mute_pin(device), "muted right away")
        r.mic(False)  # grog switched it
        r.clock.now += ring.ASK_AGAIN_S
        r.tick()
        self.assertEqual([False], r.asked, "not asked again")
        device.press()
        r.tick()
        self.assertEqual([False, True], r.asked)
        self.assertEqual([(30, 0), (30, 1), (30, 0)], mute_pin(device))

    def test_a_press_before_grog_said_waits_for_it(self):
        device = FakeDevice()
        r = ringed(device)
        device.press()
        r.tick()
        self.assertEqual([], r.asked)
        r.mic(True)
        r.tick()
        self.assertEqual([False], r.asked)

    def test_while_grog_cant_be_asked_it_stays_muted_and_asks_again(self):
        device = FakeDevice()
        tries = []

        def unreachable(on):
            tries.append(on)
            raise GrogError("Couldn't reach grog")

        r = ringed(device, set_mic=unreachable)
        r.mic(True)
        r.tick()
        device.press()
        with self.assertLogs("grog_voice", "WARNING") as logs:
            r.tick()
            r.tick()
            r.clock.now += ring.ASK_AGAIN_S
            r.tick()
        self.assertEqual([False, False], tries)
        self.assertEqual(1, len(logs.output), "said once")
        self.assertEqual((30, 1), mute_pin(device)[-1])
        r.mic(True)  # grog came back, as it was
        r.tick()
        self.assertEqual((30, 1), mute_pin(device)[-1], "still muted, as its button asked")

    def test_a_failing_transfer_is_tried_again_then_it_is_gone(self):
        first, second = FakeDevice(), FakeDevice()
        r = ringed(first, second)
        r.show("hearing")
        r.tick()
        first.failing = [errno.EPROTO]
        r.tick()
        self.assertFalse(first.closed, "once can happen")
        first.failing = [errno.EPROTO] * ring.FAILURES
        with self.assertLogs("grog_voice", "INFO") as logs:
            for _ in range(ring.FAILURES):
                r.tick()
        self.assertTrue(first.closed)
        self.assertIn("the ReSpeaker went away", logs.output[0])
        r.clock.now += ring.RETRY_S
        with self.assertLogs("grog_voice", "INFO"):
            r.tick()
        self.assertEqual(list(look("hearing")), second.written[1:], "shown again")

    def test_unplugged_it_comes_back_as_it_was(self):
        first, second = FakeDevice(), FakeDevice()
        r = ringed(first, None, second)
        r.mic(False)
        r.tick()
        first.failing = [errno.ENODEV]
        with self.assertLogs("grog_voice", "INFO"):
            r.tick()
        self.assertTrue(first.closed, "at once")
        r.clock.now += ring.RETRY_S
        r.tick()
        r.clock.now += ring.ABSENT_S
        with self.assertLogs("grog_voice", "INFO"):
            r.tick()
        self.assertEqual([(30, 1)], mute_pin(second))
        self.assertEqual(look("muted")[0], second.written[-2])

    def test_no_access_is_said_once(self):
        denied = PermissionError(errno.EACCES, os.strerror(errno.EACCES))
        r = ringed(denied)
        with self.assertLogs("grog_voice", "WARNING") as logs:
            r.tick()
            r.clock.now += ring.DENIED_S
            r.tick()
        self.assertEqual(2, len(r.opened))
        self.assertEqual(1, len(logs.output))
        self.assertIn("can't use the ReSpeaker (Permission denied)", logs.output[0])

    def test_closing_turns_it_off_and_gives_its_button_back(self):
        device = FakeDevice()
        r = ringed(device)
        r.show("hearing")
        r.tick()
        r.close()
        self.assertEqual([("LED_EFFECT", (ring.OFF,)), ("MUTE_FUNCTION_ENABLE", (1,))], device.written[-2:])
        self.assertTrue(device.closed)


class ThreadTest(unittest.TestCase):
    def test_it_shows_states_as_they_come(self):
        device, done = FakeDevice(), threading.Event()
        r = Ring(lambda on: None, open_device=lambda: device)
        self.addCleanup(r.close)
        r.show("hearing")
        deadline = time.monotonic() + 5
        while ("LED_EFFECT", (ring.DOA,)) not in device.written and time.monotonic() < deadline:
            done.wait(0.01)
        self.assertIn(("LED_EFFECT", (ring.DOA,)), device.written)
        r.close()
        self.assertEqual(("MUTE_FUNCTION_ENABLE", (1,)), device.written[-1])


if __name__ == "__main__":
    unittest.main()

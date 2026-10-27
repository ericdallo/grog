import os
import socket
import tempfile
import time
import unittest

from grog_voice import watchdog
from grog_voice.watchdog import Watchdog


class NotifyTest(unittest.TestCase):
    def listening(self, address):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        s.bind(address)
        s.settimeout(2)
        self.addCleanup(s.close)
        return s

    def test_tells_systemd_on_its_socket(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "notify")
            s = self.listening(path)
            self.assertTrue(watchdog.notify("READY=1", {"NOTIFY_SOCKET": path}))
            self.assertEqual(b"READY=1", s.recv(64))

    def test_an_abstract_socket(self):
        name = f"grog-voice-test-{os.getpid()}"
        s = self.listening("\0" + name)
        self.assertTrue(watchdog.notify("WATCHDOG=1", {"NOTIFY_SOCKET": "@" + name}))
        self.assertEqual(b"WATCHDOG=1", s.recv(64))

    def test_nothing_without_systemd(self):
        self.assertFalse(watchdog.notify("READY=1", {}))
        self.assertFalse(watchdog.notify("READY=1", {"NOTIFY_SOCKET": "/nonexistent/notify"}))

    def test_half_its_watchdog_for_this_process(self):
        self.assertEqual(15.0, watchdog.interval({"WATCHDOG_USEC": "30000000"}, pid=7))
        self.assertEqual(15.0, watchdog.interval({"WATCHDOG_USEC": "30000000", "WATCHDOG_PID": "7"}, pid=7))
        self.assertIsNone(watchdog.interval({"WATCHDOG_USEC": "30000000", "WATCHDOG_PID": "8"}, pid=7))
        self.assertIsNone(watchdog.interval({}, pid=7))
        self.assertIsNone(watchdog.interval({"WATCHDOG_USEC": "soon"}, pid=7))
        self.assertIsNone(watchdog.interval({"WATCHDOG_USEC": "0"}, pid=7))


class WatchdogTest(unittest.TestCase):
    def test_alive_while_healthy_said_once_when_not(self):
        healthy, sent = [True], []
        dog = Watchdog(lambda: healthy[0], 1.0, send=sent.append)
        dog.tick()
        self.assertEqual(["WATCHDOG=1"], sent)
        healthy[0] = False
        with self.assertLogs("grog_voice", "WARNING") as logs:
            dog.tick()
            dog.tick()
        self.assertEqual(["WATCHDOG=1"], sent, "not while it isn't")
        self.assertEqual(1, len(logs.output))
        self.assertIn("stopped taking what its mic hears", logs.output[0])
        healthy[0] = True
        with self.assertLogs("grog_voice", "INFO"):
            dog.tick()
        self.assertEqual(["WATCHDOG=1", "WATCHDOG=1"], sent)

    def test_a_bug_is_as_if_stuck(self):
        sent = []
        dog = Watchdog(lambda: 1 / 0, 1.0, send=sent.append)
        with self.assertLogs("grog_voice", "WARNING") as logs:
            dog.tick()
        self.assertEqual([], sent)
        self.assertIn("ZeroDivisionError", logs.output[0])

    def test_on_a_thread_from_the_start(self):
        sent = []
        dog = Watchdog(lambda: True, 60.0, send=sent.append)
        dog.start()
        self.addCleanup(dog.close)
        deadline = time.monotonic() + 2
        while not sent and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(["WATCHDOG=1"], sent[:1])


if __name__ == "__main__":
    unittest.main()

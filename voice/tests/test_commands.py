import threading
import unittest

from grog_voice.commands import Commands
from grog_voice.grog import GrogError


class FakeVoice:
    """What a Voice was asked to do, in .did."""

    def __init__(self):
        self.did = []

    def set_mic(self, on):
        self.did.append(("mic", on))

    def listen_now(self):
        self.did.append("listen")

    def stop(self):
        self.did.append("stop")

    def back(self):
        self.did.append("back")


def grog(*connections, gate=None):
    """A fake grog's GET /v1/voice/commands: each connection says the next
    of `connections`, (type, data) pairs, or fails with it, an exception;
    and once they're all said (.said_all), nothing more until .over. The
    first waits for `gate`, when given."""
    pending, said_all, over = list(connections), threading.Event(), threading.Event()

    def connect():
        if gate is not None:
            gate.wait(5)
        if not pending:
            said_all.set()
            over.wait(5)
            return
        connection = pending.pop(0)
        if isinstance(connection, Exception):
            raise connection
        yield from connection

    connect.said_all, connect.over = said_all, over
    return connect


class CommandsTest(unittest.TestCase):
    def commands(self, connect):
        c = Commands(connect, retry_min_s=0.01, retry_max_s=0.02)
        self.addCleanup(connect.over.set)
        self.addCleanup(c.close)
        return c

    def test_what_grog_said_waits_for_the_voice(self):
        connect = grog([("snapshot", {"mic": "off"})])
        c = self.commands(connect)
        self.assertTrue(connect.said_all.wait(5))
        self.assertIs(False, c.mic_on)
        v = FakeVoice()
        c.attach(v)
        self.assertEqual([("mic", False), "back"], v.did)
        c.attach(v)
        self.assertEqual([("mic", False), "back", ("mic", False)], v.did, "back only once")

    def test_nothing_before_grog_says(self):
        connect = grog(gate=threading.Event())
        c = self.commands(connect)
        self.assertIsNone(c.mic_on)
        v = FakeVoice()
        c.attach(v)
        self.assertEqual([], v.did)

    def test_what_grog_asks_as_it_comes_and_again_once_it_is_back(self):
        attached = threading.Event()
        connect = grog(
            [
                ("snapshot", {"mic": "on"}),
                ("listen", {}),
                ("message", {}),
                ("stop", {}),
                ("mic", {"mic": "off"}),
            ],
            GrogError("grog went away"),
            GrogError("grog went away"),
            [("snapshot", {"mic": "on"})],
            gate=attached,
        )
        c = self.commands(connect)
        v = FakeVoice()
        c.attach(v)
        with self.assertLogs("grog_voice", "INFO") as logs:
            attached.set()
            self.assertTrue(connect.said_all.wait(5))
        self.assertEqual(
            [("mic", True), "back", "listen", "stop", ("mic", False), ("mic", True), "back"],
            v.did,
        )
        self.assertEqual(
            [
                "WARNING:grog_voice:not hearing from grog what to do (grog went away): it'll try again",
                "INFO:grog_voice:hearing from grog again what to do",
            ],
            logs.output,
            "once while it fails",
        )

    def test_what_makes_no_sense_is_ignored(self):
        connect = grog([("mic", {"mic": "maybe"}), ("mic", ["off"]), ("snapshot", {}), ("dance", {})])
        c = self.commands(connect)
        v = FakeVoice()
        c.attach(v)
        self.assertTrue(connect.said_all.wait(5))
        self.assertEqual((["back"], None), (v.did, c.mic_on), "but grog is there")


if __name__ == "__main__":
    unittest.main()

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

    def set_music(self, playing):
        self.did.append(("music", playing))

    def set_speaker(self, name):
        self.did.append(("speaker", name))

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
        connect = grog(
            [
                ("mic", {"mic": "maybe"}),
                ("mic", ["off"]),
                ("snapshot", {}),
                ("dance", {}),
                ("music", {"music": "yes"}),
                ("music", [True]),
                ("mic", {"music": True}),
            ]
        )
        c = self.commands(connect)
        v = FakeVoice()
        c.attach(v)
        self.assertTrue(connect.said_all.wait(5))
        self.assertEqual((["back"], None, None), (v.did, c.mic_on, c.music), "but grog is there")

    def test_whether_music_plays_waits_for_the_voice(self):
        connect = grog([("snapshot", {"mic": "on", "music": True})])
        c = self.commands(connect)
        self.assertTrue(connect.said_all.wait(5))
        self.assertEqual((True, True), (c.mic_on, c.music))
        v = FakeVoice()
        c.attach(v)
        self.assertEqual([("mic", True), ("music", True), "back"], v.did)

    def test_whether_music_plays_as_it_changes(self):
        attached = threading.Event()
        connect = grog(
            [
                ("snapshot", {"mic": "on", "music": False}),
                ("music", {"music": True}),
                ("listen", {}),
                ("music", {"music": False}),
            ],
            GrogError("grog went away"),
            [("snapshot", {"mic": "on", "music": True})],
            gate=attached,
        )
        c = self.commands(connect)
        v = FakeVoice()
        c.attach(v)
        with self.assertLogs("grog_voice", "INFO"):
            attached.set()
            self.assertTrue(connect.said_all.wait(5))
        self.assertEqual(
            [("mic", True), ("music", False), "back", ("music", True), "listen", ("music", False)]
            + [("mic", True), ("music", True), "back"],
            v.did,
        )
        self.assertIs(True, c.music, "as grog last said")

    def test_the_speaker_grog_picked_waits_for_the_voice(self):
        connect = grog([("snapshot", {"mic": "on", "music": False, "speaker": "bt"})])
        c = self.commands(connect)
        self.assertTrue(connect.said_all.wait(5))
        self.assertEqual("bt", c.speaker)
        v = FakeVoice()
        c.attach(v)
        self.assertEqual([("mic", True), ("music", False), ("speaker", "bt"), "back"], v.did)

    def test_the_speaker_as_it_changes(self):
        attached = threading.Event()
        connect = grog(
            [
                ("snapshot", {"mic": "on", "speaker": None}),
                ("speaker", {"speaker": "hdmi"}),
                ("speaker", {"speaker": 5}),
                ("speaker", {"speaker": " "}),
                ("speaker", {}),
                ("speaker", {"speaker": None}),
            ],
            gate=attached,
        )
        c = self.commands(connect)
        v = FakeVoice()
        c.attach(v)
        attached.set()
        self.assertTrue(connect.said_all.wait(5))
        self.assertEqual(
            [("mic", True), ("speaker", None), "back", ("speaker", "hdmi"), ("speaker", None)],
            v.did,
            "what makes sense",
        )
        self.assertIsNone(c.speaker)

    def test_which_speakers_there_are_on_a_thread_of_its_own(self):
        asked, threads = [], []

        def speakers(request):
            asked.append(request)
            threads.append(threading.current_thread().name)

        connect = grog(
            [("snapshot", {"mic": "on"}), ("speakers", {"id": 3}), ("speakers", {"id": 4, "music": "bt"})]
        )
        c = Commands(connect, speakers=speakers, retry_min_s=0.01, retry_max_s=0.02)
        self.addCleanup(connect.over.set)
        self.addCleanup(c.close)
        self.assertTrue(connect.said_all.wait(5))
        for _ in range(100):
            if len(asked) == 2:
                break
            threading.Event().wait(0.01)
        self.assertEqual([{"id": 3}, {"id": 4, "music": "bt"}], sorted(asked, key=lambda r: r["id"]))
        self.assertEqual(["speakers", "speakers"], threads)


if __name__ == "__main__":
    unittest.main()

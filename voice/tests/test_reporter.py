import threading
import time
import unittest

from grog_voice.grog import GrogError
from grog_voice.reporter import Reporter


class ReporterTest(unittest.TestCase):
    def test_tells_grog_every_state_in_order(self):
        sent = []
        report = Reporter(sent.append, heartbeat_s=60)
        for state in ("listening", "hearing", "answering", "speaking"):
            report(state)
        report.flush()
        self.assertEqual(["listening", "hearing", "answering", "speaking"], sent)

    def test_says_it_again_now_and_then(self):
        sent = []
        report = Reporter(sent.append, heartbeat_s=0.05)
        report("listening")
        time.sleep(0.3)
        self.assertGreater(len(sent), 2)
        self.assertEqual({"listening"}, set(sent))

    def test_what_it_heard_goes_with_the_state_it_is_in(self):
        sent = []
        report = Reporter(lambda state, heard=None: sent.append((state, heard)), heartbeat_s=60)
        report.heard("before any state", False)  # nothing to say it with
        report("hearing")
        report.heard("Grog, que horas", False)
        report.heard("Grog, que horas são?", True)
        report("answering")
        report.flush()
        self.assertEqual(
            [
                ("hearing", None),
                ("hearing", {"text": "Grog, que horas", "final": False}),
                ("hearing", {"text": "Grog, que horas são?", "final": True}),
                ("answering", None),
            ],
            sent,
        )

    def test_says_the_state_again_but_not_what_it_heard(self):
        sent = []
        report = Reporter(lambda state, heard=None: sent.append((state, heard)), heartbeat_s=0.05)
        report("hearing")
        report.heard("Grog.", True)
        time.sleep(0.3)
        self.assertEqual([("hearing", None), ("hearing", {"text": "Grog.", "final": True})], sent[:2])
        self.assertGreater(len(sent), 3)
        self.assertEqual({("hearing", None)}, set(sent[2:]))

    def test_where_who_talks_is_goes_with_the_last_state_and_those_after(self):
        sent = []

        def send(state, heard=None, **more):
            sent.append((state, heard, more))

        report = Reporter(send, heartbeat_s=60)
        report.direction(-20)  # nothing to say it with
        report("hearing")
        report.flush()
        report.direction(30)
        report.heard("Grog.", True)
        report("answering")
        report.flush()
        self.assertEqual(
            [
                ("hearing", None, {"direction": -20}),
                ("hearing", None, {"direction": 30}),
                ("hearing", {"text": "Grog.", "final": True}, {"direction": 30}),
                ("answering", None, {"direction": 30}),
            ],
            sent,
        )

    def test_keeps_whether_grog_shows_what_it_hears(self):
        answers = [{"captions": True}, {}, {"captions": False}]
        report = Reporter(lambda state: answers.pop(0), heartbeat_s=60)
        self.assertFalse(report.captions, "not before grog says")
        report("listening")
        report.flush()
        self.assertTrue(report.captions)
        report("hearing")
        report.flush()
        self.assertFalse(report.captions, "an older grog says nothing: off")
        report("answering")
        report.flush()
        self.assertFalse(report.captions)

    def test_keeps_grogs_name(self):
        answers = [{}, {"name": "Grog"}, {"captions": True}, {"name": "Jarvis"}]
        report = Reporter(lambda state: answers.pop(0), heartbeat_s=60)
        names = []
        for state in ("loading", "listening", "hearing", "answering"):
            report(state)
            report.flush()
            names.append(report.name)
        self.assertEqual([None, "Grog", "Grog", "Jarvis"], names, "the last one it said")

    def test_a_bug_sending_says_only_its_type_and_it_goes_on(self):
        sent, failures = [], [RuntimeError("Grog, que horas são?")] * 2

        def send(state):
            if failures:
                raise failures.pop(0)
            sent.append(state)

        with self.assertLogs("grog_voice", "INFO") as logs:
            report = Reporter(send, heartbeat_s=60)
            for state in ("loading", "listening", "hearing"):
                report(state)
            report.flush()
        self.assertEqual(["hearing"], sent)
        self.assertEqual(
            [
                "WARNING:grog_voice:couldn't tell grog what grog-voice is doing (RuntimeError): "
                "it'll try again",
                "INFO:grog_voice:telling grog what grog-voice is doing again",
            ],
            logs.output,
        )

    def test_nothing_before_there_is_a_state(self):
        sent = []
        Reporter(sent.append, heartbeat_s=0.01)
        time.sleep(0.1)
        self.assertEqual([], sent)

    def test_grog_not_answering_drops_states_and_says_so_once(self):
        sent, failures = [], [GrogError("Couldn't reach grog"), GrogError("Couldn't reach grog")]

        def send(state):
            if failures:
                raise failures.pop(0)
            sent.append(state)

        with self.assertLogs("grog_voice", "INFO") as logs:
            report = Reporter(send, heartbeat_s=60)
            for state in ("loading", "listening", "hearing"):
                report(state)
            report.flush()
        self.assertEqual(["hearing"], sent)
        self.assertEqual(
            [
                "WARNING:grog_voice:couldn't tell grog what grog-voice is doing (Couldn't reach grog): "
                "it'll try again",
                "INFO:grog_voice:telling grog what grog-voice is doing again",
            ],
            logs.output,
        )

    def test_a_slow_grog_gets_the_latest(self):
        sent, slow = [], threading.Event()

        def send(state):
            slow.wait(5)
            sent.append(state)

        report = Reporter(send, heartbeat_s=60, keep=3)
        report("first")
        time.sleep(0.05)  # it's sending that one
        for n in range(10):
            report(f"state {n}")
        slow.set()
        report.flush()
        self.assertEqual(["first", "state 7", "state 8", "state 9"], sent)


if __name__ == "__main__":
    unittest.main()

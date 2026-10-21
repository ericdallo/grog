import os
import signal
import tempfile
import threading
import time
import unittest
from unittest import mock

from grog_voice import worker
from grog_voice.worker import Worker


class Echo:
    """A fake speech to text: the audio as text, after `delay_s`, and which
    process it was."""

    def __init__(self, delay_s=0.0):
        self.delay_s = delay_s

    def __call__(self, pcm):
        time.sleep(self.delay_s)
        return f"{pcm.decode()} from {os.getpid()}"


class Failing:
    def __call__(self, pcm):
        raise ValueError(f"couldn't make out {pcm.decode()!r}")


class Dying:
    """A fake speech to text that dies as it loads, noting it in `path`."""

    def __init__(self, path):
        with open(path, "a") as f:
            f.write("started\n")
        os._exit(1)


def pid(text):
    return int(text.rsplit(" from ", 1)[1])


def state(pid):
    """A process's state (R, S, T for stopped, Z for dead...), or None once
    it's gone."""
    try:
        with open(f"/proc/{pid}/stat") as f:
            return f.read().rsplit(")", 1)[1].split()[0]
    except FileNotFoundError:
        return None


def until(check, timeout=10):
    """Whether `check()` came true within `timeout`."""
    deadline = time.monotonic() + timeout
    while not check():
        if time.monotonic() > deadline:
            return False
        time.sleep(0.01)
    return True


class WorkerTest(unittest.TestCase):
    def worker(self, *args, **kwargs):
        w = Worker(*args, **kwargs)
        self.addCleanup(w.close)
        return w

    def asking(self, w, pcm):
        """Asks `w` on a thread: its answer, or error, lands in the returned
        list."""
        answers = []

        def ask():
            try:
                answers.append(w(pcm))
            except RuntimeError as e:
                answers.append(e)

        thread = threading.Thread(target=ask, daemon=True)
        thread.start()
        return thread, answers

    def test_transcribes_in_a_process_of_its_own(self):
        w = self.worker(Echo)
        text = w(b"oi")
        self.assertTrue(text.startswith("oi from "))
        self.assertNotEqual(os.getpid(), pid(text))
        self.assertEqual(f"tchau from {pid(text)}", w(b"tchau"), "the same one")

    def test_held_it_stops_until_released_as_many_times(self):
        w = self.worker(Echo)
        child = pid(w(b"started"))
        w.hold()
        w.hold()
        self.assertTrue(until(lambda: state(child) == "T"), "stopped")
        thread, answers = self.asking(w, b"oi")
        w.release()
        self.assertEqual(("T", []), (state(child), answers), "still held once")
        w.release()
        thread.join(5)
        self.assertEqual(["oi"], [a.rsplit(" from ", 1)[0] for a in answers])

    def test_started_ahead_of_time(self):
        w = self.worker(Echo)
        w.start()
        self.assertIsNotNone(w.pid, "ready")
        self.assertEqual(w.pid, pid(w(b"oi")))

    def test_held_before_it_started(self):
        w = self.worker(Echo)
        w.hold()
        thread, answers = self.asking(w, b"oi")
        self.assertTrue(until(lambda: w.pid is not None and state(w.pid) == "T"), "it starts stopped")
        self.assertEqual([], answers)
        w.release()
        thread.join(10)
        self.assertEqual(1, len(answers))
        self.assertEqual(w.pid, pid(answers[0]))

    def test_started_again_while_held_it_starts_stopped(self):
        w = self.worker(Echo)
        first = pid(w(b"oi"))
        os.kill(first, signal.SIGKILL)
        self.assertTrue(until(lambda: state(first) in ("Z", None)))
        w.hold()
        with self.assertLogs("grog_voice", "WARNING"):
            thread, answers = self.asking(w, b"oi")
            self.assertTrue(until(lambda: w.pid not in (None, first) and state(w.pid) == "T"))
        self.assertEqual([], answers)
        w.release()
        thread.join(10)
        self.assertEqual(w.pid, pid(answers[0]))

    def test_its_failures_say_what_kind_not_what_was_said(self):
        w = self.worker(Failing)
        with self.assertRaises(RuntimeError) as failed:
            w(b"segredo")
        self.assertIn("ValueError", str(failed.exception))
        self.assertNotIn("segredo", str(failed.exception))

    def test_started_again_when_it_died(self):
        w = self.worker(Echo)
        first = pid(w(b"oi"))
        os.kill(first, signal.SIGKILL)
        self.assertTrue(until(lambda: state(first) in ("Z", None)))
        with self.assertLogs("grog_voice", "WARNING"):
            self.assertNotEqual(first, pid(w(b"oi")))

    def test_dying_over_and_over_it_waits_before_trying_again(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        path = os.path.join(d.name, "starts")
        w = self.worker(Dying, path, retries=3, retry_s=60)

        def starts():
            with open(path) as f:
                return len(f.readlines())

        with self.assertLogs("grog_voice", "WARNING") as logs:
            for _ in range(3):
                with self.assertRaisesRegex(RuntimeError, "stopped"):
                    w(b"oi")
                self.assertTrue(until(lambda: state(w.pid) in ("Z", None)))
            for _ in range(3):
                with self.assertRaisesRegex(RuntimeError, "keeps stopping"):
                    w(b"oi")
        self.assertEqual(3, starts(), "not again for a while")
        self.assertRegex(logs.output[-1], r"stopped \(1\) 3 times in a row: trying again in 60 s$")
        self.assertEqual(3, len(logs.output), "said once")

    def test_closing_kills_it_held_or_not(self):
        w = Worker(Echo)
        child = pid(w(b"oi"))
        w.hold()
        w.close()
        self.assertIn(state(child), ("Z", None))
        with self.assertRaises(ProcessLookupError):
            os.kill(child, 0)

    def test_closed_it_starts_no_more(self):
        w = Worker(Echo, 5.0)
        w.start()
        child = w.pid
        thread, answers = self.asking(w, b"oi")
        w.close()
        thread.join(10)
        self.assertIsInstance(answers[0], RuntimeError, "what was under way failed")
        with self.assertRaisesRegex(RuntimeError, "closed"):
            w(b"oi")
        self.assertIsNone(w.pid)
        self.assertIn(state(child), ("Z", None))

    def test_it_is_killed_at_exit_once_registered(self):
        with mock.patch.object(worker.atexit, "register") as register:
            w = self.worker(Echo)
            first = pid(w(b"oi"))
            os.kill(first, signal.SIGKILL)
            self.assertTrue(until(lambda: state(first) in ("Z", None)))
            with self.assertLogs("grog_voice", "WARNING"):
                w(b"oi")
        register.assert_called_once_with(w.close)


if __name__ == "__main__":
    unittest.main()

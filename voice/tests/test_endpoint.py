import unittest

from grog_voice.endpoint import End, Endpointer, Pause, Resume

SPEECH, SILENCE = 0.9, 0.1


def endpointer(**kw):
    # 1-byte frames of 0.1 s: easy to count. Short utterances end like the
    # others, unless a test says.
    options = {
        "end_silence_s": 0.6,
        "speculate_s": 0.3,
        "max_s": 3.0,
        "min_speech_s": 0.2,
        "short_speech_s": 0.0,
        "preroll_s": 0.2,
        "tail_s": 0.2,
        **kw,
    }
    return Endpointer(1, 0.1, **options)


def feed(ep, probs, start=0):
    """Pushes a frame per probability (frame i is the byte i + start, mod 256)
    and returns the events with the index of the frame that caused them."""
    events = []
    for i, p in enumerate(probs):
        if (event := ep.push(bytes([(start + i) % 256]), p)) is not None:
            events.append((start + i, event))
    return events


class EndpointerTest(unittest.TestCase):
    def test_silence_is_nothing(self):
        self.assertEqual([], feed(endpointer(), [SILENCE] * 50))

    def test_utterance_with_preroll_and_tail(self):
        probs = [SILENCE] * 3 + [SPEECH] * 5 + [SILENCE] * 6
        events = feed(endpointer(), probs)
        pause_at, pause = events[0]
        end_at, end = events[1]
        self.assertEqual(Pause, type(pause))
        self.assertEqual(10, pause_at)  # 3 silent frames (0.3 s) after the speech
        self.assertEqual(13, end_at)  # 6 silent frames (0.6 s)
        # 2 frames (0.2 s) of preroll, the 5 of speech, 2 of tail.
        self.assertEqual(bytes(range(1, 10)), end.audio)
        self.assertAlmostEqual(0.6, end.silence_s)
        self.assertAlmostEqual(0.5, end.speech_s)
        self.assertEqual(pause.audio, end.audio, "the guess at the pause is the final audio")

    def test_resume_after_a_pause(self):
        probs = [SPEECH] * 4 + [SILENCE] * 3 + [SPEECH] * 2 + [SILENCE] * 6
        events = [type(e) for _, e in feed(endpointer(), probs)]
        self.assertEqual([Pause, Resume, Pause, End], events)
        end = feed(endpointer(), probs)[-1][1]
        self.assertEqual(bytes(range(0, 11)), end.audio)

    def test_no_speculation(self):
        probs = [SPEECH] * 4 + [SILENCE] * 6
        self.assertEqual([End], [type(e) for _, e in feed(endpointer(speculate_s=0), probs)])

    def test_cut_at_the_maximum(self):
        events = feed(endpointer(), [SPEECH] * 40)
        self.assertEqual(End, type(events[0][1]))
        self.assertEqual(29, events[0][0])  # 3 s of utterance (preroll included)
        self.assertEqual(0.0, events[0][1].silence_s)

    def test_a_click_is_dropped(self):
        # 0.3 s of speech starts an utterance, but it's under the minimum.
        ep = endpointer(min_speech_s=0.5)
        self.assertEqual([Pause], [type(e) for _, e in feed(ep, [SPEECH] * 3 + [SILENCE] * 6)])
        self.assertEqual(End, type(feed(ep, [SPEECH] * 5 + [SILENCE] * 6, start=20)[-1][1]))

    def test_short_utterances_wait_longer(self):
        ep = endpointer(short_speech_s=0.8, short_end_silence_s=1.2, speculate_s=0)
        # Like just a wake word: 0.4 s of speech, then a pause.
        self.assertEqual([], feed(ep, [SPEECH] * 4 + [SILENCE] * 10))
        events = feed(ep, [SILENCE] * 2, start=14)
        self.assertEqual(End, type(events[0][1]))
        self.assertAlmostEqual(1.2, events[0][1].silence_s)
        # Longer ones end after the usual pause.
        ep.reset()
        self.assertEqual(End, type(feed(ep, [SPEECH] * 9 + [SILENCE] * 6)[-1][1]))

    def test_needs_consecutive_voiced_frames_to_start(self):
        self.assertEqual([], feed(endpointer(), [SPEECH, SILENCE] * 20))

    def test_says_when_an_utterance_is_under_way(self):
        ep = endpointer()
        feed(ep, [SILENCE] * 3 + [SPEECH] * 2)
        self.assertFalse(ep.started, "not yet: a few voiced frames in a row start it")
        feed(ep, [SPEECH])
        self.assertTrue(ep.started)
        feed(ep, [SILENCE] * 5)
        self.assertTrue(ep.started, "a pause")
        feed(ep, [SILENCE])
        self.assertFalse(ep.started, "the end")

    def test_what_was_said_so_far_while_they_talk(self):
        ep = endpointer()
        self.assertEqual((b"", 0.0, False), (ep.audio(), ep.seconds, ep.talking))
        feed(ep, [SILENCE] * 3 + [SPEECH] * 4)
        # 2 frames of preroll, then the 4 of speech.
        self.assertEqual(bytes(range(1, 7)), ep.audio())
        self.assertAlmostEqual(0.6, ep.seconds)
        self.assertTrue(ep.talking)
        feed(ep, [SILENCE] * 2, start=7)
        self.assertTrue(ep.talking, "a breath")
        feed(ep, [SILENCE], start=9)
        self.assertFalse(ep.talking, "a pause")
        self.assertTrue(ep.started)

    def test_reset_forgets_the_utterance(self):
        ep = endpointer()
        feed(ep, [SPEECH] * 5)
        ep.reset()
        self.assertEqual([], feed(ep, [SILENCE] * 10))


if __name__ == "__main__":
    unittest.main()

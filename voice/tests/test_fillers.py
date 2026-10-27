import unittest

from fakes import FakeTts

from grog_voice.audio import Silence
from grog_voice.fillers import PHRASES, Fillers, kind, phrases


class PausingTts(FakeTts):
    """Says each text, then pauses."""

    def __call__(self, text):
        yield from super().__call__(text)
        yield Silence(8)


class KindTest(unittest.TestCase):
    def test_tools_that_look_something_up(self):
        self.assertEqual("search", kind("web_search"))
        for tool in ("weather", "system_status", "ha__GetLiveContext", "casa__GetLiveContext"):
            self.assertEqual("check", kind(tool), tool)

    def test_tools_that_act_have_none(self):
        for tool in ("lights", "music", "timers", "memory", "ha__HassTurnOn", "GetLiveContext_extra"):
            self.assertIsNone(kind(tool), tool)


class PhrasesTest(unittest.TestCase):
    def test_built_in_by_language(self):
        self.assertEqual(PHRASES["pt"], phrases(True, "pt"))
        self.assertEqual(["Checando.", "Tô vendo."], phrases(True, "pt")["check"])
        self.assertEqual(PHRASES["en"], phrases(True, "en"))
        self.assertEqual({}, phrases(True, "xx"), "none in a language it has none in")
        self.assertEqual({}, phrases(True, None))

    def test_its_own_or_none(self):
        self.assertEqual({"search": ["Pesquisando."]}, phrases({"search": ["Pesquisando."]}, "pt"))
        self.assertEqual({}, phrases(False, "pt"))


class FillersTest(unittest.TestCase):
    def test_made_once_without_the_pause_after_them(self):
        tts = PausingTts()
        fillers = Fillers({"search": ["Buscando.", "Procurando."], "check": ["Checando!"]})
        with self.assertLogs("grog_voice", "INFO") as logs:
            fillers.make(tts)
        self.assertEqual(["Buscando.", "Procurando.", "Checando."], tts.texts, "calmed")
        self.assertRegex(logs.output[0], r"^INFO:grog_voice:fillers made in [\d.]+ s$")
        self.assertEqual(b"Checando.", fillers("weather"))
        self.assertEqual(b"Checando.", fillers("ha__GetLiveContext"), "the only one")
        self.assertEqual(["Buscando.", "Procurando.", "Checando."], tts.texts, "not made again")

    def test_taking_turns(self):
        fillers = Fillers(PHRASES["pt"])
        fillers.make(FakeTts())
        said = [fillers("web_search") for _ in range(3)]
        self.assertEqual(["Buscando.", "Procurando.", "Buscando."], [pcm.decode() for pcm in said])
        self.assertEqual("Checando.", fillers("weather").decode(), "each kind takes its own turns")

    def test_none_for_what_acts_for_a_kind_left_out_or_before_they_are_made(self):
        fillers = Fillers({"search": ["Buscando."]})
        self.assertIsNone(fillers("web_search"), "not made yet")
        fillers.make(FakeTts())
        self.assertIsNone(fillers("weather"))
        self.assertIsNone(fillers("lights"))
        empty = Fillers({})
        with self.assertNoLogs("grog_voice"):
            empty.make(FakeTts())
        self.assertIsNone(empty("web_search"))

    def test_text_to_speech_failing_leaves_none(self):
        class Failing(FakeTts):
            def __call__(self, text):
                raise RuntimeError(f"no voice for {text!r}")

        fillers = Fillers(PHRASES["pt"])
        with self.assertLogs("grog_voice", "WARNING") as logs:
            fillers.make(Failing())
        [line] = logs.output
        self.assertRegex(line, r"looks something up: RuntimeError at .*test_fillers\.py:\d+$")
        self.assertNotIn("Buscando", line)
        self.assertIsNone(fillers("web_search"))


if __name__ == "__main__":
    unittest.main()

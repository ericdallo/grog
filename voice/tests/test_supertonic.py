import os
import unittest
from unicodedata import normalize

from grog_voice import supertonic


class PrepareTest(unittest.TestCase):
    def test_between_language_tags_ending_with_punctuation(self):
        self.assertEqual("<pt>Oi, tudo bem.</pt>", supertonic.prepare("Oi, tudo bem", "pt"))
        self.assertEqual("<en>Ready?</en>", supertonic.prepare("Ready?", "en"))
        self.assertEqual("<pt>Pronto!</pt>", supertonic.prepare("  Pronto!\n", "pt"))

    def test_decomposed_with_plain_punctuation_and_single_spaces(self):
        self.assertEqual(
            "<pt>" + normalize("NFKD", 'São Paulo - "agora", 20 graus.') + "</pt>",
            supertonic.prepare("São  Paulo — “agora” , 20 graus.", "pt"),
        )


class SymbolsTest(unittest.TestCase):
    def test_leaves_out_what_the_indexer_doesnt_know(self):
        indexer = [-1] * 128
        indexer[ord("o")], indexer[ord("i")], indexer[ord(".")] = 1, 2, 3
        self.assertEqual([1, 2, 3], supertonic.symbols("oi?.🌧", indexer))


class PiecesTest(unittest.TestCase):
    def test_short_texts_whole(self):
        self.assertEqual(["Oi. Tudo bem?"], supertonic.pieces(" Oi.\n Tudo  bem? ", "pt"))
        self.assertEqual([], supertonic.pieces("  ", "pt"))

    def test_long_ones_after_the_last_sentence_end_that_fits(self):
        sentence = "Uma frase de cinquenta caracteres, mais ou menos."
        text = " ".join([sentence] * 8)
        pieces = supertonic.pieces(text, "pt")
        self.assertEqual([" ".join([sentence] * 6), " ".join([sentence] * 2)], pieces)
        self.assertTrue(all(len(p) <= supertonic.DEFAULT_MAX_CHARS for p in pieces))

    def test_or_else_a_comma_or_a_space(self):
        clause = "uma parte de quarenta caracteres e pouco"
        self.assertEqual(
            [", ".join([clause] * 7) + ",", clause + "."],
            supertonic.pieces(", ".join([clause] * 8) + ".", "pt"),
        )
        words = ("palavra " * 50).strip()
        pieces = supertonic.pieces(words, "pt")
        self.assertEqual(words, " ".join(pieces))
        self.assertTrue(all(len(p) <= 300 and not p.endswith(" ") for p in pieces), pieces)
        self.assertEqual(["x" * 300, "x" * 20], supertonic.pieces("x" * 320, "pt"))

    def test_shorter_in_japanese_and_korean(self):
        self.assertEqual(2, len(supertonic.pieces("あ" * 200, "ja")))
        self.assertEqual(1, len(supertonic.pieces("a" * 200, "pt")))


class VoiceStyleTest(unittest.TestCase):
    def test_a_voice_of_the_model_or_a_file(self):
        self.assertEqual(os.path.join("/m", "voice_styles", "M1.json"), supertonic.voice_style("/m", "M1"))
        self.assertEqual("/voices/mine.json", supertonic.voice_style("/m", "/voices/mine.json"))
        self.assertEqual(("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"), supertonic.VOICES)

    def test_latent_frames(self):
        self.assertEqual(1, supertonic.latent_frames(1, 3072))
        self.assertEqual(1, supertonic.latent_frames(3072, 3072))
        self.assertEqual(2, supertonic.latent_frames(3073, 3072))
        self.assertEqual(1, supertonic.latent_frames(0, 3072))

    def test_duration_samples_shorter_when_faster(self):
        self.assertEqual(44100, supertonic.duration_samples(1.0, 44100, 1.0))
        self.assertEqual(36750, supertonic.duration_samples(1.0, 44100, 1.2))
        self.assertEqual(88200, supertonic.duration_samples(1.0, 44100, 0.5))


if __name__ == "__main__":
    unittest.main()

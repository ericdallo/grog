import os
import tempfile
import unittest

from grog_voice import pocket

# As the Portuguese model's export wrote them (pocket.json).
RULES = {
    "replace_characters": {'"': "", "“": "", "”": "", "(": "", ")": "", "–": ","},
    "remove_semicolons": False,
    "pad_with_spaces_for_short_inputs": False,
    "append_terminal_punctuation": True,
    "capitalize_first_letter": True,
    "tokens_per_second": 3.0,
    "padding_seconds": 2.0,
    "sample_rate": 24000,
    "frame_samples": 1920,
}


def words(text):
    return len(text.split())


class PrepareTest(unittest.TestCase):
    def test_capitalized_and_ended_with_a_sentence_mark(self):
        self.assertEqual(("Claro, já aumentei.", 3), pocket.prepare(" claro, já aumentei ", RULES))
        self.assertEqual(
            ("Quer que eu te lembre amanhã?", 1), pocket.prepare("Quer que eu te lembre amanhã?", RULES)
        )
        self.assertEqual(("Pronto.", 3), pocket.prepare("Pronto:", RULES), "a weak mark becomes a period")
        self.assertEqual(("Ele disse oi.", 3), pocket.prepare("Ele disse oi", RULES))

    def test_characters_replaced_and_spaces_squeezed(self):
        self.assertEqual(
            ("Ela disse Oi! e foi embora afinal.", 1),
            pocket.prepare("ela disse “Oi!”, e foi\n embora (afinal)", RULES),
        )

    def test_nothing_to_say(self):
        self.assertEqual(("", 0), pocket.prepare(' "" ', RULES))

    def test_the_rest_of_pocket_tts_rules(self):
        rules = RULES | {"remove_semicolons": True, "pad_with_spaces_for_short_inputs": True}
        self.assertEqual(("        Sim, não.", 3), pocket.prepare("sim; não", rules))


class PiecesTest(unittest.TestCase):
    def test_short_texts_whole(self):
        self.assertEqual(["Oi. Tudo bem?"], pocket.pieces(" Oi.  Tudo bem? ", words, 50))
        self.assertEqual([], pocket.pieces("  ", words, 50))

    def test_sentences_together_while_they_fit(self):
        self.assertEqual(
            ["um dois. três quatro.", "cinco seis."],
            pocket.pieces("um dois. três quatro. cinco seis.", words, 4),
        )

    def test_long_sentences_cut_at_commas(self):
        self.assertEqual(
            ["um dois três, quatro cinco,", "seis sete oito."],
            pocket.pieces("um dois três, quatro cinco, seis sete oito.", words, 5),
        )


class VoicesTest(unittest.TestCase):
    def test_voice_files(self):
        self.assertEqual(os.path.join("/m", "voices", "deep-calm.npz"), pocket.voice_file("/m", "deep-calm"))
        self.assertEqual("/v/mine.npz", pocket.voice_file("/m", "/v/mine.npz"), "a file of its own")

    def test_the_models_voices(self):
        with tempfile.TemporaryDirectory() as model:
            self.assertEqual([], pocket.voices(model), "no voices/")
            os.mkdir(os.path.join(model, "voices"))
            for f in ("radio.npz", "deep-calm.npz", "notes.txt"):
                open(os.path.join(model, "voices", f), "w").close()
            self.assertEqual(["deep-calm", "radio"], pocket.voices(model))


class TimingTest(unittest.TestCase):
    def test_max_frames(self):
        self.assertEqual(150, pocket.max_frames(30, RULES), "(30 / 3 + 2) s at 12.5 frames a second")

    def test_ending(self):
        ending = pocket.Ending(min_frames=6, frames_after=3)
        self.assertEqual([False] * 6, [ending(f, f == 2) for f in range(6)], "too soon to end")
        self.assertEqual([False, False, False, True], [ending(f, f == 7) for f in range(7, 11)])

        never = pocket.Ending(min_frames=6, frames_after=3)
        self.assertFalse(any(never(f, False) for f in range(200)))


class SteadiestTest(unittest.TestCase):
    def attempts(self, *scores):
        """Attempts of 12 frames, each frame being its attempt's noisiness,
        noting which were made."""
        self.made = []
        for n, score in enumerate(scores):
            self.made.append(n)
            yield iter([score] * 12)

    def steadiest(self, attempts):
        return list(pocket.steadiest(attempts, max, check=10, limit=0.03))

    def test_the_first_steady_attempt(self):
        self.assertEqual(
            [0.01] * 12, self.steadiest(self.attempts(0.01, 0.5)), "its checked frames, then the rest"
        )
        self.assertEqual([0], self.made, "the next one isn't made")

    def test_noisy_ones_made_again(self):
        self.assertEqual([0.02] * 12, self.steadiest(self.attempts(0.2, 0.02, 0.01)))
        self.assertEqual([0, 1], self.made)

    def test_the_least_noisy_when_they_all_are(self):
        self.assertEqual([0.05] * 12, self.steadiest(self.attempts(0.2, 0.05, 0.1)))
        self.assertEqual([0, 1, 2], self.made)

    def test_shorter_than_checked(self):
        self.assertEqual([0.01, 0.01], self.steadiest(iter([iter([0.01, 0.01])])))

    def test_nothing_to_say(self):
        self.assertEqual([], self.steadiest(iter([])))

    def test_short_pieces_checked(self):
        self.assertTrue(pocket.checked("        Tô ouvindo, sim!"))
        self.assertTrue(pocket.checked("Pronto! A luz da cozinha está apagada."))
        self.assertFalse(pocket.checked("Amanhã deve fazer vinte e oito graus em São Paulo."))


class PauseTest(unittest.TestCase):
    def test_sentence_ends(self):
        self.assertTrue(pocket.ends_sentence("Claro, já aumentei."))
        self.assertTrue(pocket.ends_sentence("Quer mais alguma coisa?"))
        self.assertTrue(pocket.ends_sentence('Ele disse "oi!" '))
        self.assertFalse(pocket.ends_sentence("Se for sair depois das cinco,"))
        self.assertFalse(pocket.ends_sentence(""))

    def test_up_to_a_pause_after_a_sentence(self):
        speech = [-20.0] * 50
        self.assertAlmostEqual(0.4, pocket.pause("Pronto.", speech, 0.01), msg="no quiet at its end")
        self.assertAlmostEqual(0.3, pocket.pause("Pronto.", speech + [-60.0] * 10, 0.01))
        self.assertAlmostEqual(
            0.4, pocket.pause("Pronto.", speech + [-60.0] * 10 + [-20.0], 0.01), msg="its end"
        )
        self.assertEqual(0.0, pocket.pause("Pronto.", speech + [-60.0] * 50, 0.01), "already long enough")
        self.assertAlmostEqual(0.3, pocket.pause("Pronto.", speech + [-56.0] * 10, 0.01), msg="35 dB under")
        self.assertAlmostEqual(0.4, pocket.pause("Pronto.", speech + [-50.0] * 10, 0.01), msg="not quiet")

    def test_no_pause_mid_sentence(self):
        self.assertEqual(0.0, pocket.pause("Se for sair depois das cinco,", [-20.0] * 50, 0.01))
        self.assertEqual(0.0, pocket.pause("Pronto.", [], 0.01))


class NumbersTest(unittest.TestCase):
    def test_cardinals(self):
        for n, said in [
            (0, "zero"), (1, "um"), (16, "dezesseis"), (21, "vinte e um"), (100, "cem"), (101, "cento e um"),
            (110, "cento e dez"), (200, "duzentos"), (999, "novecentos e noventa e nove"), (1000, "mil"),
            (1001, "mil e um"), (1200, "mil e duzentos"), (1234, "mil duzentos e trinta e quatro"),
            (2026, "dois mil e vinte e seis"), (21000, "vinte e um mil"), (100000, "cem mil"),
            (1000000, "um milhão"), (1500000, "um milhão e quinhentos mil"),
            (2000500, "dois milhões e quinhentos"),
            (1234567, "um milhão duzentos e trinta e quatro mil quinhentos e sessenta e sete"),
            (3000000000, "três bilhões"), (-5, "menos cinco"),
        ]:  # fmt: skip
            self.assertEqual(said, pocket.cardinal(n), n)

    def test_feminine(self):
        self.assertEqual("uma", pocket.cardinal(1, feminine=True))
        self.assertEqual("vinte e duas", pocket.cardinal(22, feminine=True))
        self.assertEqual("duzentas e uma", pocket.cardinal(201, feminine=True))
        self.assertEqual("duas mil", pocket.cardinal(2000, feminine=True))
        self.assertEqual("dois milhões", pocket.cardinal(2000000, feminine=True), "milhão is masculine")

    def test_ordinals(self):
        self.assertEqual("primeiro", pocket.ordinal(1))
        self.assertEqual("segunda", pocket.ordinal(2, feminine=True))
        self.assertEqual("vigésimo primeiro", pocket.ordinal(21))
        self.assertEqual("décima", pocket.ordinal(10, feminine=True))
        self.assertEqual("cem", pocket.ordinal(100), "a cardinal past 99")

    def test_spelled_out_in_text(self):
        for text, said in [
            ("Amanhã deve fazer 28 graus.", "Amanhã deve fazer vinte e oito graus."),
            ("Faz 28°C agora", "Faz vinte e oito graus agora"),
            ("Faz 28ºC agora", "Faz vinte e oito graus agora"),
            ("Vai a -3° de madrugada", "Vai a menos três graus de madrugada"),
            ("Custa R$ 10,50.", "Custa dez reais e cinquenta centavos."),
            ("Custa R$1", "Custa um real"),
            ("só R$ 0,01", "só um centavo"),
            ("Custa R$ 1.500", "Custa mil e quinhentos reais"),
            ("às 14:30", "às catorze e trinta"),
            ("às 21h05", "às vinte e uma e cinco"),
            ("à 1h", "à uma hora"),
            ("Chega às 9h.", "Chega às nove horas."),
            ("Subiu 50%.", "Subiu cinquenta por cento."),
            ("Subiu 2,5%.", "Subiu dois vírgula cinco por cento."),
            ("Ficou em 1º lugar", "Ficou em primeiro lugar"),
            ("pela 3ª vez", "pela terceira vez"),
            ("Vieram 1.500 pessoas", "Vieram mil e quinhentas pessoas"),
            ("São 2 dias e 2 semanas", "São dois dias e duas semanas"),
            ("Pi é 3,14 ou 3.14", "Pi é três vírgula catorze ou três vírgula catorze"),
            ("Faltam 1,05 metros", "Faltam um vírgula zero cinco metros"),
            ("Em 2026.", "Em dois mil e vinte e seis."),
            ("Corri 10km", "Corri dez km"),
            (
                "O código é 12345678901234",
                "O código é um dois três quatro cinco seis sete oito nove zero um dois três quatro",
            ),
            ("Ouvi um MP3.", "Ouvi um MP3."),
            ("De 10 a 20, 3 vezes", "De dez a vinte, três vezes"),
        ]:
            self.assertEqual(said, pocket.spell_numbers(text), text)


if __name__ == "__main__":
    unittest.main()

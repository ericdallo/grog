import unittest

from grog_voice.wake import WakeWords, sound


class SoundTest(unittest.TestCase):
    def test_spellings_of_grog(self):
        # How Parakeet heard "Grog" in Portuguese.
        for word in ["Grog", "grog", "Groga", "Grogue", "Grógui", "Crog", "Croga", "Grok", "Grogg", "GROG"]:
            self.assertEqual(sound("Grog"), sound(word), word)

    def test_other_words(self):
        for word in ["Droga", "Broca", "Troca", "Pro", "Bro", "Claro", "Grau", "Grosso", "Gol", "Geral"]:
            self.assertNotEqual(sound("Grog"), sound(word), word)

    def test_portuguese_spellings(self):
        self.assertEqual(sound("guerra"), sound("kera"))
        self.assertNotEqual(sound("gera"), sound("kera"), "g before e is soft")
        self.assertEqual(sound("quilo"), sound("kilo"))
        self.assertEqual(sound("chave"), sound("xave"))
        self.assertEqual(sound("hora"), sound("ora"))


class WakeWordsTest(unittest.TestCase):
    def test_command_after_the_wake_word(self):
        wake = WakeWords(["Grog"])
        self.assertEqual("que horas são?", wake.match("Grog, que horas são?"))
        self.assertEqual(
            "qual é a previsão do tempo para amanhã?",
            wake.match("Groga, qual é a previsão do tempo para amanhã?"),
        )
        self.assertEqual("Acende a luz da sala.", wake.match("Grogue. Acende a luz da sala."))

    def test_words_before_it(self):
        wake = WakeWords(["Grog"])
        self.assertEqual("me conta uma piada.", wake.match("Ei, Grog, me conta uma piada."))
        self.assertEqual("acende a luz.", wake.match("Ok Grog acende a luz."))
        self.assertIsNone(wake.match("Você viu o Grog ontem?"), "too far in")

    def test_another_first_plosive_from_afar(self):
        # How Parakeet heard "Grog" from a few meters away, through a speakerphone.
        wake = WakeWords(["Grog"])
        self.assertEqual("que horas são?", wake.match("Drog, que horas são?"))
        self.assertEqual("coração.", wake.match("Drogue, coração."))
        self.assertEqual("acende a luz.", wake.match("Prog, acende a luz."))
        self.assertEqual("", wake.match("Drog."))
        for text in ["Droga, esqueci.", "O troco tá aqui.", "Truco!", "Troca o canal.", "Broca nova."]:
            self.assertIsNone(wake.match(text), text)

    def test_just_the_wake_word(self):
        wake = WakeWords(["Grog"])
        self.assertEqual("", wake.match("Grog."))
        self.assertEqual("", wake.match("Ei, Groga?"))

    def test_not_addressed(self):
        wake = WakeWords(["Grog"])
        for text in [
            "Droga, esqueci a chave.",
            "Pro jantar, vamos fazer massa.",
            "Bro, você viu o jogo?",
            "Que horas são?",
            "Claro, pode deixar.",
            "",
        ]:
            self.assertIsNone(wake.match(text), text)

    def test_other_names_and_phrases(self):
        self.assertEqual("que horas são?", WakeWords(["Computador"]).match("Computador, que horas são?"))
        self.assertEqual("what time is it?", WakeWords(["Jarvis"]).match("Jarvis, what time is it?"))
        self.assertEqual("liga a TV.", WakeWords(["Test"]).match("Teste, liga a TV."))
        wake = WakeWords(["ok computer", "Alexa"])
        self.assertEqual("play music", wake.match("OK, computer: play music"))
        self.assertEqual("toca música", wake.match("Alexa, toca música"))
        self.assertIsNone(wake.match("Computer, play music"), "the whole phrase")

    def test_no_phrases_match_nothing(self):
        self.assertIsNone(WakeWords([]).match("Grog, que horas são?"))
        self.assertIsNone(WakeWords(["", "!!"]).match("Grog, que horas são?"))


if __name__ == "__main__":
    unittest.main()

import unittest

from grog_voice.chunks import Chunker


def chunks(*pieces):
    chunker = Chunker()
    out = [c for piece in pieces for c in chunker.add(piece)]
    return out + chunker.flush()


class ChunkerTest(unittest.TestCase):
    def test_sentences(self):
        self.assertEqual(
            ["De boa!", "E você?", "Tudo certo por aqui."], chunks("De boa! E você? Tudo certo por aqui.")
        )

    def test_waits_for_whitespace_after_the_punctuation(self):
        self.assertEqual(["Custa 2.5 reais.", "Ok."], chunks("Custa 2.5 reais. Ok."))
        chunker = Chunker()
        self.assertEqual([], chunker.add("Custa 2."))
        self.assertEqual([], chunker.add("5 reais."))
        self.assertEqual(["Custa 2.5 reais."], chunker.add(" Ok"))

    def test_first_chunk_may_end_at_a_clause(self):
        self.assertEqual(
            [
                "O Brasil ganhou a Copa de 2002,",
                "vencendo a Alemanha por dois a zero.",
                "Foi o penta, com dois gols do Ronaldo.",
            ],
            chunks(
                "O Brasil ganhou a Copa de 2002, vencendo a Alemanha por dois a zero. "
                "Foi o penta, com dois gols do Ronaldo."
            ),
        )

    def test_but_not_too_soon(self):
        self.assertEqual(["Sim, claro que sim.", "Pode deixar."], chunks("Sim, claro que sim. Pode deixar."))

    def test_streamed_or_whole_is_the_same(self):
        text = "Amanhã deve fazer vinte e oito graus, com chuva à tarde. Leve um guarda-chuva!"
        self.assertEqual(
            ["Amanhã deve fazer vinte e oito graus,", "com chuva à tarde.", "Leve um guarda-chuva!"],
            chunks(text),
        )
        self.assertEqual(chunks(text), chunks(*text))
        self.assertEqual(chunks(text), chunks(*(w + " " for w in text.split(" "))))

    def test_flush(self):
        chunker = Chunker()
        self.assertEqual([], chunker.add("Sem ponto final"))
        self.assertEqual(["Sem ponto final"], chunker.flush())
        self.assertEqual([], chunker.flush())

    def test_starts_afresh_after_a_flush(self):
        chunker = Chunker()
        self.assertEqual(["Vou ver."], chunker.add("Vou ver. Um momento"))
        self.assertEqual(["Um momento"], chunker.flush())
        # What follows (after a web search, say) may end at a clause again.
        self.assertEqual(["Amanhã faz sol,"], chunker.add("Amanhã faz sol, com chuva"))


if __name__ == "__main__":
    unittest.main()

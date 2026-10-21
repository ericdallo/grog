import os
import tempfile
import unittest

from grog_voice.speech import parakeet_files


class ParakeetFilesTest(unittest.TestCase):
    def setUp(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.dir = d.name

    def touch(self, *names):
        for name in names:
            open(os.path.join(self.dir, name), "w").close()

    def test_int8_first_then_fp32(self):
        self.assertIsNone(parakeet_files(self.dir))
        self.touch("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        self.assertEqual(
            {
                "encoder": os.path.join(self.dir, "encoder.onnx"),
                "decoder": os.path.join(self.dir, "decoder.onnx"),
                "joiner": os.path.join(self.dir, "joiner.onnx"),
                "tokens": os.path.join(self.dir, "tokens.txt"),
            },
            parakeet_files(self.dir),
        )
        self.touch("encoder.int8.onnx", "decoder.int8.onnx")
        self.assertEqual(
            os.path.join(self.dir, "encoder.onnx"),
            parakeet_files(self.dir)["encoder"],
            "int8 isn't all there",
        )
        self.touch("joiner.int8.onnx")
        self.assertEqual(os.path.join(self.dir, "joiner.int8.onnx"), parakeet_files(self.dir)["joiner"])

    def test_tokens_are_needed(self):
        self.touch("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")
        self.assertIsNone(parakeet_files(self.dir))

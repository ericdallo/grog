import copy
import json
import os
import subprocess
import unittest

from grog_voice import speakers

# What pw-dump said on a laptop with a Bluetooth speaker (trimmed to what
# matters, the speaker renamed): its built-in speaker, three HDMI ports (a
# screen on the first), its mics, grog's echo canceller, and the Bluetooth
# speaker, the default.
with open(os.path.join(os.path.dirname(__file__), "pw-dump.json"), encoding="utf-8") as f:
    LAPTOP = json.load(f)

BLUETOOTH = "bluez_output.00_11_22_33_44_55.1"
BUILT_IN = "alsa_output.pci-0000_00_1f.3-platform-skl_hda_dsp_generic.HiFi__Speaker__sink"
HDMI = "alsa_output.pci-0000_00_1f.3-platform-skl_hda_dsp_generic.HiFi__HDMI1__sink"


def with_default(dump, name):
    """`dump` with speaker `name` the default."""
    dump = copy.deepcopy(dump)
    for o in dump:
        for m in o.get("metadata") or []:
            if m["key"] == "default.audio.sink":
                m["value"] = {"name": name}
    return dump


class FakeRun:
    """subprocess.run for pw-dump, saying the next of `dumps` each time (the
    last one again once they're all said), and pw-metadata. What it ran, in
    .ran; `failing`: what exits with 1."""

    def __init__(self, *dumps, failing=()):
        self.dumps, self.failing, self.ran = list(dumps), failing, []

    def __call__(self, argv, capture_output, timeout):
        self.ran.append(argv)
        if argv[0] in self.failing:
            return subprocess.CompletedProcess(argv, 1, b"", b"")
        if argv[0] != "pw-dump":
            return subprocess.CompletedProcess(argv, 0, b"", b"")
        dump = self.dumps.pop(0) if len(self.dumps) > 1 else self.dumps[0]
        return subprocess.CompletedProcess(argv, 0, json.dumps(dump).encode(), b"")


class ListedTest(unittest.TestCase):
    def test_a_laptop_with_a_bluetooth_speaker(self):
        self.assertEqual(
            [
                {"name": BLUETOOTH, "label": "EDIFIER R1280DB", "kind": "bluetooth", "default": True},
                {"name": BUILT_IN, "label": "Speaker", "kind": "speaker", "default": False},
                {"name": HDMI, "label": "HDMI / DisplayPort 1 Output", "kind": "hdmi", "default": False},
            ],
            speakers.listed(LAPTOP),
            "neither the HDMI ports without a screen, nor grog's echo canceller, nor the mics",
        )

    def test_a_speaker_without_a_device(self):
        dump = [
            {
                "id": 31,
                "type": "PipeWire:Interface:Node",
                "info": {
                    "props": {
                        "node.name": "null-speaker",
                        "node.description": "Null speaker",
                        "media.class": "Audio/Sink",
                    }
                },
            },
            {
                "id": 32,
                "type": "PipeWire:Interface:Node",
                "info": {"props": {"node.name": "x", "media.class": "Audio/Sink", "priority.session": -1}},
            },
            {"id": 33, "type": "PipeWire:Interface:Node", "info": {"props": {"media.class": "Audio/Sink"}}},
            {"id": 34, "type": "PipeWire:Interface:Node", "info": None},
            {
                "id": 35,
                "type": "PipeWire:Interface:Node",
                "info": {"props": {"node.name": "plain", "media.class": "Audio/Sink"}},
            },
        ]
        self.assertEqual(
            [
                {"name": "null-speaker", "label": "Null speaker", "kind": "speaker", "default": False},
                {"name": "plain", "label": "plain", "kind": "speaker", "default": False},
            ],
            speakers.listed(dump),
        )

    def test_the_default(self):
        self.assertEqual(BLUETOOTH, speakers.default_name(LAPTOP))
        self.assertEqual(BUILT_IN, speakers.default_name(with_default(LAPTOP, BUILT_IN)))
        self.assertIsNone(
            speakers.default_name([o for o in LAPTOP if o["type"] != "PipeWire:Interface:Metadata"])
        )


class AnswerTest(unittest.TestCase):
    def test_which_speakers_there_are(self):
        run = FakeRun(LAPTOP)
        answer = speakers.answer({"id": 7}, run=run)
        self.assertEqual({"id": 7, "speakers": speakers.listed(LAPTOP)}, answer)
        self.assertEqual([["pw-dump", "--no-colors"]], run.ran)

    def test_making_one_the_music_s(self):
        run = FakeRun(LAPTOP, LAPTOP, with_default(LAPTOP, BUILT_IN))
        with self.assertLogs("grog_voice", "INFO") as logs:
            answer = speakers.answer({"id": 8, "music": BUILT_IN}, run=run, sleep=lambda s: None)
        self.assertEqual(8, answer["id"])
        self.assertEqual([BUILT_IN], [s["name"] for s in answer["speakers"] if s["default"]])
        self.assertIn(
            [
                "pw-metadata",
                "-n",
                "default",
                "0",
                "default.configured.audio.sink",
                json.dumps({"name": BUILT_IN}),
                "Spa:String:JSON",
            ],
            run.ran,
        )
        self.assertEqual([f"INFO:grog_voice:music on {BUILT_IN}"], logs.output)

    def test_not_a_speaker_there_is(self):
        run = FakeRun(LAPTOP)
        with self.assertLogs("grog_voice", "WARNING"):
            answer = speakers.answer({"id": 9, "music": "gone"}, run=run)
        self.assertEqual({"id": 9, "error": "there's no such speaker now"}, answer)
        self.assertEqual(["pw-dump"], [argv[0] for argv in run.ran], "nothing changed")

    def test_wireplumber_not_taking_it(self):
        run = FakeRun(LAPTOP)
        with self.assertLogs("grog_voice", "WARNING"):
            answer = speakers.answer(
                {"id": 10, "music": HDMI}, run=run, sleep=lambda s: None, wait_s=0.3, poll_s=0.1
            )
        self.assertEqual({"id": 10, "error": "WirePlumber didn't make it the default"}, answer)
        self.assertEqual(4, [argv[0] for argv in run.ran].count("pw-dump"))

    def test_when_pipewire_doesnt_say(self):
        for run, error in (
            (FakeRun(LAPTOP, failing=("pw-dump",)), "pw-dump exited with 1"),
            (FakeRun(LAPTOP, failing=("pw-metadata",)), "pw-metadata exited with 1"),
            (
                lambda argv, **_: subprocess.CompletedProcess(argv, 0, b"{oops", b""),
                "pw-dump said something else than JSON",
            ),
            (
                lambda argv, **_: subprocess.CompletedProcess(argv, 0, b"{}", b""),
                "pw-dump said something else than a list",
            ),
        ):
            with self.subTest(error), self.assertLogs("grog_voice", "WARNING") as logs:
                self.assertEqual(
                    {"id": 1, "error": error}, speakers.answer({"id": 1, "music": BUILT_IN}, run=run)
                )
            self.assertEqual([f"WARNING:grog_voice:speakers: {error}"], logs.output)

    def test_without_pipewire(self):
        def missing(argv, **_):
            raise FileNotFoundError(argv[0])

        with self.assertLogs("grog_voice", "WARNING"):
            answer = speakers.answer({"id": 2}, run=missing)
        self.assertEqual({"id": 2, "error": "pw-dump didn't run (FileNotFoundError)"}, answer)


if __name__ == "__main__":
    unittest.main()

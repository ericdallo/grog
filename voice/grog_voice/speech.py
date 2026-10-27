"""The models: Silero VAD, Parakeet speech-to-text, and text-to-speech with
Pocket TTS, Supertonic or Piper. Their libraries load lazily, so the rest
runs (and is tested) without them."""

import itertools
import os
import zlib

from . import pocket, supertonic
from .audio import Silence, peak_dbfs, stt_gain_db

# Supertonic's speech is brought up to peak there (-1 dBFS), by at most that
# much (+12 dB), like Piper's.
SUPERTONIC_PEAK, SUPERTONIC_MAX_GAIN = 0.89, 4.0
# Pocket's speech, made louder to its level (see pocket.Leveler), is kept
# under -1 dBFS, softly: its peaks over it are rounded off, not clipped.
SOFT_LIMIT = 10 ** (-1 / 20)


class SileroVad:
    """Speech probability of 16 kHz 16-bit frames of 512 samples (pysilero-vad,
    on ggml). Run with OMP_THREAD_LIMIT=1 (see grog_voice.main)."""

    def __init__(self):
        from pysilero_vad import SileroVoiceActivityDetector

        self.vad = SileroVoiceActivityDetector()

    def __call__(self, frame):
        return self.vad(frame)

    def reset(self):
        self.vad.reset()


def parakeet_files(model_dir):
    """The files of the Parakeet TDT export for sherpa-onnx in `model_dir`
    (encoder, decoder, joiner and tokens): its int8 graphs, or else its fp32
    ones, or None when neither is all there."""
    for graphs in (".int8.onnx", ".onnx"):
        files = {part: os.path.join(model_dir, part + graphs) for part in ("encoder", "decoder", "joiner")}
        files["tokens"] = os.path.join(model_dir, "tokens.txt")
        if all(os.path.isfile(f) for f in files.values()):
            return files
    return None


class Parakeet:
    """Speech to text with Parakeet TDT 0.6B v3 (sherpa-onnx): 25 European
    languages, told apart from the audio. In int8, on grognix's Celeron N5095
    (2 threads), 0.5 s for 0.8 s of audio, 1.3 s for 2.5 s: far more accurate
    than Whisper base there, and about as fast. In fp32 it hears more (from
    afar, say), for twice the memory: on a Core Ultra 7 155H, 0.20 s for a
    few seconds of speech with 4 threads, as fast as int8 with 2."""

    def __init__(self, model_dir, threads):
        import sherpa_onnx

        self.recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            **parakeet_files(model_dir),
            num_threads=threads,
            model_type="nemo_transducer",
            provider="cpu",
        )
        self(bytes(16000 * 2))  # the first run is slower

    def __call__(self, pcm):
        """The text of 16 kHz 16-bit mono audio, made louder first when it's
        quiet (see stt_gain_db): as is, Parakeet often hears nothing in speech
        from a few meters away."""
        import numpy as np

        gain = 10 ** (stt_gain_db(peak_dbfs(pcm)) / 20) / 32768
        stream = self.recognizer.create_stream()
        stream.accept_waveform(16000, np.frombuffer(pcm, dtype=np.int16).astype(np.float32) * gain)
        self.recognizer.decode_stream(stream)
        return stream.result.text.strip()


class Piper:
    """Text to speech with a Piper voice, kept loaded: ~0.2 s per second of
    speech on grognix (loading it takes ~2 s). `speed`: 1 is the voice's own
    pace, higher is faster."""

    def __init__(self, voice, speed=1.0):
        from piper import PiperVoice, SynthesisConfig

        self.voice = PiperVoice.load(voice)
        self.sample_rate = self.voice.config.sample_rate
        self.config = SynthesisConfig(length_scale=self.voice.config.length_scale / speed)

    def __call__(self, text):
        """Yields the 16-bit mono audio of `text`, a sentence at a time."""
        for chunk in self.voice.synthesize(text, syn_config=self.config):
            yield chunk.audio_int16_bytes


def _stateful(graph, carried):
    """`graph` (OpenVINO's) with what it carries from a step to the next, its
    inputs and outputs `carried` ({input: output}, by name), kept as
    variables of its requests: they're no longer given, nor returned."""
    from openvino import passes

    manager = passes.Manager()
    manager.register_pass(passes.MakeStateful(carried))
    manager.run_passes(graph)
    return graph


class Pocket:
    """Text to speech with Pocket TTS (Kyutai, CC-BY-4.0) in a cloned voice,
    kept loaded, on OpenVINO's CPU plugin, from the ONNX graphs that
    voice/tools/export_pocket.py made of it: its speech streams out 80 ms
    at a time as it's made. On a Core Ultra 7 155H with 6 threads at 2.4
    GHz, its 24-layer model takes 0.5 s per second of speech, its first
    sound in 0.15 s (the 6-layer one, with turbo: 0.17 s, and 0.03 s). A
    short piece (8 words or fewer) starts once its first 800 ms are made, as
    they're made again when they come out murmured or reverberant (see
    pocket.steadiest);
    each is said as loud as the rest (see pocket.Leveler); one that ends a
    sentence is followed by a pause (see pocket.pause).
    `model` has the graphs (flow_lm.onnx, mimi_decoder.onnx),
    tokenizer.model and pocket.json (how to run them); `voice`, a voice's
    file (see pocket.voice_file)."""

    # Its speech plays as it's made (see mouth.Mouth).
    streams = True

    def __init__(self, model, voice, threads):
        import json

        import numpy as np
        import openvino as ov
        import sentencepiece

        with open(f"{model}/pocket.json", encoding="utf-8") as f:
            self.rules = json.load(f)
        # pocket_tts pads short texts with spaces for its English model, not
        # its Portuguese one, which says them better padded: fewer murmurs
        # (plans/voice.md, "Breathy short answers").
        self.rules["pad_with_spaces_for_short_inputs"] = True
        self.sample_rate = self.rules["sample_rate"]
        self.tokenizer = sentencepiece.SentencePieceProcessor(model_file=f"{model}/tokenizer.model")
        with np.load(voice) as v:
            self.voice, self.gain = v["cache"], 10 ** (float(v["gain_db"]) / 20)
        self._tensor = ov.Tensor
        core, config = ov.Core(), {"INFERENCE_NUM_THREADS": threads, "PERFORMANCE_HINT": "LATENCY"}
        # What a graph carries from a step to the next (the FlowLM's KV
        # cache, a frame longer at each, and Mimi's state) stays in OpenVINO,
        # a variable of each request, rather than going in and out of every
        # step: copying the cache slowed a long piece down as it grew, by up
        # to half (plans/voice.md, "Long answers"). The sound is the same.
        carried = {"flow_lm": {"cache": "cache_out"}, "mimi_decoder": {"state": "state_out"}}
        self.flow, self.mimi = (
            core.compile_model(_stateful(core.read_model(f"{model}/{name}.onnx"), state), "CPU", config)
            for name, state in carried.items()
        )

    def __call__(self, text):
        """Yields the 16-bit mono audio of `text` as it's made, in pieces of
        80 ms (long texts are read in parts, see pocket.pieces). Numbers are
        spelled out in Portuguese, for a model that reads it."""
        if self.rules.get("language") == "pt":
            text = pocket.spell_numbers(text)
        for part in pocket.pieces(text, self._tokens, self.rules["max_tokens"]):
            said, guess = pocket.prepare(part, self.rules)
            if said:
                ahead = pocket.CHECK_FRAMES if pocket.checked(said) else 0
                # Whether it ends a sentence is in its own text: the model's
                # always ends with one.
                yield from self._paused(part, self._leveled(self._synthesize(said, guess), ahead))

    def _leveled(self, frames, ahead):
        """`frames` at the gain that says them as loud as the rest (see
        pocket.Leveler), gliding from a frame's to the next's: the first
        `ahead`, already made, at the gain they call for."""
        leveler = pocket.Leveler()
        head = list(itertools.islice(frames, ahead))
        gain = leveler.start([level for pcm in head for level in self._halves(pcm)])
        for pcm in head:
            yield self._gained(pcm, gain, gain)
        for pcm in frames:
            before, gain = gain, leveler(self._halves(pcm))
            yield self._gained(pcm, before, gain)

    def _halves(self, pcm):
        """The levels (dB) of the halves of a frame (16-bit), 40 ms each."""
        import numpy as np

        x = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        half = len(x) // 2
        return [float(10 * np.log10(np.mean(x[i : i + half] ** 2) + 1e-12)) for i in (0, half) if half]

    def _gained(self, pcm, start_db, end_db):
        """A frame (16-bit) made louder by a gain (dB) going from `start_db`
        to `end_db` along it, its peaks softly kept under SOFT_LIMIT."""
        if not start_db and not end_db:
            return pcm
        import numpy as np

        x = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
        x *= np.linspace(10 ** (start_db / 20), 10 ** (end_db / 20), len(x), dtype=np.float32)
        over = np.abs(x) > SOFT_LIMIT
        if over.any():
            excess = (np.abs(x[over]) - SOFT_LIMIT) / (1 - SOFT_LIMIT)
            x[over] = np.sign(x[over]) * (SOFT_LIMIT + (1 - SOFT_LIMIT) * np.tanh(excess))
        return (x * 32767).astype(np.int16).tobytes()

    def _paused(self, text, frames):
        """`frames`, saying `text`, then the pause a sentence's end calls for
        (see pocket.pause), from their levels in 10 ms windows: Silence,
        which an answer plays only before more speech (see mouth.Pauses)."""
        import numpy as np

        window, levels = self.sample_rate // 100, []
        for pcm in frames:
            x = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
            n = len(x) // window
            levels += (10 * np.log10((x[: n * window].reshape(n, window) ** 2).mean(axis=1) + 1e-9)).tolist()
            yield pcm
        if samples := int(pocket.pause(text, levels, window / self.sample_rate) * self.sample_rate):
            yield Silence(2 * samples)

    def _tokens(self, text):
        return len(self.tokenizer.encode(text))

    def _synthesize(self, text, guess):
        """The frames of `text`: a short one's made ahead, and again with
        other noise while it comes out murmured or reverberant (see
        pocket.steadiest)."""
        if not pocket.checked(text):
            return self._frames(text, guess, 0)
        attempts = (self._frames(text, guess, attempt) for attempt in range(pocket.ATTEMPTS))
        return pocket.steadiest(attempts, self._flaw)

    def _flaw(self, frames):
        """How flawed `frames` (16-bit mono audio) sound, by the worst of how
        much like noise they sound (see _noisiness) and how reverberant (see
        pocket.reverberation), each against its limit: 1 or less is fine."""
        import numpy as np

        x = np.frombuffer(b"".join(frames), dtype=np.int16).astype(np.float32) / 32768
        window = self.sample_rate // 100
        n = len(x) // window
        levels = (10 * np.log10((x[: n * window].reshape(n, window) ** 2).mean(axis=1) + 1e-12)).tolist()
        return max(self._noisiness(frames) / pocket.NOISY, pocket.reverberation(levels) / pocket.REVERBERANT)

    def _noisiness(self, frames):
        """How much like noise `frames` (16-bit mono audio, 80 ms each) sound:
        the median spectral flatness (0 for a tone, 1 for white noise) of
        their loudest 32 ms windows (within 25 dB), 100 to 4000 Hz; speech is
        ~0.01. 1 when even their loudest frame is a murmur (see
        pocket.QUIET)."""
        import numpy as np

        x = np.frombuffer(b"".join(frames), dtype=np.int16).astype(np.float32) / 32768
        size, hop = int(0.032 * self.sample_rate), int(0.01 * self.sample_rate)
        if len(x) < size:
            return 0.0
        step = self.rules["frame_samples"]
        whole = x[: len(x) // step * step].reshape(-1, step)
        if len(whole) and 10 * np.log10(np.mean(whole**2, axis=1).max() + 1e-12) < pocket.QUIET:
            return 1.0
        windows = np.lib.stride_tricks.sliding_window_view(x, size)[::hop] * np.hanning(size)
        power = np.abs(np.fft.rfft(windows, axis=1)) ** 2
        freqs = np.fft.rfftfreq(size, 1 / self.sample_rate)
        level = 10 * np.log10(power.sum(axis=1) + 1e-12)
        band = power[level > level.max() - 25][:, (freqs >= 100) & (freqs <= 4000)] + 1e-12
        return float(np.median(np.exp(np.log(band).mean(axis=1)) / band.mean(axis=1)))

    def _frames(self, text, guess, attempt):
        import numpy as np

        rules = self.rules
        tokens = np.array([self.tokenizer.encode(text)], dtype=np.int64)
        no_tokens, no_noise = np.zeros((1, 0), dtype=np.int64), np.zeros((1, 32), dtype=np.float32)
        # Requests of its own, which hold its caches: a short piece's attempts
        # are made one after the other, and the one said may be an earlier
        # one, taken up again (see pocket.steadiest). Mimi's starts at zero.
        flow, mimi = self.flow.create_infer_request(), self.mimi.create_infer_request()
        # The text first, into the voice's cache, then a latent at a time from
        # "speech starts" (NaN), with noise seeded by the text (and the
        # attempt): the same text sounds the same, and sentences don't all
        # start alike.
        [cache] = flow.query_state()
        cache.state = self._tensor(self.voice)
        flow.infer({"tokens": tokens, "latent": np.zeros((1, 0, 32), np.float32), "noise": no_noise})
        latent = np.full((1, 1, 32), np.nan, dtype=np.float32)
        rng, std = np.random.default_rng(zlib.crc32(text.encode()) + attempt), rules["temperature"] ** 0.5
        after = rules["frames_after_eos"] if rules["frames_after_eos"] is not None else guess + 2
        ending = pocket.Ending(rules["min_frames_before_eos"], after)
        fade_in = np.linspace(0, 1, self.sample_rate // 200, dtype=np.float32)
        for frame in range(pocket.max_frames(tokens.shape[1], rules)):
            noise = (rng.standard_normal((1, 32)) * std).astype(np.float32)
            out = flow.infer({"tokens": no_tokens, "latent": latent, "noise": noise})
            if ending(frame, float(np.asarray(out["eos"]).reshape(-1)[0]) > rules["eos_threshold"]):
                break
            latent = np.asarray(out["latent_out"])[None]
            offset = np.array([frame * rules["mimi_steps_per_frame"]], dtype=np.int64)
            out = mimi.infer({"latent": latent, "offset": offset})
            audio = np.asarray(out["audio"]).reshape(-1) * self.gain
            if frame == 0:  # a fresh decoder starts with a click
                audio[: len(fade_in)] *= fade_in
            yield (np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes()


class Supertonic:
    """Text to speech with Supertonic 3 (Supertone, OpenRAIL-M), kept loaded,
    on OpenVINO's CPU plugin: twice as fast as ONNX Runtime on grognix's
    Celeron N5095, where it takes ~0.5 s per second of speech (4 threads, 5
    steps) and ~1 GB. `model` has its graphs (onnx/) as in
    Supertone/supertonic-3; `style`, a voice (its .json); `language`, what
    it reads (see supertonic.LANGUAGES); `steps`, of its flow: fewer are
    faster, and worse; `speed`: 1 is the voice's own pace, higher is
    faster."""

    def __init__(self, model, style, language, steps, threads, speed=1.0):
        import json

        import numpy as np
        import openvino as ov

        with open(f"{model}/onnx/tts.json", encoding="utf-8") as f:
            cfg = json.load(f)
        with open(f"{model}/onnx/unicode_indexer.json", encoding="utf-8") as f:
            self.indexer = json.load(f)
        with open(style, encoding="utf-8") as f:
            voice = json.load(f)
        self.ttl, self.dp = (
            np.array(voice[k]["data"], dtype=np.float32).reshape(voice[k]["dims"])
            for k in ("style_ttl", "style_dp")
        )
        self.sample_rate = cfg["ae"]["sample_rate"]
        # Samples per latent frame, and its channels.
        self.frame = cfg["ae"]["base_chunk_size"] * cfg["ttl"]["chunk_compress_factor"]
        self.channels = cfg["ttl"]["latent_dim"] * cfg["ttl"]["chunk_compress_factor"]
        self.language, self.steps, self.speed = language, steps, speed
        # Compiled straight from their files, they take half the memory.
        core, config = ov.Core(), {"INFERENCE_NUM_THREADS": threads, "PERFORMANCE_HINT": "LATENCY"}
        self.duration, self.encoder, self.estimator, self.vocoder = (
            core.compile_model(f"{model}/onnx/{name}.onnx", "CPU", config)
            for name in ("duration_predictor", "text_encoder", "vector_estimator", "vocoder")
        )

    def __call__(self, text):
        """Yields the 16-bit mono audio of `text`, a piece at a time (long
        texts are read in pieces, see supertonic.pieces)."""
        for piece in supertonic.pieces(text, self.language):
            if pcm := self._synthesize(piece):
                yield pcm

    def _synthesize(self, text):
        import numpy as np

        ids = supertonic.symbols(supertonic.prepare(text, self.language), self.indexer)
        if not ids:
            return b""
        ids = np.array([ids], dtype=np.int64)
        text_mask = np.ones((1, 1, ids.shape[1]), dtype=np.float32)
        seconds = np.asarray(self.duration({"text_ids": ids, "style_dp": self.dp, "text_mask": text_mask})[0])
        samples = supertonic.duration_samples(float(seconds.reshape(-1)[0]), self.sample_rate, self.speed)
        frames = supertonic.latent_frames(samples, self.frame)
        text_emb = self.encoder({"text_ids": ids, "style_ttl": self.ttl, "text_mask": text_mask})[0]
        latent_mask = np.ones((1, 1, frames), dtype=np.float32)
        # The same noise for the same length: the same text sounds the same.
        latent = np.random.default_rng(0).standard_normal((1, self.channels, frames), dtype=np.float32)
        total = np.array([self.steps], dtype=np.float32)
        for step in range(self.steps):
            latent = self.estimator(
                {
                    "noisy_latent": latent,
                    "text_emb": text_emb,
                    "style_ttl": self.ttl,
                    "text_mask": text_mask,
                    "latent_mask": latent_mask,
                    "current_step": np.array([step], dtype=np.float32),
                    "total_step": total,
                }
            )[0]
        wav = np.asarray(self.vocoder({"latent": latent})[0]).reshape(-1)[:samples]
        # Up to where Piper's speech peaks, which the earcons and the speaker's
        # volume were set for: Supertonic's is ~8 dB lower.
        if (peak := float(np.abs(wav).max())) > 0:
            wav = wav * min(SUPERTONIC_PEAK / peak, SUPERTONIC_MAX_GAIN)
        return (np.clip(wav, -1, 1) * 32767).astype(np.int16).tobytes()

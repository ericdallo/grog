"""The models: Silero VAD, Parakeet speech-to-text and Piper text-to-speech.
Their libraries load lazily, so the rest runs (and is tested) without them."""

from .audio import peak_dbfs, stt_gain_db


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


class Parakeet:
    """Speech to text with Parakeet TDT 0.6B v3 (int8, sherpa-onnx): 25
    European languages, told apart from the audio. On grognix's Celeron N5095
    (2 threads), 0.5 s for 0.8 s of audio, 1.3 s for 2.5 s: far more accurate
    than Whisper base there, and about as fast."""

    def __init__(self, model_dir, threads):
        import sherpa_onnx

        self.recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f"{model_dir}/encoder.int8.onnx",
            decoder=f"{model_dir}/decoder.int8.onnx",
            joiner=f"{model_dir}/joiner.int8.onnx",
            tokens=f"{model_dir}/tokens.txt",
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
    speech on grognix (loading it takes ~2 s)."""

    def __init__(self, voice):
        from piper import PiperVoice

        self.voice = PiperVoice.load(voice)
        self.sample_rate = self.voice.config.sample_rate

    def __call__(self, text):
        """Yields the 16-bit mono audio of `text`, a sentence at a time."""
        for chunk in self.voice.synthesize(text):
            yield chunk.audio_int16_bytes

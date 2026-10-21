# voice

grog-voice, grog's ears and mouth. It listens to a mic, works out when
someone talks to grog and what they said, sends that to the
[brain](../brain/README.md), and speaks the answer. What to answer is never
up to it.

## A turn

1. [Silero VAD](https://github.com/snakers4/silero-vad) tells speech from
   silence. 600 ms of silence ends what you said.
2. [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
   through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), turns it into
   text, in any of 25 European languages. While you're still talking, a
   second Parakeet transcribes what you've said so far, to catch its name
   early.
3. Only what starts with its name gets an answer: "Grog, will it rain
   tomorrow?", or "Hey Grog, ...". The name is matched by how it sounds,
   since speech to text writes it as "Groga" or "Grok" and so on. After its
   name alone, or an answer asking you something, the next thing you say
   doesn't need it.
4. A short sound says it got it, and the brain answers, streamed. Another
   sound means it's looking something up.
5. [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) speaks the
   answer in its voice M1, a sentence at a time, while the rest is still
   coming. It runs on [OpenVINO](https://github.com/openvinotoolkit/openvino),
   twice as fast as ONNX Runtime on grognix's CPU.
   [Piper](https://github.com/OHF-Voice/piper1-gpl) can speak instead
   (`tts.engine`): lighter and quicker to start, but flatter.

It doesn't listen while it talks. A low sound means something went wrong,
and the log says what. A rising one means the brain is there: at start, or
back after it restarted (after an update, say).

The brain can also ask it (`GET /v1/voice/commands`), for someone at grog's
screen: to listen now, without its name (touching its face), to stop
answering, or to switch its mic off. Off, it records nothing at all until
it's switched on again, even after restarts: the brain keeps that.

On grognix, a Celeron N5095, Supertonic takes ~0.5 s per second of speech
(Piper: 0.2), so it starts answering 1 to 3 s after the LLM's first words,
which take 2 to 3 s, more when it searches the web. What was tried for its
voice, and why this one: [plans/voice.md](../plans/voice.md).

## Running it

On NixOS, `services.grog.voice.enable` runs it as a user service of
`services.grog.user`, whose session has the mic and the speaker (PipeWire).
Log that user in at boot, or set `users.users.<name>.linger = true`.

Nix fetches the models: Parakeet, and Supertonic 3 with its ten voices (F1
to F5, M1 to M5), under OpenRAIL-M, which forbids harmful uses (its LICENSE
comes with it). Pick others with `services.grog.voice.sttModel`, `ttsModel`
and `ttsVoice`. `ttsEngine = "piper"` speaks with Piper's pt_BR `faber`
voice, or the one in `ttsVoice`. `sttPrecision = "fp32"` runs Parakeet in
fp32 rather than int8: it hears more, from afar most of all, for ~1.3 GB
more RAM per Parakeet.

`journalctl --user -u grog-voice` shows how long each turn took.

To see how it hears you, or try a turn without a mic:

```sh
grog-voice listen                       # what it hears, and whether grog would answer
grog-voice turn question.wav --no-play  # a turn from a 16 kHz mono recording
```

If it keeps writing your way of saying its name as something else, add that
spelling to `wakeWord`.

## Privacy

It keeps no recordings and logs no words, only timings and levels. What's
said to grog goes to the brain as the question. With `skin.captions`, it
also goes as it's said, for the skin to show. What anyone else says never
leaves it. With its mic switched off ("stop listening", grog's skin, or
`grog mic off`), nothing is recorded.

## Settings

From `services.grog.voice.settings`, or a JSON file (`grog-voice --config`,
or `$GROG_VOICE_CONFIG`), merged over the defaults in
[`config.py`](grog_voice/config.py).

| Setting | Default | Notes |
| --- | --- | --- |
| `grogUrl` | `"http://127.0.0.1:8484"` | The brain |
| `conversationId` | `"voice"` | The brain's conversation its turns go to |
| `wakeWord` | `null` | Like `"Ok computer"`, or a list of spellings. `null`: grog's name. `false`: none, everything gets an answer |
| `mic`, `speaker` | `null` | PipeWire node names (`wpctl status -n`). `null`: the session's defaults |
| `stt.model` | `null` | A Parakeet TDT model for sherpa-onnx, int8 or fp32. On NixOS: `sttModel`, or `sttPrecision` |
| `stt.threads` | `2` | 3 is slower on grognix's 4 cores. fp32 with 4 is as fast as int8 with 2, given AVX2 |
| `tts.engine` | `"supertonic"` | Or `"piper"`: ~1 GB lighter and quicker to start speaking, but flatter. On NixOS: `ttsEngine` |
| `tts.voice` | `"M1"` | Supertonic: one of its voices (F1 to F5, M1 to M5), or a voice style `.json`. Piper: a voice, `.onnx` with its `.onnx.json`. On NixOS: `ttsVoice` |
| `tts.model` | `null` | Supertonic's directory, as in Supertone/supertonic-3 (`onnx/`, `voice_styles/`). On NixOS: `ttsModel` |
| `tts.language` | `"pt"` | What Supertonic reads, the language grog answers in: `en`, `es`, `fr` and 28 more |
| `tts.steps` | `5` | Supertonic: fewer are faster, and worse (with 3 it drops words) |
| `tts.threads` | `4` | Supertonic: the fastest on grognix's 4 cores, as it doesn't listen while it talks |
| `endSilenceMs` | `600` | Silence that ends what you said |
| `speculateMs` | `250` | A pause this long starts transcribing, in case it's the end. `0`: wait for the end |
| `partialMs` | `300` | How much more you have to say before what you've said so far is transcribed again. `0`: only at pauses, without the second model |
| `maxUtteranceSeconds` | `15` | Longer is cut |

## Development

The tests only need the standard library, as the models load lazily:
`python3 -m unittest discover -s tests`.

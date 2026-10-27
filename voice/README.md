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
   second Parakeet (int8, loaded once the rest is) transcribes what you've
   said so far, to catch its name early.
3. Only what starts with its name gets an answer: "Grog, will it rain
   tomorrow?", or "Hey Grog, ...". The name is matched by how it sounds,
   since speech to text writes it as "Groga" or "Grok" and so on. After its
   name alone, or an answer asking you something, the next thing you say
   doesn't need it. What isn't known to be for grog is only transcribed as
   far as its name could be, its first 3 s: a TV, or people talking, can go
   on for the 15 s an utterance lasts at most, many times an hour.
4. A short tick says it got it, and the brain answers, streamed.
5. [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) speaks the
   answer, a sentence at a time while the rest is still coming, in a voice
   cloned from a recording: deep-calm, a deep, calm Brazilian voice designed
   with Qwen3-TTS (no one's own). Its speech plays as it's made, 80 ms at a
   time, so it gets whole sentences, which it says better (a short first one
   goes with the next). It runs on
   [OpenVINO](https://github.com/openvinotoolkit/openvino), from ONNX graphs
   of its 24-layer Portuguese model made by
   [`tools/export_pocket.py`](tools/export_pocket.py).
   [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) (31
   languages, ~8 times as fast) or
   [Piper](https://github.com/OHF-Voice/piper1-gpl) (lighter, but flatter)
   can speak instead (`tts.engine`). Whichever speaks reads "!" as ".":
   exclamations made grog sound too cheerful.

While grog plays music, the VAD takes the music for speech, so what you
say never ends, and its name gets lost in the lyrics. The brain tells it
when music plays (`GET /v1/voice/commands`). Until it hears its name, it
then cuts what it hears into 4 s windows (`musicUtteranceSeconds`), each
starting 1 s before the last one ends, so a name said across a cut is
whole in one of them. Each window is transcribed and searched for the
name, anywhere in it: a window can start mid-sentence. When it hears its
name, what you say starts with that window, from the name on, and the
brain ducks the music. From there it ends as usual. Its mic should record
from an echo-cancelled node that removes grog's own music (`grog-mic`,
see [body](../body/README.md#echo-cancellation)).

It listens while grog answers too, for its name. Said then, while grog
thinks, searches the web or speaks, its name stops the answer at once,
with the sound saying it listens: alone ("Grog!"), it waits for what you
say next, which needs no name; with more ("Grog, what time is it?"), that's
answered instead. grog hears how much of its answer was said before it was
cut off, so you can ask it to go on. What isn't for grog is let be, and
the answer goes on. Its mic hears grog's own voice too, which the VAD
takes for speech, so while grog's sounds play, only what's louder than
their echo is heard: 6 dB over its loudest, learned as grog speaks
([`echo.py`](grog_voice/echo.py)). With the speaker right next to the
mic, as on a laptop, grog's voice reaches the mic much louder than you,
and nothing is heard over it: you can call it while it thinks or searches,
not while it speaks. With the speaker farther away, or quieter, you can.
Touching its face stops it too. Why not an echo canceller:
[plans/voice.md](../plans/voice.md#interruptions).

A low sound means something went wrong, and the log says what. A rising
one means the brain is there: at start, or back after it restarted (after
an update, say).

The brain can also ask it (`GET /v1/voice/commands`), for someone at grog's
screen: to listen now, without its name (touching its face), to stop
answering, or to switch its mic off. Off, it records nothing at all until
it's switched on again, even after restarts: the brain keeps that.

And to say something though no one asked (`say`): a timer going off. It
plays its alarm sound, louder and longer than the others, then says it,
once it's done answering if it is (a minute at most), one after the
other. Calling grog, touching its face or stopping it cuts it off, and
what was still to be said goes. Its mic off, it still says it.

And which speakers its session has: it reads them with `pw-dump` (PipeWire's
sinks, without its echo canceller's nor HDMI ports with no screen) and
tells the brain (`POST /v1/voice/speakers`), for its screen to pick from
([`speakers.py`](grog_voice/speakers.py)). It speaks on the one picked for
its voice (the brain keeps it), or else on its `speaker` setting, or the
default; a speaker picked for the music becomes the session's default (as
`wpctl set-default` does: WirePlumber keeps it).

On a Core Ultra 7 155H, Pocket TTS takes 0.5 s per second of speech, and
its first sound comes 0.1 to 0.15 s after the answer's first sentence, as
long as the CPU stays cool enough not to throttle: too hot, it falls
behind on long answers, and what it says then waits for a second of
speech made ahead whenever it runs out, rather than crackling. On grognix,
a Celeron N5095, grog speaks with Supertonic, which takes ~0.5 s per second
of speech there (Piper: 0.2), so it starts answering 1 to 3 s after the
LLM's first words, which take 2 to 3 s, more when it searches the web.
Pocket TTS wasn't measured on such slow CPUs, where it may not keep up.
What was tried for its voice, and why this one:
[plans/voice.md](../plans/voice.md).

## Running it

On NixOS, `services.grog.voice.enable` runs it as a user service of
`services.grog.user`, whose session has the mic and the speaker (PipeWire).
Log that user in at boot, or set `users.users.<name>.linger = true`.

Nix fetches the models: Parakeet, and Pocket TTS's 24-layer Portuguese
model with its voice deep-calm (~1.3 GB, ~3 GB of RAM), under CC-BY-4.0
(its README.md says where it comes from), from grog's GitHub release
`pocket-tts-pt-24l-v1`. The release `pocket-tts-pt-v1` has the 6-layer
one: 3 times as fast, a third of the RAM, less natural. `ttsEngine =
"supertonic"` speaks with Supertonic 3 and its voice M1 (or F1 to F5, M2 to
M5, in `ttsVoice`), under OpenRAIL-M, which forbids harmful uses (its
LICENSE comes with it): grognix does. `ttsEngine = "piper"` speaks with
Piper's pt_BR `faber` voice, or the one in `ttsVoice`. Pick other models
with `services.grog.voice.sttModel` and `ttsModel`. `sttPrecision = "fp32"`
runs Parakeet in fp32 rather than int8: it hears more, from afar most of
all, for ~1.3 GB more RAM per Parakeet.

`journalctl --user -u grog-voice` shows how long each turn took.

To see how it hears you, or try a turn without a mic:

```sh
grog-voice listen                       # what it hears, and whether grog would answer
grog-voice turn question.wav --no-play  # a turn from a 16 kHz mono recording
```

If it keeps writing your way of saying its name as something else, add that
spelling to `wakeWord`.

## A ReSpeaker's ring

grog works with any mic. With a ReSpeaker XVF3800 plugged in (Seeed's USB
4-mic array), its ring also shows what grog-voice does
([`ring.py`](grog_voice/ring.py)): off at rest, pointing at whoever talks
to grog, or whoever it waits for, spinning while grog thinks, lit green
while it speaks, breathing amber while it says a timer going off, in the
skin's colors; red a moment when something went wrong, dim red while
grog's mic is off. It also tells the brain where whoever talks to grog
is, from where it hears speech (its direction of arrival), for grog's face
to look at them: straight ahead is where people talked to grog from
lately, or `ring.front`. Its mute button switches
grog's mic off and on, through grog, as asking would, and grog's mic off
mutes its mics in hardware, its red LED lit. Its own mute is off while grog-voice
runs, and on again once it stops. grog-voice talks to it over USB, so its
user needs the device: `services.grog.voice` gives it, on NixOS. Spinning
takes its firmware 2.0.7 or later (it breathes instead), the button 2.1.1:
how to update it is in [plans/voice.md](../plans/voice.md#the-respeaker-xvf3800).

## Another voice

Pocket TTS clones a voice from a few seconds of clean speech. Make one
where torch and pocket_tts run (`pip install pocket-tts==4.0.0 onnx`), then
point `ttsVoice` at it:

```sh
python tools/export_pocket.py voice tools/pocket_portuguese_24l.yaml recording.wav mine.npz
```

A voice only works with the model it was made for: for the 6-layer one,
`tools/pocket_portuguese.yaml`. The same tool makes the model's files from
its weights (`model tools/pocket_portuguese_24l.yaml pt DIR`), as for the
release. Only clone the voice of someone who agrees to it.

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
| `mic`, `speaker` | `null` | PipeWire node names (`wpctl status -n`). `null`: the session's defaults. A speaker picked on grog's screen comes before `speaker` |
| `stt.model` | `null` | A Parakeet TDT model for sherpa-onnx, int8 or fp32. On NixOS: `sttModel`, or `sttPrecision` |
| `stt.threads` | `2` | 3 is slower on grognix's 4 cores. fp32 with 4 is as fast as int8 with 2, given AVX2 |
| `stt.partialModel` | `null` | The second Parakeet's, for what you've said so far (`partialMs`): an int8 one where `stt.model` is fp32 saves ~1.3 GB, as what's for grog is transcribed again at pauses and at its end. `null`: `stt.model`. On NixOS: `partialSttModel`, int8 |
| `stt.partialThreads` | `2` | The second Parakeet's threads: it runs while grog speaks too |
| `tts.engine` | `"pocket"` | Or `"supertonic"`: 31 languages, ~8 times as fast, a sentence at a time. Or `"piper"`: ~1 GB lighter than Supertonic and quicker to start speaking, but flatter. On NixOS: `ttsEngine` |
| `tts.voice` | `null` | Pocket: one of its model's voices (`voices/`), or a voice file (`.npz`). Supertonic: one of its voices (F1 to F5, M1 to M5), or a voice style `.json`. Piper: a voice, `.onnx` with its `.onnx.json`. `null`: `deep-calm` with Pocket, `M1` with Supertonic. On NixOS: `ttsVoice` |
| `tts.speed` | `null` | How fast it speaks, from 0.5 to 2: 1 is the voice's own pace, 1.2 a fifth faster. `null`: 1.4 with Supertonic (M1 says ~140 words a minute at 1, ~200 at 1.4), 1 with Piper. Pocket only takes `null`: its voices speak at their recording's pace |
| `tts.model` | `null` | Pocket's directory, as `tools/export_pocket.py` makes it. Supertonic's, as in Supertone/supertonic-3 (`onnx/`, `voice_styles/`). On NixOS: `ttsModel` |
| `tts.language` | `"pt"` | What Supertonic reads, the language grog answers in: `en`, `es`, `fr` and 28 more. Pocket's model reads one, Portuguese |
| `tts.steps` | `5` | Supertonic: fewer are faster, and worse (with 3 it drops words) |
| `tts.threads` | `4` | Pocket and Supertonic: Supertonic's fastest on grognix's 4 cores, as nothing is transcribed while it talks, unless heard over it. On a Core Ultra 7 155H, 6, its performance cores: Supertonic's fastest there, and Pocket's faster than 2 or 4 |
| `endSilenceMs` | `600` | Silence that ends what you said |
| `speculateMs` | `250` | A pause this long starts transcribing, in case it's the end. `0`: wait for the end |
| `partialMs` | `300` | How much more you have to say before what you've said so far is transcribed again. `0`: only at pauses, without the second model |
| `maxUtteranceSeconds` | `15` | Longer is cut |
| `musicUtteranceSeconds` | `4` | While grog plays music, until it hears its name: how long each window it looks for the name in is. They overlap by 1 s. From 2 to `maxUtteranceSeconds` |
| `ring.front` | `null` | A ReSpeaker XVF3800's angle for whoever faces grog's screen, from 0 to 359: seen from above, clockwise from its USB port (180 with the port toward the screen's back). grog's face looks at who talks to it as they're to the side of it. `null`: where people talked to grog from lately |

## Development

The tests only need the standard library, as the models load lazily:
`python3 -m unittest discover -s tests`.

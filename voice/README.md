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
5. [Piper](https://github.com/OHF-Voice/piper1-gpl) speaks the answer a
   sentence at a time, while the rest is still coming.

It doesn't listen while it talks. A low sound means something went wrong,
and the log says what.

On grognix, a Celeron N5095, it starts answering about 3 s after you stop
talking, 5 to 8 s when it searches the web. Most of that is the LLM.

## Running it

On NixOS, `services.grog.voice.enable` runs it as a user service of
`services.grog.user`, whose session has the mic and the speaker (PipeWire).
Log that user in at boot, or set `users.users.<name>.linger = true`.

Nix fetches the models: Parakeet and Piper's pt_BR `faber` voice. Pick others
with `services.grog.voice.sttModel` and `ttsVoice`.

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
leaves it.

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
| `stt.model` | `null` | A Parakeet TDT model for sherpa-onnx. On NixOS: `sttModel` |
| `stt.threads` | `2` | 3 is slower on grognix's 4 cores |
| `tts.voice` | `null` | A Piper voice, `.onnx` with its `.onnx.json`. On NixOS: `ttsVoice` |
| `endSilenceMs` | `600` | Silence that ends what you said |
| `speculateMs` | `250` | A pause this long starts transcribing, in case it's the end. `0`: wait for the end |
| `partialMs` | `300` | How much more you have to say before what you've said so far is transcribed again. `0`: only at pauses, without the second model |
| `maxUtteranceSeconds` | `15` | Longer is cut |

## Development

The tests only need the standard library, as the models load lazily:
`python3 -m unittest discover -s tests`.

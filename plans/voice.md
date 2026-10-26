# grog's voice: what we learned

Context for whoever improves grog's voice next, or trains one of its own:
what was tried in October 2026, what it measured, what the user liked, and
why grog speaks the way it does. The numbers come from grognix itself unless
said otherwise.

## Where it stands

- It runs on an asus oled now, a Core Ultra 7 155H, rather than grognix's
  Celeron: see "Round 3" below. The rest of this file is about grognix
  unless said otherwise.
- grog speaks with **Pocket TTS** (Kyutai, CC-BY-4.0), its 24-layer
  Portuguese model, in the voice **deep-calm**: a voice designed with
  Qwen3-TTS, cloned by Pocket TTS. The user picked the voice, then, having
  heard it live, the 24 layers (see "Live, and an A/B" below). It runs on
  **OpenVINO's CPU plugin**, from ONNX graphs grog makes of the model
  (`voice/tools/export_pocket.py`), its speech playing 80 ms at a time as
  it's made, without torch: see "Pocket TTS on OpenVINO" below. On the
  station, 6 threads (`tts.threads`).
- The station spoke for a while in a carioca friend's voice, cloned with his
  OK from his recordings, then went back to deep-calm, the user not happy
  with it: see "Accents" below. Its answers kept their carioca words (the
  `instructions` setting, in its own config).
- grognix speaks with **Supertonic 3** (Supertone, May 2026), voice **M1**,
  in Portuguese (`pt`), on OpenVINO too: the official fp32 ONNX graphs from
  `Supertone/supertonic-3`, pinned at revision
  `3cadd1ee6394adea1bd021217a0e650ede09a323`, 4 threads, 5 steps, at speed
  1.4 (`tts.speed`, see "Speed" below). Pocket TTS wasn't measured there
  (`body/grognix/default.nix` picks Supertonic, `tts.engine`).
- Piper stays available (`tts.engine = "piper"`, `tts.voice` = a `.onnx`).
- It hears with Parakeet TDT 0.6B v3 in int8, or fp32 with
  `services.grog.voice.sttPrecision` (see "The ears" below).
- grog can ask it to listen now (someone touched its face on the skin), to
  stop answering (pw-cat killed, the connection to grog shut down, which
  ends grog's turn), or to switch its mic off (pw-cat for the mic stopped:
  nothing recorded), over `GET /v1/voice/commands`
  (`voice/grog_voice/commands.py`). Until then grog-voice could only tell
  grog what it was doing. grog also tells it when its music plays, to
  listen for its name in short windows over it (see "Music" below).
- It listens while grog answers: its name then cuts the answer off, and
  what follows it is answered instead. While grog's sounds play, only what's
  louder than their echo is heard, which with the station's speaker at full
  volume, beside its mics, is nothing: see "Interruptions" below.
- Since October 26, 2026 the station hears through a ReSpeaker XVF3800, a
  USB mic array with an echo canceller of its own, whose ring shows what
  grog-voice does and whose button switches grog's mic, and speaks through
  its screen's speaker (HDMI) until one is plugged into the ReSpeaker: see
  "The ReSpeaker XVF3800" below.
- Code: `voice/grog_voice/pocket.py` and `voice/grog_voice/supertonic.py`
  (text handling, pure), the `Pocket`, `Supertonic` and `Piper` classes in
  `voice/grog_voice/speech.py`, the `tts.*` settings in
  `voice/grog_voice/config.py`, the models in
  `body/packages/voice-models.nix`, the NixOS options (`ttsEngine`,
  `ttsModel`, `ttsVoice`) in `body/modules/grog.nix`, and Pocket TTS's
  export in `voice/tools/` (`export_pocket.py`, `pocket_portuguese.yaml`).
- What Supertonic costs over Piper on grognix: answers start 1 to 3 s
  later, ~1 GB more RAM (the text to speech alone peaked at 1.19 GB on the
  laptop), ~400 MB of model files, ~235 MB more in grog-voice's closure
  (openvino 135 MB, opencv 76 MB, small libraries). Pocket TTS's 24-layer
  model takes ~2.9 GB of RAM at its peak and ~1.3 GB of model files (its
  6-layer one: as much RAM as Supertonic, ~405 MB of files), and
  sentencepiece (14 MB more in the closure).
- Supertonic speaks ~8 dB lower than Piper (speech RMS -22 to -24 dBFS,
  peaks -5 to -8 dBFS, against Piper's -15 and 0): each piece is brought up
  to peak at -1 dBFS, by at most +12 dB, like Piper's normalized output, as
  the earcons (`sounds.py`) and the speaker's volume were set for Piper.
  Pocket TTS streams, so a voice has one gain (`gain_db` in its file, +2.9
  dB for deep-calm), set when it's made: its speech to ~-18 dBFS RMS,
  peaking at -1 dBFS at most.

## Constraints

- **The machine**: grognix is an Intel Celeron N5095 (Jasper Lake, 4 Tremont
  cores, 2.0 GHz, bursts to 2.9 GHz). SSE4.2 only: no AVX, AVX2, AVX-512 or
  VNNI, so int8 kernels bring little and most runtimes fall back to their
  slowest paths. 7.7 GB of RAM, ~4.3 GB free with everything running
  (grog-voice alone used ~1.3 GB, plus ~0.8 GB for its partials worker). Its
  iGPU is an Intel UHD Graphics, Gen11, 16 EUs at 750 MHz (PCI 8086:4E55,
  i915).
- **No cloud, nothing paid** (the user's call): the voice runs on grognix.
- **Dependencies from nixpkgs only**; models are fetched by Nix (pinned URLs
  and hashes), never at runtime.
- **Brazilian Portuguese** first: grog answers in the user's language, which
  is pt-BR here.
- **Privacy**: grog-voice never logs or keeps what it hears or says.
- **Speed that keeps up**: grog-voice synthesizes an answer a chunk at a time
  while the rest streams in (`chunks.py`: sentences, and a first chunk that
  may end at a clause). Synthesis must stay well under real time, or speech
  stops between chunks, and the first chunk's synthesis is added to the wait
  before grog speaks (the LLM's first words already take ~2 to 3 s).

## How we measured

- **RTF**: seconds of computing per second of speech. Piper does 0.19 on
  grognix. Above ~0.7, long answers start to stall; above 1, they always do.
- **On grognix, in bursts**: three answers of 2, 1 and 3 sentences, with 8 s
  of silence between answers, like real use, never back to back:

  > Amanhã deve fazer 28 graus em São Paulo, com chance de chuva no fim da
  > tarde. Leve um guarda-chuva se for sair depois das cinco. / Claro, já
  > aumentei o volume. / Encontrei três farmácias abertas perto de você. A
  > mais próxima fica na Rua Augusta, a uns dez minutos a pé. Quer que eu te
  > lembre de passar lá amanhã de manhã?

  We noted each sentence's time, the first sentence of each answer (the
  wait it adds), the overall RTF, and the CPU clock while synthesizing.
- **Watch for noise**: grog-voice keeps listening during a benchmark, and
  people talking in the room make it transcribe (165% CPU), which shows up as
  sentences suddenly twice as slow (`top`, `journalctl --user -u
  grog-voice`). When it speaks for real, it only transcribes what's louder
  than its voice (see "Interruptions").
- **Without installing anything there**: scripts ran with grog-voice's own
  Python (the site directories listed in its store path's
  `bin/.grog-voice-wrapped`); other tools were built on the laptop from this
  flake's nixpkgs and sent with `nix copy --no-check-sigs --to
  ssh-ng://grog@grognix.local` (grog is a trusted user). Clean up afterwards.
- **Checking the audio**: Parakeet (`stt.model`) transcribed every sample,
  which catches skipped words, mangled numbers and silence. Then the user
  listens: the transcript can't tell a voice is unpleasant.
- **The same sentence for every sample**: "Oi, eu sou o Grog. Posso ver a
  previsão do tempo, tocar uma música ou responder suas perguntas. Amanhã
  deve fazer 28 graus em São Paulo, com chance de chuva no fim da tarde."

## What the user heard

Two rounds of samples, all with the sentence above.

1. **Disliked all of them**: Piper `faber` (the default until then), `cadu`,
   `jeff`, and OpenVoiceOS's pt-BR `dii` and `miro` (trained on synthetic
   data); Kokoro's `pf_dora`, `pm_alex` and `pm_santa`; Supertonic's ten
   voices, most likely version 2, two of them at 2 steps.
2. **Picked Supertonic 3's M1**, out of: Supertonic 3's ten voices (F1 to F5,
   M1 to M5); Kyutai's Pocket TTS in Portuguese (`rafael`, its Portuguese
   voice, and `lola`, `giovanni`, `estelle`, `juergen`, on the 6-layer and
   24-layer models); Microsoft's neural voices (Francisca, Antonio, Thalita),
   as a cloud reference. Then the user ruled out cloud and paid options.

## Speed on grognix

Piper `faber` medium (piper1-gpl, ONNX Runtime): RTF 0.19, 0.3 to 0.9 s for
an answer's first sentence, loaded in 2.3 s.

Supertonic 3 through sherpa-onnx 1.12.38 (ONNX Runtime 1.24.4), with
sherpa's int8 package `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` unless
said otherwise:

| Setup | RTF | First sentence |
| --- | --- | --- |
| F1, 2 threads, 5 steps | 1.18 | 3.2 to 7.8 s |
| M1, 2 threads, 5 steps | 1.09 | 3.1 to 7.3 s |
| M1, 2 threads, 4 steps | 1.03 | 2.6 to 6.1 s |
| M1, 3 threads, 5 steps | 1.29 | |
| M1, 4 threads, 5 steps | 1.74 | |
| M1, int8 graphs with the fp32 vocoder | 1.14 | |
| M1, the fp32 vector estimator with the int8 vocoder | 1.26 | |
| F1, all fp32, 2 threads / 4 threads | 1.34 / 1.98 | |
| F1, 3 steps | 0.74 | but it drops and mangles words |
| F1, 2 steps | 0.62 | but it's unintelligible |
| Supertonic 2 (int8), F1, 2 threads, 5 steps | 0.47 | 1.2 to 2.7 s |

With ONNX Runtime, more threads made it slower. With 2 threads the clock
stayed near 2.7 GHz.

Supertonic 3 through **OpenVINO 2026.1.2's CPU plugin**, the official fp32
graphs, M1 (the setup grog uses is 4 threads, 5 steps):

| Setup | RTF | First sentence |
| --- | --- | --- |
| 2 threads, 5 steps | 0.88, 0.90 | 2.2 to 5.7 s |
| 3 threads, 5 steps | 0.94 | |
| 4 threads, 5 steps | 0.73, 0.61, 0.65 | 1.3 to 4.1 s |
| 2 threads, 4 steps | 0.71 | |
| 4 threads, 4 steps | 0.69 | |

- 4 threads were the fastest here, although 3 were slower than 2 (a single
  run: it may have been noise). Most sentences took 0.47 to 0.54 s per second
  of speech with 4 threads; the slower ones coincided with grog-voice
  transcribing the room.
- OpenVINO is about twice as fast as ONNX Runtime on this CPU, for the same
  model.
- By graph, with 4 threads and 5 steps, in seconds per second of speech: the
  vector estimator 0.49 to 0.52, the vocoder 0.11, the text encoder 0.01,
  the duration predictor nothing.
- Memory: compiled straight from the files (`core.compile_model(path)`), the
  vector estimator and the vocoder take 0.56 GB, and 0.86 GB after a 7 s
  sentence. Through `read_model`, with the small graphs, the process reached
  1.4 to 1.6 GB. sherpa's int8 graphs run on OpenVINO at the same speed, in
  0.79 GB. Compiling takes 1.7 to 2.5 s.

For scale, on the laptop the same code is 3 to 6 times faster (an Intel Core
Ultra 7 155H, with AVX2): Piper 0.08, Supertonic 3 with sherpa int8 0.12 to
0.39, Pocket TTS's 6-layer Portuguese model in PyTorch 0.19 to 0.38, its
24-layer one 0.49 to 0.89.

## The iGPU: a dead end, for now

OpenVINO's GPU plugin sees grognix's iGPU with nixpkgs'
`intel-compute-runtime-legacy1` (24.35, the OpenCL runtime for Gen8 to Gen11;
`intel-compute-runtime` is for Gen12 and newer): "Intel(R) UHD Graphics
(iGPU)", FP16 capable, 1.88 GiB per allocation. For a test, pointing
`OCL_ICD_VENDORS` at the runtime's `etc/OpenCL/vendors` was enough; for good
it would go in `hardware.graphics.extraPackages`. `/dev/dri/renderD128` is
open to all users there.

The iGPU looked fast: with Supertonic's graphs as they are, 0.23 s per step
of the vector estimator and 0.29 s of vocoder for 7 s of speech, after a
first run of 4 to 6 s (kernels compiling). That would be RTF ~0.2. But:

- Fully dynamic shapes don't compile ("get_tensor() is called for dynamic
  shape without upper bound"). With bounded ones (1 to 512), the duration
  predictor and the text encoder fail ("out of GPU resources"): fine, they're
  cheap enough on the CPU.
- Once a graph has run with some size, running it with a smaller one fails
  (`clEnqueueMapBuffer`, -59 `CL_INVALID_OPERATION`), and every run after
  that fails too, whatever the size.
- Static shapes don't compile at all (`clBuildProgram` fails).
- One bounded model per size bucket (5 buckets) took ~2 GB of RAM, then
  failed with `CL_EXEC_STATUS_ERROR_FOR_EVENTS_IN_WAIT_LIST` and
  `CL_OUT_OF_RESOURCES`.
- A single bounded model always given the same size (text padded to 320
  symbols, latent to 128 frames, about 9 s of speech, with masks) runs every
  time, ~1.9 s per sentence (RTF 0.42 overall). But it outputs a flat line,
  no speech at all, in f16 and in f32, and with the vocoder alone on the iGPU
  too. The same padding on the CPU gives the right audio: the iGPU computes
  wrong results.

Worth another look with a newer OpenVINO or compute runtime, or another
iGPU. Gen12 and newer use the maintained runtime.

## How Supertonic works

From Supertone's own Python example (`py/helper.py` in
supertone-inc/supertonic, MIT), which grog follows:

- **Files** (`Supertone/supertonic-3` on Hugging Face):
  - `onnx/duration_predictor.onnx` (3.7 MB), `onnx/text_encoder.onnx` (36 MB);
  - `onnx/vector_estimator.onnx` (256 MB in fp32, 78 MB in sherpa's int8 package);
  - `onnx/vocoder.onnx` (101 MB, int8 26 MB);
  - `onnx/tts.json` (its configuration);
  - `onnx/unicode_indexer.json` (a list indexed by code point, -1 for unknown characters);
  - `voice_styles/F1.json` to `M5.json`: a voice is just a style, `style_ttl` [1, 50, 256] and `style_dp` [1, 8, 16].
- **Text**: Unicode NFKD, some cleanup, a final period if there's no closing
  punctuation, then `<pt>…</pt>` around it; each character becomes its
  indexer id. 31 languages: en, ko, ja, ar, bg, cs, da, de, el, es, et, fi,
  fr, hi, hr, hu, id, it, lt, lv, nl, pl, pt, ro, ru, sk, sl, sv, tr, uk, vi.
  It reads digits by itself ("28 graus" came out right). Chunks of at most
  300 characters (120 in Korean and Japanese).
- **Synthesis**:
  1. The duration predictor (text ids, `style_dp`, text mask) gives the
     length in seconds, divided by `speed`.
  2. Latent frames = ceil(seconds × 44100 / 3072) (`base_chunk_size` 512 ×
     `chunk_compress_factor` 6), of 144 channels (`latent_dim` 24 × 6).
  3. The text encoder (text ids, `style_ttl`, mask) gives `text_emb`.
  4. Gaussian noise of shape [1, 144, frames] goes through the vector estimator `steps`
     times (`current_step`, `total_step`, both masks).
  5. The vocoder turns the latent into 44.1 kHz audio.
- **Defaults**: 5 steps, both in sherpa and in the official SDK. Speed is
  1.0 in sherpa (the samples the user picked) and 1.05 in the SDK. At 1.0
  it speaks slowly: the first test sentence lasts 6.8 s, against Piper's
  4.7 s.
- **Speed**: grog divides the predicted duration by `tts.speed` (Piper's
  `length_scale` too). Once grog ran on a faster machine, the user noticed
  it was still reading what came before a tool call when the model had
  long answered: answers stream all the way, but the model is done in 2 to
  7 s, and speaking took 7 to 29 s after the question. M1 on the sample
  sentence: 142 words a minute at 1.0, 149 at 1.05, 156 at 1.1, 163 at
  1.15, 170 at 1.2, 184 at 1.3, 198 at 1.4; Parakeet heard every word up to
  1.4 (one slurred "amanhã" at 1.2). The user liked 1.4, Supertonic's
  default since (Piper's voices keep their own pace, 1: faber already says
  that sentence in 4.7 s to M1's 6.8). Fewer frames, so synthesis is that
  much faster too.
- **Masks make padding safe** (the SDK pads batches with them), which we
  checked on the CPU with padded sizes.
- **sherpa-onnx's `voice.bin`** holds the ten styles in the order F1 to F5,
  then M1 to M5: M1 is its speaker 5.
- **No training code is open.** Custom voices come from Supertone's Voice
  Builder, which is paid, and makes style files for versions 2 and 3.
  Version 3 also takes tags such as `<laugh>`, `<breath>` and `<sigh>`.
- **Licenses**: the model is OpenRAIL-M (use restrictions, no harmful uses),
  the example code is MIT.

## Everything else we looked at

- **Pocket TTS** (Kyutai, 100M parameters, CC-BY-4.0):
  - Portuguese since May 2026, a distilled 6-layer model and a 24-layer one.
  - The most natural prosody of the local options, and it clones a voice from a recording.
  - Cloning needs the gated `kyutai/pocket-tts` repo (an automatic approval, but a login). `kyutai/pocket-tts-without-voice-cloning` has ready-made voice states per language, `rafael` being the Portuguese voice.
  - It reads digits badly ("28" came out as gibberish, so numbers need spelling out), and the 6-layer model skips words now and then.
  - Its training code is open since August 2026.
  - sherpa-onnx runs it, from a reference recording, but only exports the English model. There are unofficial ONNX exports for Portuguese (`Alias-Alias/pocket-3-3-0-tts-onnx`, int8).
  - Too slow on grognix in PyTorch, by the laptop numbers; the ONNX path the station runs (round 3) wasn't measured there.
- **Kokoro** (82M, Apache-2.0):
  - Three pt-BR voices, with a more neutral, pan-Lusophone accent.
  - A community note says its int8 exports give NaN in Portuguese, so use fp32.
  - Never measured on grognix; the user didn't like its voices.
- **Piper**:
  - Only four official pt_BR voices: cadu, edresson (low), faber, jeff.
  - OpenVoiceOS trained `dii` and `miro` on synthetic data (the latter is CC-BY-NC-SA per a mirror's card).
  - Community training checkpoints were never exported or heard: `freds0/piper-ptbr-brspeech-medium`, `MatheusMarquesEiras/piper-gpl-portugues`.
- **MOSS-TTS-Nano** (OpenMOSS, 0.1B, Apache-2.0):
  - 20 languages including Portuguese, voice cloning, 48 kHz stereo, official ONNX export (670 MB in fp32).
  - Autoregressive, likely too slow on grognix; not tried.
- **GPU-class open models with pt-BR**, too heavy for grognix:
  - Chatterbox Multilingual (Resemble AI, MIT, 500M; it has a pt-BR single-language model);
  - Qwen3-TTS (0.6B and 1.7B, Apache-2.0);
  - OmniVoice (k2-fsa, on Qwen3-0.6B, CC-BY-NC);
  - VoxCPM2, F5-TTS pt-BR fine-tunes, XTTS-v2 (non-commercial).
- **Cloud**, ruled out by the user:
  - Microsoft's neural voices (Azure's free tier: 500 thousand characters a month, with an account and a card; the edge-tts library uses Edge's read-aloud service unofficially);
  - OpenAI's gpt-4o-mini-tts (about US$ 0.015 a minute, an API key, which the ChatGPT subscription doesn't cover);
  - ElevenLabs, Google.

## The ears: mics and speech to text

Tried in October 2026 on a laptop running grog (Core Ultra 7 155H, 16 GB).
Every take was recorded by two mics at once, then run offline through
grog-voice's own pipeline (VAD, endpointing, Parakeet, the wake word), so
both heard the same words. Counted: utterances starting with "Grog" that
grog would answer. The scripts ran with grog-voice's own Python, as above.

- **A USB speakerphone with its own noise suppression**: 1 of 12, missing
  "Grog" even at 40 cm in a quiet room. 83 to 89% of what it gives is digital
  silence, each word starts 10 to 20 dB down for its first ~50 ms, and pieces
  in the middle go missing: Parakeet heard "Yeah" and "Well". Its level was
  fine (peaks at -15 to -25 dBFS), and nothing brings back what it cut. It
  did cancel its own echo.
- **The laptop's two built-in mics** (digital, mixed to mono by PipeWire):
  12 of 15, all of them at 1 m; the misses were at 3 m, mostly "Grog." said
  alone. Speech peaked at -16 to -28 dBFS with the mic at 100%, 26 to 32 dB
  over the room's noise.
- **Lid closed**: the same at 1 m. At 3 m, ~2 dB quieter, 2 to 3 dB less
  above 2 kHz, and one of two utterances missed there, none with it open.
- **Mic volume**: 68% (-10 dB) against 100%, simulated by adding the gain to
  the recordings: 8 of 10 against 9 of 10 in int8, 9 of 10 both in fp32.
  The speech to text gain makes up for it; the VAD caught quiet words a
  little better louder.
- **Parakeet fp32 against int8**
  (`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3`): 14 of 15 against 12
  of 15 on the laptop's mics, and 6 of 12 against 1 of 12 through the
  speakerphone. In bursts on the Core Ultra 7, for 1.5 to 3.5 s utterances:
  int8 0.24 s median with 2 threads, 0.14 s with 4; fp32 0.56 s with 2, 0.20
  s with 4. Memory: +1.2 GB per Parakeet in int8, +2.5 GB in fp32, and
  grog-voice runs two with partials. Hence `sttPrecision`, int8 by default.
- **Echo cancellation**: PipeWire's echo-cancel module (WebRTC, the
  canceller alone, no noise suppression or gain control), with the speaker
  right next to the mic. grog's echo went from -13.5 to -25 dBFS on
  average, but it clipped the mic at 0 dBFS to begin with, and when someone
  talked over grog, their words went too. Not worth it there, as tried
  again for interruptions (see "Interruptions").
- **The listening earcon over speech**: it plays while someone talks on
  after grog's name. Added to the recordings peaking at -1 dBFS (as the
  speaker beside the mic gave it), the question was lost in 2 of 15
  utterances and the name in 1 more; at -20 dBFS, no worse than without.
  Hence: keep the speaker away from the mic.
- **How Parakeet wrote "Grog"**: matched as "Grog", "Grogue", "Drogue",
  "Drog" (with more after), "Groggy", "Grogu"; missed as "Droga", "Garage",
  "Graguinho", "Grogi", "Krogi", "Group", "G". Short and alone, from 3 m, is
  the hardest.

## Round 3: the asus oled (late October 2026)

grog moved from grognix to the user's old laptop, an ASUS Zenbook 14
UX3405MA (Core Ultra 7 155H: 6 performance cores with two threads each, 10
efficient ones, AVX2 and AVX-VNNI; 16 GB, ~10 GB free with grog running;
an Arc iGPU, Xe-LPG; an NPU without its driver, so OpenVINO sees CPU and
GPU only). It runs grog as a boot entry of its own, a NixOS specialisation
in the user's dotfiles, and uses its built-in mics and speaker. Samples
were made on another laptop (Core Ultra 9 386H, 32 GB) in a throwaway
Docker lab with CPU PyTorch; speed numbers are from the 155H unless said
otherwise.

### Its CPU ran without turbo

auto-cpufreq's `turbo = "auto"` (the dotfiles', on AC) only turns turbo on
under sustained load, too late for grog's bursts: they ran at the base
clock, 1.4 GHz (TLP was on too, fighting it). Turned on for a test
(`intel_pstate/no_turbo`, up to 4.5 GHz), with grog-voice listening on, in
bursts:

| | Turbo off | Turbo on |
| --- | --- | --- |
| Parakeet int8, 2 threads, 2.6 to 4.2 s commands (median) | 0.33 s | 0.14 s |
| Parakeet int8, 4 threads | 0.27 s | 0.10 s |
| Supertonic M1, 4 threads: RTF, first sentences | 0.155, 0.42 to 1.04 s | 0.074, 0.22 to 0.48 s |
| Supertonic M1, 6 threads | 0.06 to 0.17, 0.19 to 0.73 s | 0.056, 0.16 to 0.36 s |
| Supertonic M1, 6 threads, 10 steps | 0.18 to 0.25 | 0.100, 0.29 to 0.64 s |

- Without turbo: 8 threads 0.109, 12 threads 0.095, and OpenVINO's own
  choice (14 threads, no `INFERENCE_NUM_THREADS`) 0.14 to 0.21, with the
  slowest first sentences: 6 threads it is.
- Its package went from 47 °C to 72 °C over minutes of back-to-back
  benchmarks with turbo; real use is bursts.
- The station's config (the dotfiles) asks auto-cpufreq for `turbo =
  "always"` on AC, without TLP, with Parakeet in fp32 on 4 threads and
  Supertonic on 6 (`sttPrecision`, `stt.threads`, `tts.threads`). Updated
  that way, in bursts: Supertonic at speed 1.4, RTF 0.062, first sentences
  in 0.14 to 0.28 s; Parakeet in fp32, 0.19 s median (0.16 to 0.25) for
  the same commands, against 0.33 s in int8 on 2 threads before.

### The Arc iGPU

OpenVINO's GPU plugin sees "Intel(R) Arc(TM) Graphics" through
intel-compute-runtime (Gen12 and newer). Unlike grognix's Gen11, its
results are right (Parakeet transcribes them perfectly) and running a
smaller size after a bigger one works. But for Supertonic it's slower than
the CPU:

- Fully dynamic shapes still don't compile; bounded ones (1 to 512) do, at
  RTF 0.29 for the sample sentence and 0.63 in bursts (f16): each new size
  seems to cost a compile.
- Static shapes (text 256, latent 256) in f32: 0.28 s per step of the
  vector estimator for 14 s of speech, RTF 0.105. In f16 they don't build
  (`clBuildProgram`).

### Voices tried

All with the sample sentence, checked by Parakeet (word error rate in the
lab's `transcripts.json`), and by pitch (librosa's pyin: median F0, male
voices ~85 to 155 Hz).

- **Qwen3-TTS** (Alibaba, Apache-2.0, 0.6B and 1.7B, 10 languages):
  - 1.7B VoiceDesign makes a voice from a description, in English (`Male,
    about 35 years old, native Brazilian Portuguese speaker from São
    Paulo. Warm baritone...`). Six designs, each saying a reference
    sentence and the sample one. It's stochastic: the same description
    gave a different voice for each text, and the one written in
    Portuguese came out high (220 to 244 Hz), likely a woman's. "Deep",
    "soft" and "warm" came out male (100 to 155 Hz).
  - 0.6B Base cloned each design's reference faithfully, digits read right
    ("28 graus"), and so did the designs themselves.
  - 0.6B CustomVoice's presets (Aiden, Ryan, Uncle Fu... none Brazilian)
    speak Portuguese; one, Dylan, gave nothing Parakeet could make out.
  - On CPU PyTorch (386H): RTF 5 to 7.5 for the 1.7B, 3.4 to 7.3 for the
    0.6B: the code predictor's 15 small steps per 80 ms frame weigh as much
    as the model. Not for live use on the station; a voice designer, and a
    teacher.
- **Chatterbox Multilingual V3, Brazilian Portuguese model** (Resemble AI,
  MIT, 0.5B): cloned all six designs without a single wrong word, but at
  RTF 5.4 to 6.9 on the 386H's CPU. It needs a GPU.
- **MOSS-TTS-Nano** (Apache-2.0, 0.1B, 48 kHz): unreliable in Portuguese,
  up to 65% wrong words, and "Grog" came out "Rock", "Gauge", "Urogue".
- **Pocket TTS** (Kyutai, CC-BY-4.0, 100M):
  - Cloning needs the gated `kyutai/pocket-tts`; the user okayed
    `lunahr/pocket-tts-ungated`, a CC-BY copy of an older release
    (revision `d03cd73415a8d46d8eb115c7b524aebb0a729f4a`).
  - Its weights only read text with its own SentencePiece tokenizer
    (`tokenizer.model`): with the current release's tokenizer, every clone
    said the same gibberish.
  - With it, the 24-layer and 6-layer models cloned the six designs with 0
    to 3% wrong words (numbers spelled out: it reads digits badly).
  - Its predefined Portuguese voices are mostly foreign speakers; only
    `rafael` is Brazilian.
  - It streams: the first audio comes in 0.12 to 0.26 s, where Supertonic
    synthesizes a whole sentence first. On the 386H, in bursts, `pocket_tts`
    sets 1 torch thread: 6 layers RTF 0.37 (0.24 with 2 threads, no better
    with more); 24 layers 1.18 (0.70 with 2, 0.53 with 4).
  - Someone did for a woman's voice what grog would need for one of its
    own (`marcosremar2/pocket-tts-erinome-pt`, CC-BY-4.0): 7.3 h of a
    Brazilian voice generated with Qwen3-TTS, the 24-layer Portuguese model
    fine-tuned on it (1,000 steps), then distilled to 6 layers (16,000
    steps), with Kyutai's training code. Word errors 8.2% to 6.2% against
    the released 6 layers, ~3x real time on 4 CPU threads, and ONNX
    graphs (a FlowLM step with its KV cache, the Mimi decoder, the voice
    as a ready KV cache) that would run on OpenVINO without torch.
- **Supertonic 3**: blends of M1 with the other male styles (half and
  half, 70/30) keep every word and lower the pitch (M1 143 Hz, blends 107
  to 124 Hz); 8 and 10 steps didn't change what Parakeet heard.
- Looked up, not tried: Voxtral TTS (Mistral, 4B, CC-BY-NC, a 16 GB GPU),
  Fish Audio S2 Pro (5B, non-commercial), VoxCPM2 (2B, RTF 0.3 on an RTX
  4090), OmniVoice (CC-BY-NC), Sopro (European Portuguese only).

The samples' page is the lab's `samples/index.html` (in
`~/.cache/grog-voicelab` on the laptop that made them).

### What the user heard

- Liked: Chatterbox's Brazilian model cloning the "radio" design, and the
  6-layer Pocket TTS cloning the "deep-calm" one. And Supertonic at 1.4.
- Chatterbox is far too slow for the station's CPU. Pocket cloning
  Chatterbox's "radio" sample keeps every word too (24 and 6 layers), with
  its timbre (169 to 182 Hz, Chatterbox's 188): that voice at Pocket's
  speed, not heard yet.
- On the station itself, with turbo, nixpkgs' torch 2.11 and the
  `pocket_tts` package (in bursts, 3 s apart): the 6-layer clone of
  "deep-calm" at RTF 0.63 on 1 thread, 0.50 on 2, 0.54 on 4, its first
  audio in 0.10 to 0.16 s; of Chatterbox's "radio", 0.47 on 2 threads.
  Twice as slow as on the 386H with PyPI's torch 2.14, but real time with
  room to spare, as it streams.
- Then the user picked the 6-layer Pocket TTS clone of "deep-calm" for
  grog.

### Pocket TTS on OpenVINO

Running `pocket_tts` itself means torch (~1 GB more in grog-voice's
closure) and a package nixpkgs doesn't have, at RTF 0.5 on the station.
Instead, grog-voice runs ONNX graphs of the model on OpenVINO, as for
Supertonic:

- **The export** (`voice/tools/export_pocket.py`, in the lab, with
  pocket_tts 4.0 and `voice/tools/pocket_portuguese.yaml`):
  - `flow_lm.onnx` (358 MB, fp32): one step of the FlowLM with its KV
    cache, and one step of its flow (LSD);
  - `mimi_decoder.onnx` (41 MB): Mimi's decoder, streaming, its state
    flattened into one tensor;
  - the model's `tokenizer.model`, and `pocket.json`: how to run them
    (pocket_tts's sampling, end of speech and text rules);
  - a voice is the FlowLM's KV cache once it heard the recording
    (`voices/deep-calm.npz`: 113 frames, 5.5 MB).
  - The graphs follow `marcosremar2/pocket-tts-erinome-pt`'s
    `onnx/export.py`. Run again, the export gives the same bytes. It's
    grog's GitHub release `pocket-tts-pt-v1`, with its README.md crediting
    Kyutai (CC-BY-4.0), pinned in `body/packages/voice-models.nix`.
- **Synthesis** (`speech.Pocket`): for each piece of at most 50 tokens
  (sentences together, long ones cut at commas), the text goes into the
  voice's cache in one run, then one FlowLM step per 80 ms frame, from
  "speech starts" (NaN), with noise from a fixed seed (the same text sounds
  the same) at temperature 0.3. It stops 3 frames after the model says
  speech ends (its logit past -4; 5 frames for texts of 4 words or less),
  not before frame 6, and after (tokens / 3 + 2) s at most. Mimi decodes
  each frame as it comes (1920 samples at 24 kHz). The first one fades in
  over 5 ms: a fresh decoder clicks.
- **Text**: pocket_tts's own rules, from `pocket.json`, and Portuguese
  numbers spelled out (`pocket.py`: money, times, degrees, percentages,
  ordinals, decimals, "uma" and "duas" before feminine nouns), as the model
  reads digits badly.
- **The same as PyTorch**: the graphs against the same computation in
  PyTorch, at most 4.9e-6 apart (latent) and 1.1e-5 (cache); the first
  step, from speech starts, identical to pocket_tts's; Mimi 2.4e-6 from
  pocket_tts's streaming decoder.
- **Words**: 30 sentences like grog's answers, transcribed by Parakeet:
  5.6% wrong words (16 of 285) from the graphs, 6.3% from pocket_tts's 6
  layers, 5.6% from its 24, 6.0% from Supertonic's M1 at 1.4. Some are
  Parakeet's ("7h30" for "sete e meia").
- **Speed on the station** (turbo, nixpkgs' OpenVINO 2026.1.2), the sample
  sentence and bursts: RTF 0.259 and 0.256 with 2 threads, 0.205 and 0.204
  with 4, 0.174 and 0.173 with 6; the first sound 0.03 to 0.05 s after a
  sentence comes. Supertonic is ~3 times as fast (0.062), but only speaks a
  sentence once it's all made: 0.14 to 0.28 s for the first. In the module's
  VM test (2 cores, the fake LLM), grog started speaking 0.09 and 0.20 s
  after the answer's first words with Pocket TTS, 1.07 s with Supertonic.
- **Memory**: ~1.0 GB at its peak, in the lab (Supertonic: ~1.1 GB).

### Live, and an A/B

Heard live on the station, the 6-layer deep-calm sounded "very artificial
in pt-BR" to the user, unlike the samples they had picked it from. grog
fed it differently from those:

- The chunker cut an answer's first sentence at a comma (for Supertonic,
  to start speaking sooner), and Pocket TTS's text rules ended the clause
  with a period and capitalized the rest: "...vinte e oito graus. Com
  chance de chuva...", which Parakeet even wrote down with the full stop.
- A short first sentence ("Claro!") went alone, and came out mangled:
  "Claro! Coloquei um alarme" was heard as "Porque um alarme", "Ok, um
  alarme", "Toquei um alarme".
- Every piece started from the same noise (seed 0), so sentences started
  alike.

Since its speech plays as it's made, Pocket TTS needs no head start: it
now gets whole sentences (`Chunker(streams=True)`), a first one of 4 words
or fewer with the next (pocket_tts's own bound for short texts), the
sentences already waiting together (`mouth.gathered`), as pocket_tts reads
a text, and noise seeded by the text.

Then an A/B of four answers like grog's, as grog would say them (in the
lab's `samples/answers`), each answer transcribed alone by Parakeet:

| Variant | What Parakeet got wrong |
| --- | --- |
| 1. as it was (6 layers) | the full stop mid-sentence, "às 5", "1 minutos", "Porque um alarme" |
| 2. with the fixes (6 layers) | nothing |
| 3. with the fixes, 24 layers | "1 minutos" |
| 4 and 5. "radio" voice (cloned from Chatterbox), 6 and 24 layers | "1 minutos", "O" dropped at sentences' starts |
| 6. Supertonic M1 at 1.4 | the full stop mid-sentence, "leva", "às 22" |
| 7. Chatterbox's Brazilian model, "radio" (RTF 5.8, not live) | "1 minutos" |

Parakeet hears "a uns dez minutos" as "1 minutos" from Chatterbox too.
The user picked 3: 24 layers, deep-calm. On the station (6 threads,
turbo), the 24-layer model takes 0.53 s per second of speech for the
sample sentence, 0.485 in bursts, its first sound in 0.08 to 0.10 s (6
layers: 0.18 and 0.175, 0.03 s): real time with room to spare, as it
streams. It peaks at ~2.9 GB in the lab (6 layers: 1.0): OpenVINO's CPU
plugin keeps ~1.7 times its 1.26 GB of weights, from ONNX or from its own
IR alike (2.15 and 2.13 GB once compiled). The station had 6.7 GB
available with grog running.

## Accents

The user wanted grog carioca, or mineiro. All in the lab, then on the
station:

- **Measuring it**: the carioca mark any text shows is the chiado, a
  syllable's last s said [ʃ] ("fe[ʃ]ta"), where most of Brazil says [s].
  The lab's `accent.py` reads it from a passage: Parakeet times its words,
  and in each, the sibilant frames' spectral centroid (2 to 11 kHz) is set
  against the voice's own [s] (onset s: "sábado", "sol") and [ʃ] ("chuva",
  "caixa"): 0 for [s], 1 for [ʃ]. The passage has no ti, di, te or de near
  them (they say [tʃi]). Mineiro has no mark like it: its melody, its R,
  its swallowed endings are for the ear.
- **grog's voices**: deep-calm 0.03, radio −0.03.
- **Qwen3-TTS doesn't do accents**: VoiceDesign asked for a carioca or a
  mineiro (in English and in Portuguese), and for Lisbon as a control,
  gave −0.1 to −0.2 every time. The description changes the voice, not its
  Portuguese.
- **Spelling it** ("festa" as "fechta", "mesmo" as "mejmo") makes Pocket
  TTS say it (0.94 and 1.05), with stumbles (Parakeet heard "ecola", "é te
  está").
- **Pocket TTS copies the accent of the recording it clones**, even on
  words that weren't in it: radio cloned from 20 s of its spelled-out
  chiado read another text, spelled as usual, at 0.82; cloned again from
  that clone, 0.93. deep-calm held out (0.5 at best). The user found these
  not good enough: only the s changed.
- **Not from voice services**: fish.audio's carioca voices, which the user
  liked, are out: its terms forbid using its output to develop competing
  models, and its library is mostly clones of real people.
- **A real carioca, who agreed**: a friend of the user's recorded 37 s,
  then 20 sentences like grog's answers, one per file (~66 s of speech, MP3
  at 128 kbps). His own chiado: 0.9. Every prompt cut from them kept it,
  0.78 to 0.93 on another text, "sexta" included (no other voice said it).
  - Of 15 prompts, the steadiest is five of his sentences (15 s, 189
    frames): 4.6% of the words wrong in 30 sentences like grog's answers
    (deep-calm 4.2%), 9 of 79 in 20 short ones like "Claro!" and "Pronto,
    desliguei o microfone." (deep-calm 11; Parakeet itself loses "Ok",
    "Sim" and "Tá bom").
  - Others derailed on short texts: with three of them, "Claro! Coloquei um
    alarme..." came out "Claro" and 7 s of fading noise; others lost
    "Pronto, desliguei o microfone." or another sentence, each its own.
    Prompts of 20 s and more were no better, and slower (30 to 37 s: 1.5 to
    1.7 times deep-calm's time in the lab).
  - On the station (6 threads, bursts): 0.58 s per second of speech, against
    0.48 for deep-calm, its first sound in 0.1 s.
  - His recordings were hot (speech ~-13 dBFS RMS, peaks at -1), and the
    clone spoke as hot: 5 to 10 dB louder than deep-calm, clipping, until
    the export lowered voices too, not only raised them (-4.7 dB for his;
    deep-calm's +2.6 stays).
  - The station spoke in it (`ttsVoice` in its own config; the voice file
    stays there, in no repository), until the user, not happy with it,
    went back to deep-calm.

## Breathy short answers

Live, in the carioca voice, "Tô ouvindo, sim!" came out breathy, like a
hoarse whisper, and the next answer well. Not the station falling behind:
every frame came in time, even after a minute idle. Pocket TTS itself:

- Of 20 short answers ("Ok.", "Pronto! Tudo certo.", "Claro! Já está
  ligado."...) and 10 sentences, every voice tried says 4 to 6 of the 30
  breathy, deep-calm included, nearly all of 7 words or fewer. Measured as
  the median spectral flatness of a rendering's loud 32 ms windows, 100 to
  4000 Hz: speech ~0.01, those 0.03 to 0.2 ("Tô ouvindo, sim!" 0.077).
- Lower temperatures make it worse (0.1 or 0: 8 or 9 of 30); other noise
  says them well (other seeds: 2 of 30, other texts each time).
- The first 800 ms tell: above 0.03 there, 27 of 28 breathy renderings,
  and 9 of 222 good ones.
- So grog-voice makes a piece of 8 words or fewer 800 ms ahead before it
  plays, and again with other noise while that sounds breathy, up to 3
  times, keeping the least breathy (`pocket.steadiest`). Breathy ones: 5 to
  0 of 30 in the carioca voice, 6 to 4 in deep-calm (one-word answers left,
  0.03 to 0.054); words wrong in the short answers: 9 to 7 of 79, and 11 to
  7.
- Its cost: a short answer starts 0.46 to 0.49 s after its text on the
  station rather than 0.1 (0.4 s more each time it's made again); longer
  pieces stream as before.
- Not enough: the user then heard "De boa!" as several voices at once
  (0.025 over its first 800 ms, under that limit), long answers well. What
  goes wrong is a murmur, like voices behind: over a whole rendering, at -37
  to -47 dBFS where the voice speaks at ~-15, or after a word.
- pocket_tts pads short texts (under 5 words) with 8 spaces before them for
  its English model, not its Portuguese one. Padded, of 5 tries at each of
  16 short answers, 61% come out well (0.02 at most, their loudest 80 ms
  frame at -32 dBFS or more) against 50%, and every answer has a good one
  (3 had none). Temperature 0.5: 66%; the 6-layer model, no better.
- So grog-voice pads them too, takes a try as murmured past 0.015, or
  quieter than -32 dBFS at its loudest, and keeps the least murmured of 3.
  Over 27 short answers, renderings past 0.015: 9 to 3 (the worst 0.076 to
  0.026, "De boa!" 0.029 to 0.002). Their first sound comes 0.6 s after
  their text (the median, in the lab), 1.8 s at most (three tries).

## Pauses between sentences

The user found grog hardly paused between sentences. Pocket TTS pauses
~0.33 s between sentences it reads together (0.26 to 0.43 s in the
carioca voice), but grog-voice says the first sentence alone, to start
sooner, and the next piece came right after it: 0.1 s, or no pause at all.
So a piece that ends a sentence is followed by silence, up to 0.4 s of
quiet at its end (`pocket.pause`): 0.40 to 0.46 s there now, in either
voice. The mouth plays it only before more speech, and only what's left
of it by then (`mouth.Pauses`): not at the end of an answer, where it
would hold off a quick "Sim" to a question (while grog's sound plays, only
what's louder than it is heard), nor after a wait for grog's tools. Inside a piece, the carioca voice still sometimes
runs sentences together (0.1 s), as he does himself (his pauses: 0.1 to
0.4 s).

## Exclamations

The user always found the voice too cheerful where an answer had "!". So
grog-voice reads them as periods, whichever voice speaks
(`mouth.calmed`: "Claro!" as "Claro.", "Sério?!" as "Sério?"); what grog
shows keeps them. Over 8 answers like "Pronto! Desliguei a luz da sala.",
each said twice in deep-calm, its pitch went from 156 Hz to 126 Hz (the
median, 3.7 semitones lower) and its voice 2.5 dB quieter.

## Music

Once grog played music on its own speaker (the station: a laptop, its
speaker right next to its mics), it couldn't hear its name over it. The
log said "not for grog: 15.0 s, 14.6 s of it speech, peak -3 dBFS" over
and over. Silero took the music for speech, so every utterance ran to
`maxUtteranceSeconds`. The name was somewhere in 15 s of lyrics, past the
two words allowed before it, and partials only cover an utterance's first
2.5 s. Alexa handles it in three steps, and grog now does too:

1. **Echo cancellation of the music only**: grog-music plays into
   PipeWire's echo-cancel module (WebRTC's canceller alone), and
   grog-voice records from its source, `grog-mic` (see body/README.md,
   "Echo cancellation"). The canceller's reference is the music alone.
   grog's own voice and earcons go straight to the speaker, as before (see
   "Echo cancellation" under "The ears": on them, it clipped and ate the
   words of someone talking over grog; and "Interruptions").
2. **Windows** (`ears.py`, `Ears._window`): grog tells grog-voice whether
   music plays (`GET /v1/voice/commands`: `music` in the snapshot, and a
   `music` event when it changes). While it does, and grog-voice waits for
   its name (not once awake, or asked), what the VAD takes for speech is
   cut into windows of `musicUtteranceSeconds` (4 s), each starting
   `MUSIC_OVERLAP_S` (1 s) before the last one ends, so a name said across
   a cut is whole in one of them. Each window is transcribed on the main
   Parakeet, one transcription at a time with the pause guesses, and the
   name is looked for anywhere in it (`WakeWords.match(anywhere=True)`), as
   a window starts mid-sentence. A name last in a window counts, unlike in
   partials, since people say it and wait. The next window starts before
   it ends, so a name cut short there ("Drog" of "Droga") is whole in that
   one. Without the name, what came before the next window is let go of,
   so utterances over music no longer run to 15 s. With it, the utterance
   starts over from that window: what follows the name is the request, as
   with "Grog, que horas são?" said in one breath. From there it ends as
   usual, with `maxUtteranceSeconds` counted from that window. What's
   shown and asked starts at the name, never at the lyrics or someone else's
   words before it. Once the music stops, it's back to the usual at the
   next frame.
3. **Ducking**: grog lowers the music once grog-voice says it hears
   someone talking to it (`hearing`), so the request ends at a pause as
   usual.

Partials still run at an utterance's start, up to 2.5 s, but not once it
is cut into windows. By then the windows do their job, on the other model,
for nothing. Measured with fakes over 13 s of music: 4 windows, at most 6
partials, none past 2.5 s. A 4 s window every 3 s costs the main Parakeet
~0.2 s on the station (fp32, 4 threads, by the commands measured in round
3), so it's busy ~7% of the time, about a quarter of a core. That's an
estimate, and it only applies while music plays and the VAD hears speech.

Measured on the station (October 22, 2026), with the user calling grog
from where they talk to it (1 to 2 m), the speaker at 100%, the mic at its
0.68, levels in 0.5 s blocks (recordings deleted once measured):

- **The canceller takes ~30 dB of the music out**: the raw mic got it at
  -10 to -16 dBFS RMS, peaking near 0 dBFS; `grog-mic`, at -45 to -50.
  The VAD stopped taking the music for speech: no utterance at all while
  it played.
- **With grog-music at 100 the mic saturates**, and what's left of the
  music rose to -27 dBFS in loud passages: nothing cancels clipping.
  Turning the mic down to 0.45 got it back to -50, but lowers everyone.
- **A voice played on the speaker beside the music** (not in the
  canceller's reference) came through 20 to 30 dB above what was left of
  the music with grog-music at 70, 15 at 85. It's much louder at the mic
  than someone 2 m away, though.
- **The user's own voice didn't**: with grog-music at 80 and at 60,
  `grog-mic` stayed flat through three calls, and grog-voice heard nothing.
  The music reaches the mic ~20 dB louder than their voice from there, and
  WebRTC's suppressor took their voice out with the music. At 40 (cubic in
  mpv: ~-24 dB), their voice came through, but the name was only heard
  once in four calls. The lighter canceller (`webrtc.mobile_mode`, AECM)
  wasn't measured with them.
- So on a laptop, with its speaker beside its mics, grog can't hear its
  name over its own music at listening volumes: it takes a mic array and a
  speaker apart from it, as Alexa has. The music is controlled on grog's
  screen meanwhile (the skin's now-playing bar), and touching its face
  makes it listen without its name, the music ducked at once. With a
  speaker away from the mics (Bluetooth, across the room), the canceller
  and the windows should do much better: to measure then, with the wake
  rate from 1 and 3 m, false wakes from lyrics, and CPU (`top`: grog-voice,
  grog-music and the canceller together).
- **With a Bluetooth speaker paired**, the session's default, grog spoke
  there too, and the user wanted it for the music only: the skin's Speakers
  pick them apart (the music's is the default; grog's voice's, kept by the
  brain), across restarts (brain/README.md, "Speakers"). Its voice on the
  laptop's speaker isn't in the canceller's reference: while it speaks,
  grog-voice only hears what's louder than it (see "Interruptions").
- **Pausing the music left grog deaf for minutes** (October 24, 2026):
  every "Grog" after a pause came out "not for grog". `pw-top` showed the
  canceller missing ~70 of its 100 cycles a second, `grog-mic` with them,
  from the pause on: what grog-voice heard came in pieces. Restarting the
  canceller cured it until the next pause. Reproduced inaudibly: an mpv
  playing pink noise at -70 dBFS into `grog-music-out`, paused, and 10 s
  later hundreds of xruns, none with silence played instead. Once the
  reference goes from sound to zeros, WebRTC's filters decay into denormal
  floats, which the CPU crawls through, until they reach zero minutes
  later (it once came back by itself after ~7). With `cpu.zero.denormals`
  on the canceller's PipeWire (flush to zero, on its data thread), the
  same pause leaves no xrun. The module's VM test can't catch it: its
  virtual mic records exact zeros, and the same pause there never fell
  behind.

## Interruptions

The user wanted grog to take interruptions as Alexa does: while grog
thinks, searches or speaks, its name alone stops it and waits for what
comes next, and "Grog, ..." stops it and does that instead. Until then
grog-voice dropped what the mic heard while grog answered, and only
touching grog's face stopped it.

Measured on the station (October 25, 2026), its speaker at 100% unless
said, the mic at its 0.68, levels in 0.5 s blocks, with calls ("Grog, que
horas são?" and the like) made with text to speech and played on the
station's speaker (recordings deleted once measured):

- **grog's voice is the loudest thing its mic hears**: it clips it (peaks
  at 0 dBFS, -8 to -15 dBFS RMS). A call played 20 dB down peaked at -14
  to -16 dBFS (-27 to -34 RMS), and speech in the room, in grog-voice's
  logs, peaks at -19 to -30 dBFS: grog's voice reaches the mic ~25 dB
  louder than someone talking to it, by those numbers.
- **Transcribing the mic while grog speaks**: none of 6 calls was heard,
  only grog's words, and its "Eu sou o Grog" had its name in it: grog
  would call itself.
- **grog's voice through the music's echo canceller** (played into
  `grog-music-out`): its echo went down to -28 to -45 dBFS RMS, and the
  calls went with it: none of 6 heard as loud as above, none 8 dB louder,
  1 of 6 (garbled) 14 dB louder. With grog's voice 20 dB down, as from a
  speaker farther away, none of 6 was heard, with the canceller or
  without: Parakeet follows the louder voice. 30 dB down, about as loud as
  the calls, it heard bits of them.
- **At 50% the laptop's speaker plays much quieter** (its DSP): a call
  played at full level reached the mic at ~-47 dBFS RMS, peaking at -28
  to -33.

So grog's voice still goes straight to the speaker, and grog-voice listens
while grog answers, through an echo gate (`echo.py`):

- **The mouth says when grog's sounds play** (`Echo.played`, as each piece
  is written to the speaker, after what it still plays), its speech apart
  from its earcons. While they play, and 0.5 s after (`TAIL_S`: the room,
  the speaker's own delay, and the mic's frames, seen stamped up to ~0.35
  s late under load), a frame no louder than their echo gets a speech
  probability of 0: it can't start an utterance. Pocket TTS sometimes falls
  behind on the station (80 ms pieces every ~120 ms), so grog's sound comes
  in bits, which the tail joins. Once an utterance started, the rest of it
  is heard as usual: the earcon saying grog listens, played while someone
  talks on after its name, never cuts them.
- **How loud their echo is**: the 90th percentile of the levels of every
  frame heard while grog spoke, over the last 10 s of it, plus 6 dB
  (`MARGIN_DB`). Until it has 1 s of them, nothing is heard over grog's
  sounds. Learning from every frame while they played, earcons included
  (their echo is quieter), set the limit too low, and grog's voice started
  an utterance; learning only from frames the VAD took for speech left its
  quieter echo out, and nothing ever got through.
- **What's heard over them**: as usual, its name heard in a partial or at a
  pause stops the answer at once (the player killed, the connection to
  grog shut down), with the earcon saying it listens. Its name alone wakes
  it up (8 s without the name); "Grog, ..." is answered next. What isn't
  for grog is let be, and the answer goes on. What's still being said as
  the answer ends is the next turn's. "Grog, para" goes to grog like
  anything else, which answers it: grog-voice never decides an answer.
- **The brain**, hung up on, stops the turn at once, even mid web search
  (its provider's stream checks every 200 ms), as stopped rather than
  failed. The conversation keeps the question and what was answered so
  far, marked " …", and with the next question grog-voice says how much
  of the answer was said aloud, its sentences played to their end
  (`cutOff`): the conversation keeps only that of it, so "continua" goes
  on from where the user stopped hearing.
- While grog speaks, the VAD now runs on every frame (they were dropped);
  with nothing over the gate, nothing is transcribed.

Live on the station, with a test copy of grog-voice (its service stopped
meanwhile) and the brain as it was:

- Calling grog while it thought cut the answer off, and what was asked
  was answered, twice (the first turn "stopped" in the log).
- With grog's voice at 100%, the gate's limit came to ~-4 dBFS: nothing
  is heard over it there, as measured above, and it never started an
  utterance (once, before the gate learned from grog's speech alone).
- With grog's voice played 30 dB down by the test copy, the speaker at
  50%, as from a speaker farther away: "Grog, que horas são?" over it cut
  the answer off, and was answered.
- "Grog!" alone over its voice, at that level, was too quiet for the VAD.
  The tests cover it.
- Some calls lost their first "Grog," as with the service as it was: the
  VAD started ~1 s into them, past the 0.42 s kept from before speech (the
  endpointer's preroll), so they weren't for grog. Offline, keeping the
  VAD's state between utterances or resetting it made no difference: both
  started 1.44 s in. Not fixed (see "Where to go from here").

## The ReSpeaker XVF3800

On October 26, 2026 the station got a ReSpeaker XVF3800 USB 4-Mic Array,
in its case (Seeed, USB 2886:001a, its USB firmware at bcdDevice 0206): four
mics in a circle, and an XMOS XVF3800 doing echo cancellation,
beamforming, dereverberation, noise suppression and up to 60 dB of gain
control.

- **Over USB**, with its firmware as it came (2.0.6), it recorded and
  played 2 channels of 16-bit audio at 16 kHz only
  (`/proc/asound/card1/stream0`); at 48 kHz since its update (below).
- **What it records**: channel 0 (left) is its "conference" output,
  channel 1 (right) the ASR output of its auto-selected beam, as Seeed
  says. With speech in the room, they were nearly the same signal
  (correlation 0.96, no lag), the right one ~5 dB noisier between words
  (-44 to -46 dBFS RMS against -49 to -53). So the echo canceller mixing
  them to mono for `grog-mic` is fine as it is.
- **It's the session's default mic**, picked with `wpctl set-default` as
  grog: WirePlumber keeps it, the laptop's mics coming next when it's
  unplugged. The music's echo canceller records from it, grog-voice from
  `grog-mic`. Speech in the room peaked at -22 to -2 dBFS through it,
  against -19 to -30 with the laptop's mics: its gain control at work.
- **Its echo canceller's reference is what it plays itself**, the left
  channel of its USB output, which its DAC plays: grog's voice played
  through it should be taken out of what it records, and someone heard
  over grog while it speaks (see "Interruptions"). It has no speaker: a
  3.5 mm jack, and inside the case (three clips hold it) a JST PH 2.0
  connector for a speaker of up to 5 W on its amplifier. A 4 Ω 5 W
  enclosed speaker for it is on its way, for grog's voice more than music,
  which stays on the Bluetooth speaker.
- **Its firmware** went from 2.0.6 to 2.1.1 (October 26, 2026), the 48 kHz
  2-channel image (`respeaker_xvf3800_usb_dfu_firmware_v2.1.1_48k2ch.bin`,
  from Seeed's repository, checked against its git blob), as root:
  `dfu-util -d 2886:001a -S <its serial> -R -e -a 1 -D <image>`. Alt 1 is
  the upgrade image, alt 0 the factory one, left alone; the serial keeps
  dfu-util off the webcam, which has a DFU interface too. Since 2.0.6: the
  ring's per-LED mode (2.0.7), USB audio fixed after bus resets on Linux
  (2.0.10), its headphone and speaker gain set over USB, 8 dB by default
  (2.1.0), and its button's events with its own mute off (2.1.1). It came
  back with the same nodes, still the default mic, recording and playing
  at 48 kHz: grog's voice through it won't stop at 8 kHz, and PipeWire,
  running at 48 kHz, resamples less.
- **Its control interface**: USB vendor control transfers on endpoint 0,
  0.2 to 0.6 ms each (Seeed's `python_control/xvf_host.py` lists the
  commands); a command its firmware lacks answers status 65. Its usbfs
  node is root's: the NixOS module's udev rule gives it to grog-voice's
  user. Reading its mute pin back (GPO_READ_VALUES) lags a write behind,
  so grog-voice keeps track of it. A press of its button once failed the
  transfer under way (EPROTO) without it going away: grog-voice tries
  again before taking it as gone. With its own mute off, a press latches
  an event bit (GPI_EVENT_PENDING_ALL, read and cleared), and sends no key
  through its HID inputs, which GNOME could take for a mic mute key.
- **Its ring** (`ring.py`), as the user picked on it: off at rest; pointing
  at who talks (its DoA effect) in the skin's hearing green, at 6% around;
  spinning in the thinking indigo while grog thinks (the per-LED mode, 12
  frames a second), picked over breathing; breathing green while grog
  speaks (the skin's awake green: its coral looked red on the ring); red a
  moment on errors, dim red while grog's mic is off, amber without a mic.
- **Its mute**: raising its mute pin (X0D30) silenced its mics (-119 dBFS
  RMS at `grog-mic`) and lit its red LED: grog's mic off raises it. While
  grog-voice runs, its button's own mute is off, and a press asks grog to
  switch its mic (POST /v1/actions/mic), the pin following at once, asked
  again while grog can't be reached, muted meanwhile. Once grog-voice
  stops (SIGTERM included), its own mute is back on.
- **Meanwhile grog speaks through its screen's speaker** (the MPI7009's,
  over HDMI, picked for grog's voice on its screen), 3 dB over full
  volume (`wpctl set-volume` 1.12, a gain of 1.405), as it's weak. That
  speaker is only there while the screen is on. Under cage the kiosk turns
  the screen off with `wlr-randr --off` after `skin.screenOffMinutes`,
  which takes its HDMI audio too (no ELD), and only turns it on once
  grog-voice hears grog's name, as it plays the sound saying it listens:
  that sound is mostly lost. The audio is back 65 ms after the screen is
  turned on. The user chose to keep it that way until the ReSpeaker has
  its speaker.
- The screen's HDMI audio is also the session's default speaker: asked by
  voice, grog made it so with its hands (`shell`). So the music plays there
  while the screen is on, and while it sleeps WirePlumber falls back to
  the Bluetooth speaker, or the laptop's.

## Where to go from here

### Ears

- "Grogi" and "Krogi" (keys `KRuJ`) could count as the name: no Portuguese
  word sounds like them. Check for false wakes first.
- A wake word model of its own (openWakeWord, microWakeWord) for "Grog"
  alone from afar, where speech to text struggles most.
- Calling grog while it speaks, on the station: grog's voice on the
  ReSpeaker's own speaker, for its echo canceller to take out (see "The
  ReSpeaker XVF3800"). Otherwise an echo canceller that keeps the words of
  someone talking over grog (WebRTC's ate them; its lighter AECM wasn't
  tried), or a speaker away from the mics (see "Interruptions").
- A quiet "Grog" said first is sometimes lost, the VAD starting late (see
  "Interruptions"): a longer preroll, or a lower `start_prob`, may keep it.
  Check what else starts an utterance then.
- What's heard while grog speaks could be checked against what it says,
  so that its own words ("Eu sou o Grog") never call it, should they get
  over the gate (they haven't so far).

### A voice of grog's own

- **Mix Supertonic's styles.** A voice is two small arrays, so blending M1
  with the other nine styles costs nothing to try and needs no training.
  Supertonic's README points to a community "Supertonic Voice Mixer"
  (Topping1/Supertonic-Voice-Mixer).
- **Train a Piper voice** (piper1-gpl's training, VITS, on a GPU), ideally
  fine-tuning an existing pt_BR checkpoint.
  - It would run at Piper's speed on grognix (RTF 0.19), but the user found Piper's voices flat, so the architecture may cap what training gets.
  - Data could be real recordings (a speaker who agrees to it, hours of clean speech) or synthetic speech from a model whose license allows it.
  - OpenRAIL-M usually counts a model trained on another's outputs (distillation) as a derivative: allowed, but it inherits the use restrictions. Read Supertone's LICENSE before training on M1's output.
  - pt-BR datasets to check, licenses included: Edresson Casanova's TTS-Portuguese Corpus (Piper's `edresson` voice), CML-TTS.
- **Pocket TTS**: done on the station with a clone, deep-calm, on 24
  layers (see "Pocket TTS on OpenVINO" and "Live, and an A/B"). Fine-tuning
  the 24-layer model on hours of that voice, like the Erinome one (see
  round 3), would make it steadier, and distilling it to 6 layers would make
  it 3 times as fast for slower CPUs. Measure the ONNX path on grognix
  before it speaks that way there.
- **The carioca voice, steadier**, should the user want it back (see
  "Accents"):
  - Fine-tune the 24-layer model on an hour or more of his sentences.
    Kyutai's training code (open since August 2026) wants an NVIDIA GPU (16
    GB with a batch of 16 times 4). Minervus00/ft_pocket_tts tuned a voice
    from 5.5 h in ~1,000 steps, at ~1.8 steps per second on an A100.
    Nothing here has one: renting one is paid, the user's call.
  - Or keep grog-voice from derailing: speech gone quiet for over a second
    mid-piece, without the model saying it's done, is a derailment to stop
    (or to retry with other noise). The check for breathy short answers
    (see "Breathy short answers") misses it: its noise comes after the
    first words, quieter.
- **Kokoro / StyleTTS2** can be fine-tuned too, with heavier inference than
  Piper.
- **Supertonic itself** can't be trained without Supertone (Voice Builder is
  paid).

### Faster

- Any CPU with AVX2 (an Intel N100-class mini PC, say) should run Supertonic
  3 comfortably under real time. It would also open up Pocket TTS's 24-layer
  model and Kokoro.
- The iGPU, with newer drivers or OpenVINO (see above).
- sherpa's int8 graphs on OpenVINO: the same speed, ~70 MB less RAM.
- A shorter first chunk (`FIRST_CLAUSE_CHARS` in `chunks.py`), so the first
  words come sooner.

### Open questions

- The real wait on grognix, measured during conversations, when grog-voice
  isn't transcribing while it speaks.
- Calling grog over its voice from a speaker away from the mics (a
  Bluetooth one across the room): how often it's heard from 1 and 3 m,
  whether 6 dB over its echo is the right margin, and whether anything
  else starts an utterance then.
- With grog's voice on the ReSpeaker's own speaker: how much of it its
  echo canceller takes out, whether grog can then be called while it
  speaks, and how often the ReSpeaker hears its name from 1 and 3 m
  against the laptop's mics.
- Portuguese text handling: for Pocket TTS, `pocket.py` spells out
  numbers, money, times, degrees, percentages and ordinals; abbreviations,
  and English words in Portuguese sentences, are left to the models.
- `tts.language` has to match the language grog answers in (the brain's
  `language` setting, or the user's). It could follow it by itself.
- RAM headroom on grognix with everything running, after this change.

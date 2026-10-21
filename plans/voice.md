# grog's voice: what we learned

Context for whoever improves grog's voice next, or trains one of its own:
what was tried in October 2026, what it measured, what the user liked, and
why grog speaks the way it does. The numbers come from grognix itself unless
said otherwise.

## Where it stands

- It runs on an asus oled now, a Core Ultra 7 155H, rather than grognix's
  Celeron: see "Round 3" below. The rest of this file is about grognix
  unless said otherwise.
- grog speaks with **Supertonic 3** (Supertone, May 2026), voice **M1**, in
  Portuguese (`pt`), run by **OpenVINO's CPU plugin**: the official fp32 ONNX
  graphs from `Supertone/supertonic-3`, pinned at revision
  `3cadd1ee6394adea1bd021217a0e650ede09a323`, 4 threads, 5 steps, at speed
  1.4 (`tts.speed`, see "Speed" below).
- Piper stays available (`tts.engine = "piper"`, `tts.voice` = a `.onnx`).
- It hears with Parakeet TDT 0.6B v3 in int8, or fp32 with
  `services.grog.voice.sttPrecision` (see "The ears" below).
- grog can ask it to listen now (someone touched its face on the skin), to
  stop answering (pw-cat killed, the connection to grog shut down, which
  ends grog's turn), or to switch its mic off (pw-cat for the mic stopped:
  nothing recorded), over `GET /v1/voice/commands`
  (`voice/grog_voice/commands.py`). Until then grog-voice could only tell
  grog what it was doing.
- Code: `voice/grog_voice/supertonic.py` (text handling, pure), the
  `Supertonic` and `Piper` classes in `voice/grog_voice/speech.py`, the
  `tts.*` settings in `voice/grog_voice/config.py`, the models in
  `body/packages/voice-models.nix`, the NixOS options (`ttsEngine`,
  `ttsModel`, `ttsVoice`) in `body/modules/grog.nix`.
- What it costs over Piper: answers start 1 to 3 s later, ~1 GB more RAM
  (the text to speech alone peaked at 1.19 GB on the laptop), ~400 MB of
  model files, ~235 MB more in grog-voice's closure (openvino 135 MB, opencv
  76 MB, small libraries).
- Supertonic speaks ~8 dB lower than Piper (speech RMS -22 to -24 dBFS,
  peaks -5 to -8 dBFS, against Piper's -15 and 0): each piece is brought up
  to peak at -1 dBFS, by at most +12 dB, like Piper's normalized output, as
  the earcons (`sounds.py`) and the speaker's volume were set for Piper.

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
  grog-voice`). When it speaks for real, it doesn't listen.
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
  - Too slow on grognix in PyTorch, by the laptop numbers; an ONNX path was never measured.
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
  talked over grog, their words went too. Not worth it there: grog-voice
  stays half duplex.
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

## Where to go from here

### Ears

- "Grogi" and "Krogi" (keys `KRuJ`) could count as the name: no Portuguese
  word sounds like them. Check for false wakes first.
- A wake word model of its own (openWakeWord, microWakeWord) for "Grog"
  alone from afar, where speech to text struggles most.
- Echo cancellation again, with the speaker well away from the mic: it
  would let the earcon play over speech, and someone interrupt grog.

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
- **Pocket TTS**: open training code and voice cloning. It needs a faster
  machine, or a measured ONNX path, to run on grognix. On the asus oled, a
  male voice designed with Qwen3-TTS, cloned, or better fine-tuned and
  distilled to 6 layers like the Erinome one (see round 3), then run from
  ONNX graphs on OpenVINO, is the likeliest voice of grog's own.
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
- Portuguese text handling beyond "28 graus": money, times, ordinals,
  abbreviations, English words in Portuguese sentences.
- `tts.language` has to match the language grog answers in (the brain's
  `language` setting, or the user's). It could follow it by itself.
- RAM headroom on grognix with everything running, after this change.

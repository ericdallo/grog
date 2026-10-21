"""Exports Pocket TTS (Kyutai, CC-BY-4.0) to what grog-voice runs on OpenVINO
(speech.Pocket), without torch, and voices cloned from recordings for it.

  python export_pocket.py model CONFIG.yaml LANGUAGE OUT_DIR

writes to OUT_DIR:

  flow_lm.onnx       one step of the FlowLM, with its KV cache: tokens [1, Nt]
                     int64, latent [1, Nl, 32] (NaN: speech starts), noise
                     [1, 32], cache [L, 2, 1, T, H, D] -> latent_out [1, 32],
                     eos [1, 1] (a logit), cache_out [L, 2, 1, T + Nt + Nl, H, D]
  mimi_decoder.onnx  Mimi's streaming decoder: latent [1, N, 32], state [S],
                     offset [1] int64 (200 Hz frames decoded) -> audio
                     [1, 1920 N] at 24 kHz, state_out [S]
  tokenizer.model    the model's SentencePiece tokenizer
  pocket.json        how to run them: sampling, end of speech, text, and the
                     language it reads (LANGUAGE, like "pt": grog-voice spells
                     out Portuguese numbers)
  README.md          where it all comes from, and its license

  python export_pocket.py voice CONFIG.yaml RECORDING.wav OUT.npz

writes a voice cloned from RECORDING.wav (a few seconds of clean speech), for
voices/NAME.npz in the model's directory or for tts.voice: `cache`, the
FlowLM's KV cache once it heard the recording, and `gain_db`, to bring its
speech up to ~-18 dBFS (RMS), peaking at -1 dBFS at most.

CONFIG.yaml: a pocket_tts config with voice cloning weights (`weights_path`),
the same for the model and its voices. The graphs follow
marcosremar2/pocket-tts-erinome-pt's onnx/export.py (CC-BY-4.0), for
pocket_tts 4.0. Run it where torch and pocket_tts are (`pip install
pocket-tts==4.0.0 onnx`).
"""

import argparse
import json
import math
import shutil
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from pocket_tts import TTSModel
from pocket_tts.default_parameters import MAX_TOKEN_PER_CHUNK
from pocket_tts.modules.conv import StreamingConv1d, StreamingConvTranspose1d
from pocket_tts.modules.rope import apply_rope
from pocket_tts.modules.seanet import SEANetResnetBlock
from pocket_tts.utils.utils import download_if_necessary
from torch import nn


def attention(q, k, v, bias):
    """q, k, v [1, T, H, D]."""
    out = F.scaled_dot_product_attention(q.transpose(1, 2), k.transpose(1, 2), v.transpose(1, 2), bias)
    return out.transpose(1, 2)


def feed_forward(layer, x):
    return x + layer.layer_scale_2(layer.linear2(F.gelu(layer.linear1(layer.norm2(x)), approximate="tanh")))


def qkv(attn, x):
    """Queries, keys and values [1, n, H, D] (keys and values have as many heads)."""
    assert attn.num_kv_heads == attn.num_heads
    n, h, d = x.shape[1], attn.num_heads, attn.dim_per_head
    return attn.in_proj(x).view(1, n, 3, h, d).unbind(2)


class FlowLM(nn.Module):
    """The text and latents so far in, the next latent out (one LSD step)."""

    def __init__(self, lm):
        super().__init__()
        self.lm = lm
        assert lm.flow_type == "lsd" and lm.z_proj is None

    def forward(self, tokens, latent, noise, cache):
        lm = self.lm
        x = torch.cat(
            [lm.conditioner(tokens), lm.input_linear(torch.where(torch.isnan(latent), lm.bos_emb, latent))], 1
        )
        past, n = cache.shape[3], x.shape[1]
        pos_q = torch.arange(n) + past
        pos_k = torch.arange(n + past)
        bias = torch.where(pos_k[None, :] <= pos_q[:, None], 0.0, float("-inf"))
        layers = []
        for i, layer in enumerate(lm.transformer.layers):
            attn = layer.self_attn
            q, k, v = qkv(attn, layer.norm1(x))
            q, k = apply_rope(q, k, past, attn.rope.max_period)
            k = torch.cat([cache[i, 0], k], 1)
            v = torch.cat([cache[i, 1], v], 1)
            layers.append(torch.stack([k, v]))
            x = x + layer.layer_scale_1(attn.out_proj(attention(q, k, v, bias).flatten(2)))
            x = feed_forward(layer, x)
        hidden = lm.out_norm(x)[:, -1]
        zero, one = torch.zeros_like(noise[:, :1]), torch.ones_like(noise[:, :1])
        return noise + lm.flow_net(hidden, zero, one, noise), lm.out_eos(hidden), torch.stack(layers)

    def empty_cache(self, frames):
        """A cache of `frames` zeros, to trace the graph with."""
        layers = self.lm.transformer.layers
        attn = layers[0].self_attn
        return torch.zeros(len(layers), 2, 1, frames, attn.num_heads, attn.dim_per_head)


class Mimi(nn.Module):
    """Mimi's decoder, streaming, its state flattened into one tensor."""

    def __init__(self, model):
        super().__init__()
        self.mimi, self.lm = model.mimi, model.flow_lm
        projected = model.mimi.decoder_transformer
        assert projected.input_proj is None and all(
            isinstance(p, nn.Identity) for p in projected.output_projs
        )
        self.transformer = projected.transformer
        self.window = self.transformer.context
        self.shapes = [self.partial(self.mimi.upsample.convtr)]
        for layer in self.transformer.layers:
            attn = layer.self_attn
            self.shapes += [(1, self.window, attn.num_heads, attn.dim_per_head)] * 2
        self.collect(self.mimi.decoder.model)
        self.size = sum(math.prod(s) for s in self.shapes)

    @staticmethod
    def partial(m):
        return (1, m.convtr.out_channels, m._kernel_size - m._stride)

    def collect(self, layers):
        for m in layers:
            if isinstance(m, StreamingConv1d):
                assert m.pad_mode == "constant"
                if m._effective_kernel_size > m._stride:
                    self.shapes.append((1, m.conv.in_channels, m._effective_kernel_size - m._stride))
            elif isinstance(m, StreamingConvTranspose1d):
                self.shapes.append(self.partial(m))
            elif isinstance(m, SEANetResnetBlock):
                self.collect(m.block)

    def forward(self, latent, state, offset):
        sizes = [math.prod(s) for s in self.shapes]
        pieces = iter([p.view(s) for p, s in zip(torch.split(state, sizes), self.shapes, strict=True)])
        new = []
        x = self.mimi.quantizer((latent * self.lm.emb_std + self.lm.emb_mean).transpose(1, 2))
        x = self.convtr(self.mimi.upsample.convtr, x, pieces, new).transpose(1, 2)
        for layer in self.transformer.layers:
            x = self.attend(layer, x, next(pieces), next(pieces), offset, new)
        x = x.transpose(1, 2)
        for m in self.mimi.decoder.model:
            x = self.layer(m, x, pieces, new)
        return x[:, 0], torch.cat([p.reshape(-1) for p in new])

    def layer(self, m, x, pieces, new):
        if isinstance(m, StreamingConv1d):
            tp = m._effective_kernel_size - m._stride
            if tp == 0:
                return m.conv(x)
            x = torch.cat([next(pieces), x], -1)
            new.append(x[..., -tp:])
            return m.conv(x)
        if isinstance(m, StreamingConvTranspose1d):
            return self.convtr(m, x, pieces, new)
        if isinstance(m, SEANetResnetBlock):
            v = x
            for sub in m.block:
                v = self.layer(sub, v, pieces, new)
            return x + v
        return m(x)

    @staticmethod
    def convtr(m, x, pieces, new):
        y = m.convtr(x)
        pt = m._kernel_size - m._stride
        tail = y[..., -pt:]
        if m.convtr.bias is not None:
            tail = tail - m.convtr.bias[:, None]
        new.append(tail)
        return torch.cat([y[..., :pt] + next(pieces), y[..., pt:-pt]], -1)

    def attend(self, layer, x, past_k, past_v, offset, new):
        attn = layer.self_attn
        n, w = x.shape[1], self.window
        q, k, v = qkv(attn, layer.norm1(x))
        q, k = apply_rope(q, k, offset.to(torch.float32), attn.rope.max_period)
        k = torch.cat([past_k, k], 1)
        v = torch.cat([past_v, v], 1)
        new += [k[:, -w:], v[:, -w:]]
        pos_q = torch.arange(n) + offset
        pos_k = torch.arange(n + w) + offset - w
        delta = pos_q[:, None] - pos_k[None, :]
        bias = torch.where((pos_k[None, :] >= 0) & (delta >= 0) & (delta < w), 0.0, float("-inf"))
        x = x + layer.layer_scale_1(attn.out_proj(attention(q, k, v, bias).flatten(2)))
        return feed_forward(layer, x)


def voice_cache(model, state):
    """The FlowLM's KV cache [L, 2, 1, T, H, D] in a model state."""
    layers = []
    for i in range(len(model.flow_lm.transformer.layers)):
        layer = state[f"transformer.layers.{i}.self_attn"]
        end = int(layer["offset"].view(-1)[0])
        layers.append(layer["cache"][:, :, :end])
    return torch.stack(layers)


# What a voice says to set its gain by.
SAMPLE = (
    "Oi, eu sou o Grog. Posso ver a previsão do tempo, tocar uma música ou responder suas perguntas. "
    "Amanhã deve fazer vinte e oito graus em São Paulo, com chance de chuva no fim da tarde."
)


def gain_db(model, state):
    """How much louder the voice should be: its speech to ~-18 dBFS (RMS),
    peaking at -1 dBFS at most, by up to +12 dB."""
    torch.manual_seed(0)
    audio = model.generate_audio(state, SAMPLE).numpy()
    speech = audio[np.abs(audio) > 0.01]
    rms = 20 * np.log10(np.sqrt(np.mean(speech**2)))
    peak = 20 * np.log10(np.abs(audio).max())
    return float(np.clip(min(-18 - rms, -1 - peak), 0, 12))


def readme(config, language):
    sources = [
        path
        for path in (
            config.weights_path,
            config.flow_lm.weights_path,
            config.mimi.weights_path,
            config.flow_lm.lookup_table.tokenizer_path,
        )
        if path
    ]
    return f"""# Pocket TTS ({language}) for grog-voice

[Pocket TTS](https://github.com/kyutai-labs/pocket-tts), by Kyutai, as
[grog-voice](https://github.com/ericdallo/grog) runs it on OpenVINO, without
torch: converted to ONNX by grog's `voice/tools/export_pocket.py` (its FlowLM a
step at a time, with its KV cache, and Mimi's decoder streaming), from:

{chr(10).join(f"- `{path}`" for path in sources)}

`voices/` holds voices made by the same script: the model's state once it
heard a recording.

License: [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/), as Pocket
TTS's weights.
"""


def export_model(model, language, out):
    out.mkdir(parents=True, exist_ok=True)
    flow, mimi = FlowLM(model.flow_lm).eval(), Mimi(model).eval()
    with torch.no_grad():
        torch.onnx.export(
            flow,
            (
                torch.tensor([[10, 20, 30]]),
                torch.full((1, 1, 32), float("nan")),
                torch.zeros(1, 32),
                flow.empty_cache(4),
            ),
            out / "flow_lm.onnx",
            input_names=["tokens", "latent", "noise", "cache"],
            output_names=["latent_out", "eos", "cache_out"],
            dynamic_axes={
                "tokens": {1: "text"},
                "latent": {1: "frames"},
                "cache": {3: "past"},
                "cache_out": {3: "total"},
            },
            opset_version=17,
            dynamo=False,
        )
        torch.onnx.export(
            mimi,
            (torch.zeros(1, 2, 32), torch.zeros(mimi.size), torch.tensor([32])),
            out / "mimi_decoder.onnx",
            input_names=["latent", "state", "offset"],
            output_names=["audio", "state_out"],
            dynamic_axes={"latent": {1: "frames"}, "audio": {1: "samples"}},
            opset_version=17,
            dynamo=False,
        )
    tokenizer = str(model.config.flow_lm.lookup_table.tokenizer_path)
    assert tokenizer.split("@")[0].endswith(".model"), "a SentencePiece tokenizer"
    shutil.copy(download_if_necessary(tokenizer), out / "tokenizer.model")
    (out / "tokenizer.model").chmod(0o644)
    meta = {
        "language": language,
        "sample_rate": model.sample_rate,
        "frame_samples": int(model.sample_rate / model.config.mimi.frame_rate),
        "mimi_steps_per_frame": int(model.mimi.encoder_frame_rate / model.mimi.frame_rate),
        "mimi_state": mimi.size,
        "temperature": model.temp,
        "eos_threshold": model.eos_threshold,
        "min_frames_before_eos": model._MIN_FRAMES_BEFORE_EOS,
        "frames_after_eos": model.model_recommended_frames_after_eos,
        "tokens_per_second": model._TOKENS_PER_SECOND_ESTIMATE,
        "padding_seconds": model._GEN_SECONDS_PADDING,
        "max_tokens": MAX_TOKEN_PER_CHUNK,
        "replace_characters": model.replace_characters,
        "remove_semicolons": model.remove_semicolons,
        "pad_with_spaces_for_short_inputs": model.pad_with_spaces_for_short_inputs,
        "append_terminal_punctuation": model.append_terminal_punctuation,
        "capitalize_first_letter": model.capitalize_first_letter,
    }
    (out / "pocket.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n")
    (out / "README.md").write_text(readme(model.config, language))
    print(json.dumps(meta, ensure_ascii=False))


def export_voice(model, recording, out):
    out.parent.mkdir(parents=True, exist_ok=True)
    state = model.get_state_for_audio_prompt(Path(recording))
    cache, gain = voice_cache(model, state), gain_db(model, state)
    with out.open("wb") as f:
        np.savez(f, cache=cache.numpy().astype(np.float32), gain_db=np.float32(gain))
    print(f"{out}: {cache.shape[3]} frames, gain {gain:+.1f} dB")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    model_parser = commands.add_parser("model", help="the graphs, the tokenizer and how to run them")
    model_parser.add_argument("config")
    model_parser.add_argument("language", help='what it reads, like "pt"')
    model_parser.add_argument("out", type=Path)
    voice_parser = commands.add_parser("voice", help="a voice cloned from a recording")
    voice_parser.add_argument("config")
    voice_parser.add_argument("recording")
    voice_parser.add_argument("out", type=Path)
    args = parser.parse_args()
    model = TTSModel.load_model(config=args.config).eval()
    if args.command == "model":
        export_model(model, args.language, args.out)
    else:
        export_voice(model, args.recording, args.out)


if __name__ == "__main__":
    main()

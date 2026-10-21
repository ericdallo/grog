# The speech models grog-voice uses by default, pinned (services.grog.voice).
{ lib, fetchurl, fetchzip, runCommand }:

let
  # Files of a Hugging Face repo at revision `rev`, laid out as there.
  huggingFaceFiles = { name, repo, rev, files }:
    runCommand name { } (lib.concatLines (lib.mapAttrsToList
      (path: hash: ''
        mkdir -p "$(dirname "$out/${path}")"
        ln -s ${fetchurl {
          url = "https://huggingface.co/${repo}/resolve/${rev}/${path}";
          inherit hash;
        }} "$out/${path}"
      '')
      files));

  # A voice from rhasspy/piper-voices at revision `rev`: its .onnx, with the
  # .onnx.json next to it, where Piper finds it.
  piperVoice = { rev, dir, name, onnxHash, configHash }:
    let
      base = "https://huggingface.co/rhasspy/piper-voices/resolve/${rev}/${dir}/${name}";
      onnx = fetchurl {
        url = "${base}.onnx";
        hash = onnxHash;
      };
      config = fetchurl {
        url = "${base}.onnx.json";
        hash = configHash;
      };
    in
    runCommand "piper-voice-${name}" { } ''
      mkdir $out
      ln -s ${onnx} $out/${name}.onnx
      ln -s ${config} $out/${name}.onnx.json
    '';
in
{
  # Speech to text: Parakeet TDT 0.6B v3 (NVIDIA, CC-BY-4.0), int8, exported
  # for sherpa-onnx. 25 European languages, told apart from the audio.
  parakeet = fetchzip {
    name = "parakeet-tdt-0.6b-v3-int8";
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2";
    hash = "sha256-3zMIAaTYBJZB8rXgaxHZdgBqBbrrLLN7USLH+JNTaDY=";
  };

  # The same in fp32, ~2.5 GB: it hears more, for twice the memory
  # (services.grog.voice.sttPrecision = "fp32").
  parakeetFp32 = huggingFaceFiles {
    name = "parakeet-tdt-0.6b-v3";
    repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3";
    rev = "1a468a35cbba69418f126de829e75261dea4a4e4";
    files = {
      "encoder.onnx" = "sha256-Pu185CS/gzmtCSM1M8aH4tvQfnTM9QJ7XnNEAZ6jc7A=";
      # The encoder's weights, which encoder.onnx finds next to it.
      "encoder.weights" = "sha256-OvP1GvXy0B279a9H1Cx5YqLCBfEQBCVLtPK5eYYvOag=";
      "decoder.onnx" = "sha256-1ZPNsOVx9aRX7CIZr5loy/aw6BmOj3g5tAqHVFk79ow=";
      "joiner.onnx" = "sha256-ubC8+IrFcZAuaaZTYiPtLZSIXpgbhQRUEPFAPVMSGmM=";
      "tokens.txt" = "sha256-1YVEZ56kvGrFY9H1Ret9R0vWz6Rn8KbiwdwcfTfjw10=";
    };
  };

  # Text to speech: Supertonic 3 (Supertone, OpenRAIL-M: see its LICENSE),
  # its graphs in fp32 and its ten voices. 31 languages.
  supertonic = huggingFaceFiles {
    name = "supertonic-3";
    repo = "Supertone/supertonic-3";
    rev = "3cadd1ee6394adea1bd021217a0e650ede09a323";
    files = {
      "onnx/duration_predictor.onnx" = "sha256-w+uRQU1f+KeiObf+njTn4r+KgUDYN1/7FHGLHGOTJds=";
      "onnx/text_encoder.onnx" = "sha256-x779XqjDEZdp6KbBSGxO3Go7yDZcZ2IciBu7d0uZAv8=";
      "onnx/vector_estimator.onnx" = "sha256-iDrIaOoCde8OmRUk3GTxazwDdu/XwyCva1P1t4DXxhw=";
      "onnx/vocoder.onnx" = "sha256-CF3nbdjo1YNtbKZoJmAfYVk5IY+Q5Rn3DuijbtKkxLo=";
      "onnx/tts.json" = "sha256-QgeNOu8c1Dq0MCHzxU9H0tdc60519ifxGIkBKLBqDQk=";
      "onnx/unicode_indexer.json" = "sha256-m/c0bkOIOoH4ZFyBIk94bUPFtX82QfbnZxp9bEk8sk8=";
      "voice_styles/F1.json" = "sha256-u97G7gAjHCx0KtBUg99TNMqztS/aO6OOagcFnEVj28I=";
      "voice_styles/F2.json" = "sha256-fHIsanJwexp38DXWfw0TUboYdzjgb3aD6McrHfNHf8Y=";
      "voice_styles/F3.json" = "sha256-EvbvJXO6ot76ESgGnLWfID46tnySr3e0Lfig46L3xqs=";
      "voice_styles/F4.json" = "sha256-wvp2TBIlp238Pixz6KpPcNnuSHk4YOs0wpX/8BwuAys=";
      "voice_styles/F5.json" = "sha256-RZZuczFkFWJs9Bp9HG87THDbwbor7lwZeO8M4zJE/I0=";
      "voice_styles/M1.json" = "sha256-41YEaH9dI2lLjpFZOpPuwOTspsCwK7jtaROasuprCls=";
      "voice_styles/M2.json" = "sha256-t2y/YrrHB8cQzwrlq6XjHuoaYzmpc0v64zq5hJlTSlA=";
      "voice_styles/M3.json" = "sha256-6hrDXMuRsNfsrVM6L70O7BDJFRPYlR47Jfu6mZVOFZs=";
      "voice_styles/M4.json" = "sha256-yo7vrU/NmJyTeQMv8+UHOK3FR+614iG4JZOm17O6wwM=";
      "voice_styles/M5.json" = "sha256-3SK5J0AxQyH4rhHF6H+N1g0GDxXdOmMrWt939HH3evI=";
      "LICENSE" = "sha256-DZRKkRD+2algLWDgQjonKQPnvSGrBgSQd078d8InXp8=";
    };
  };

  # Piper's pt_BR "faber" voice, medium quality: lighter and faster, and
  # flatter (services.grog.voice.ttsEngine = "piper").
  faber = piperVoice {
    rev = "375a0fe641dea077c2a47b4e9a056d6da521eed3";
    dir = "pt/pt_BR/faber/medium";
    name = "pt_BR-faber-medium";
    onnxHash = "sha256-hYVV46BkIJxXCI/mvXDEw9xU0D6qAMRdXsr0OjP5Wqc=";
    configHash = "sha256-fmlN4ZWuP8Nt1zLEResE+0m2SYVIk8tVBrl48NUKHW8=";
  };

  # Another one, "jeff" (trained on CC0 data): it asks grog the module test's
  # question (body/tests/module.nix), as someone other than grog.
  jeff = piperVoice {
    rev = "c10ece1aade47bb51c153c893d14e5bf8e5b7117";
    dir = "pt/pt_BR/jeff/medium";
    name = "pt_BR-jeff-medium";
    onnxHash = "sha256-Om9MRjVYE8K3u8TRa20T1g7XIHS5UqOTuqzoKn0MlLU=";
    configHash = "sha256-e/gUW1crNoBvXODx0zIrZxGXW8fQRz6NNvztSp7AAw0=";
  };
}

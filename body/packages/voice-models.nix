# The speech models grog-voice uses by default, pinned (services.grog.voice).
{ fetchurl, fetchzip, runCommand }:

let
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

  # Text to speech: Piper's pt_BR "faber" voice, medium quality.
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

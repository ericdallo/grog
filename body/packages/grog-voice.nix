{ lib
, python3Packages
, piper-tts
, pipewire
}:

let
  # Synthesis only: the default build also pulls torch & co, to train voices.
  piper = python3Packages.toPythonModule (piper-tts.override {
    withTrain = false;
    withHTTP = false;
    withAlignment = false;
  });
in
python3Packages.buildPythonApplication {
  pname = "grog-voice";
  version = "0.1.0";
  pyproject = true;

  src = lib.fileset.toSource {
    root = ../../voice;
    fileset = lib.fileset.unions [ ../../voice/pyproject.toml ../../voice/grog_voice ../../voice/tests ];
  };

  build-system = [ python3Packages.setuptools ];

  dependencies = with python3Packages; [
    numpy
    # Runs Pocket TTS and Supertonic: twice as fast as ONNX Runtime on
    # grognix's CPU.
    openvino
    piper
    pysilero-vad
    # Pocket TTS's tokenizer.
    sentencepiece
    sherpa-onnx
  ];
  # nixpkgs' openvino and sherpa-onnx install no package metadata
  # (pythonImportsCheck still checks they're there).
  pythonRemoveDeps = [ "openvino" "sherpa-onnx" ];

  # The unit tests need none of the models.
  nativeCheckInputs = [ python3Packages.unittestCheckHook ];
  unittestFlags = [ "-s" "tests" "-v" ];
  pythonImportsCheck = [ "grog_voice" "openvino" "piper" "pysilero_vad" "sentencepiece" "sherpa_onnx" ];

  makeWrapperArgs = [
    # pw-cat records and plays.
    "--prefix"
    "PATH"
    ":"
    (lib.makeBinPath [ pipewire ])
    # See grog_voice/main.py: the VAD crawls on several OpenMP threads.
    "--set-default"
    "OMP_THREAD_LIMIT"
    "1"
  ];

  # Its `piper` command synthesizes test questions (body/tests/module.nix).
  passthru = { inherit piper; };

  meta = {
    description = "grog's ears and mouth: hands-free voice turns with grog";
    license = lib.licenses.asl20;
    mainProgram = "grog-voice";
    platforms = lib.platforms.linux;
  };
}

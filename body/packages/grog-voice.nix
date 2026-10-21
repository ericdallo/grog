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
    piper
    pysilero-vad
    sherpa-onnx
  ];
  # nixpkgs' sherpa-onnx installs no package metadata (pythonImportsCheck
  # still checks it's there).
  pythonRemoveDeps = [ "sherpa-onnx" ];

  # The unit tests need none of the models.
  nativeCheckInputs = [ python3Packages.unittestCheckHook ];
  unittestFlags = [ "-s" "tests" "-v" ];
  pythonImportsCheck = [ "grog_voice" "piper" "pysilero_vad" "sherpa_onnx" ];

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

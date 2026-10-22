{ lib
, stdenvNoCC
, babashka-unwrapped
, curl
, qrencode
, rlwrap
, runtimeShell
, shellcheck
}:

stdenvNoCC.mkDerivation {
  pname = "grog";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../../brain;
    fileset = lib.fileset.unions [ ../../brain/bb.edn ../../brain/resources ../../brain/src ../../brain/test ];
  };

  # The app only uses libraries built into babashka, so the plain native
  # binary is enough (the `babashka` wrapper would pull a JDK for `bb clojure`).
  # curl: the tests pair computers as they do, with computer.sh. qrencode:
  # they check grog's QR codes against its.
  nativeCheckInputs = [ babashka-unwrapped curl qrencode shellcheck ];

  # The suite includes HTTP round-trips against fake LLM servers on loopback,
  # which the build sandbox allows.
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    shellcheck resources/grog/*.sh
    HOME="$TMPDIR" bb test
    runHook postCheck
  '';

  # computer.sh is served to other computers as it is: its #!/usr/bin/env
  # bash stays.
  dontPatchShebangs = true;

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/grog $out/bin
    cp -r bb.edn resources src $out/share/grog/
    substitute ${./grog.sh} $out/bin/grog \
      --subst-var-by shell ${runtimeShell} \
      --subst-var-by babashka ${lib.getExe babashka-unwrapped} \
      --subst-var-by rlwrap ${lib.getExe rlwrap} \
      --subst-var out
    chmod +x $out/bin/grog
    runHook postInstall
  '';

  meta = {
    description = "Home voice assistant: LLM chat server and CLI";
    license = lib.licenses.asl20;
    mainProgram = "grog";
    platforms = lib.platforms.linux;
  };
}

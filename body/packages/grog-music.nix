{ lib
, python3Packages
, fetchFromGitHub
, mpv
, yt-dlp
}:

let
  # 1.12.0, nixpkgs', breaks on YouTube Music's answers now (playing a
  # song's up next raises a KeyError, fixed in 1.12.1; top results lose their
  # artists).
  ytmusicapi = python3Packages.ytmusicapi.overridePythonAttrs (old: rec {
    version = "1.12.3";
    src = fetchFromGitHub {
      owner = "sigma67";
      repo = "ytmusicapi";
      tag = version;
      hash = "sha256-mIxtr018hE3LSJCJdD2Lr8UUOic8m2rmPwU86EJuLFk=";
    };
  });
in
python3Packages.buildPythonApplication {
  pname = "grog-music";
  version = "0.1.0";
  pyproject = true;

  src = lib.fileset.toSource {
    root = ../../music;
    fileset = lib.fileset.unions [ ../../music/pyproject.toml ../../music/grog_music ../../music/tests ];
  };

  build-system = [ python3Packages.setuptools ];

  dependencies = [ ytmusicapi ];

  # The unit tests fake mpv and YouTube Music.
  nativeCheckInputs = [ python3Packages.unittestCheckHook ];
  unittestFlags = [ "-s" "tests" "-v" ];
  pythonImportsCheck = [ "grog_music" "ytmusicapi" ];

  makeWrapperArgs = [
    # mpv plays, asking yt-dlp for YouTube's audio. nixpkgs' yt-dlp brings
    # what YouTube takes now: Deno (its JavaScript runtime, patched in) and
    # yt-dlp-ejs, its challenge solver scripts.
    "--prefix"
    "PATH"
    ":"
    (lib.makeBinPath [ mpv yt-dlp ])
  ];

  passthru = { inherit ytmusicapi; };

  meta = {
    description = "grog's music player (mpv and yt-dlp), and YouTube Music's search and library";
    license = lib.licenses.asl20;
    mainProgram = "grog-music";
    platforms = lib.platforms.linux;
  };
}

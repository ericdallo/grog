{ lib
, stdenvNoCC
, gjs
, glib
, gtk4
, webkitgtk_6_0
, gobject-introspection
, wrapGAppsHook4
, makeWrapper
, nodejs-slim
, qrencode
, inter
, jetbrains-mono
}:

let
  # The pages grog may ask to open on a phone when signing in
  # (brain/src/grog/providers/openai/oauth.clj): the skin shows their QR codes,
  # named the way skin/web/view.js (qrFile) looks for them.
  qrPages = [ "https://auth.openai.com/codex/device" ];
in
stdenvNoCC.mkDerivation {
  pname = "grog-skin";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../../skin;
    fileset = lib.fileset.unions [ ../../skin/package.json ../../skin/kiosk.js ../../skin/web ../../skin/test ];
  };

  nativeBuildInputs = [ gobject-introspection wrapGAppsHook4 makeWrapper qrencode ];
  # The window's libraries, whose typelibs the wrapper points gjs at. GNOME
  # has them already.
  buildInputs = [ gjs glib gtk4 webkitgtk_6_0 ];

  # What the page shows (view.js), without a browser.
  nativeCheckInputs = [ nodejs-slim ];
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    node --test
    runHook postCheck
  '';

  # The page and its fonts (grog serves them, see services.grog.skin), and
  # the window.
  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/grog-skin
    cp -r web kiosk.js $out/share/grog-skin/
    fonts=$out/share/grog-skin/web/fonts
    mkdir -p $fonts
    cp ${inter}/share/fonts/truetype/InterVariable.ttf $fonts/Inter.ttf
    cp ${jetbrains-mono}/share/fonts/truetype/JetBrainsMono-{Light,Regular}.ttf $fonts/
    qr=$out/share/grog-skin/web/qr
    mkdir -p $qr
    for page in ${lib.escapeShellArgs qrPages}; do
      name=$(printf '%s' "$page" | sed -E 's|^https?://||; s/[^A-Za-z0-9]+/-/g; s/^-|-$//g' | tr '[:upper:]' '[:lower:]')
      qrencode --type=SVG --svg-path --margin=2 --level=M --output="$qr/$name.svg" "$page"
    done
    runHook postInstall
  '';

  dontWrapGApps = true;
  postFixup = ''
    makeWrapper ${gjs}/bin/gjs $out/bin/grog-skin \
      "''${gappsWrapperArgs[@]}" \
      --add-flags "-m $out/share/grog-skin/kiosk.js"
  '';

  meta = {
    description = "grog's skin: a page showing what grog is up to, and a fullscreen window for it";
    license = lib.licenses.asl20;
    mainProgram = "grog-skin";
    platforms = lib.platforms.linux;
  };
}

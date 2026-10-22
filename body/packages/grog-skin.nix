{ lib
, stdenvNoCC
, gjs
, glib
, gtk4
, libgudev
, webkitgtk_6_0
, gobject-introspection
, wrapGAppsHook4
, makeWrapper
, nodejs-slim
, inter
, jetbrains-mono
}:

stdenvNoCC.mkDerivation {
  pname = "grog-skin";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../../skin;
    fileset = lib.fileset.unions [ ../../skin/package.json ../../skin/kiosk.js ../../skin/web ../../skin/test ];
  };

  nativeBuildInputs = [ gobject-introspection wrapGAppsHook4 makeWrapper ];
  # The window's libraries, whose typelibs the wrapper points gjs at. GNOME
  # has them already. libgudev: to hear when the GPU was reset.
  buildInputs = [ gjs glib gtk4 libgudev webkitgtk_6_0 ];

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

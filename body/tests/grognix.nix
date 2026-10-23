# A grognix in a VM, against a fake OpenAI: what the machine adds to grog,
# like SSH with keys only, never sleeping and logging grog in at boot for
# grog-voice, with grog's skin on the screen; and a headless one. grog and
# grog-voice themselves are covered by module.nix.
# Run: nix build .#checks.x86_64-linux.grognix -L
{ pkgs, modules }:

let
  vm = { pkgs, ... }: {
    imports = modules;

    services.grog.settings = {
      name = "Test";
      providers.openai.baseUrl = "http://127.0.0.1:9999";
    };

    # The VM has no sound card: a virtual mic, recording silence.
    services.grog.voice.settings.mic = "null-mic";
    # Lighter than Supertonic, by ~1 GB: grog-voice's voice is module.nix's
    # business.
    services.grog.voice.ttsEngine = "piper";
    services.pipewire.extraConfig.pipewire."90-null-mic"."context.objects" = [{
      factory = "adapter";
      args = {
        "factory.name" = "support.null-audio-sink";
        "node.name" = "null-mic";
        "media.class" = "Audio/Source/Virtual";
        "audio.position" = "MONO";
      };
    }];

    systemd.services.fake-llm = {
      wantedBy = [ "multi-user.target" ];
      serviceConfig.ExecStart = "${pkgs.babashka-unwrapped}/bin/bb ${./fake-llm.clj}";
    };
  };
  # A song for grog-music to play offline, for the skin to show: a quiet tone.
  song = pkgs.runCommand "song.flac" { nativeBuildInputs = [ pkgs.sox ]; } ''
    sox -n -r 44100 -c 2 -t flac $out synth 600 sine 440 vol 0.1
  '';
in
pkgs.testers.runNixOSTest {
  name = "grognix";
  # To read grog's skin off the screen.
  enableOCR = true;

  # machine.nix sets nixpkgs.hostPlatform.
  node.pkgsReadOnly = false;

  # Host names: the test driver would name them after the nodes, as a default
  # just like grognix's own.
  nodes.machine = {
    imports = [ vm ];
    networking.hostName = "grognix";
    # Room for the GNOME session and the speech models.
    virtualisation.memorySize = 4096;
    virtualisation.cores = 2;
  };

  nodes.headless = {
    imports = [ vm ];
    networking.hostName = "grognix-headless";
    grognix.desktop.enable = false;
    virtualisation.memorySize = 3072;
    virtualisation.cores = 2;
  };

  testScript = ''
    import json
    import shlex

    start_all()
    machine.wait_for_unit("multi-user.target")
    machine.wait_for_unit("fake-llm.service")
    machine.wait_for_unit("grog.service")
    machine.wait_for_open_port(9999)
    machine.wait_for_open_port(8484)

    with subtest("ssh with keys only, mDNS, and it never suspends nor locks"):
        machine.wait_for_unit("sshd.service")
        machine.succeed("grep -qx 'PasswordAuthentication no' /etc/ssh/sshd_config")
        # No password to guess: grog has none.
        status = machine.succeed("passwd -S grog").split()[1]
        assert status in ("L", "NP"), status
        machine.wait_for_unit("avahi-daemon.service")
        machine.succeed("systemd-analyze cat-config systemd/sleep.conf | grep -q '^AllowSuspend=no'")
        out = machine.succeed(
            "busctl call org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager CanSuspend"
        )
        assert '"yes"' not in out, out
        # Nor does grog's GNOME session try to (for good: the settings are
        # locked), and the power button powers off.
        power = "/org/gnome/settings-daemon/plugins/power/"
        locks = machine.succeed(f"su - grog -c 'dconf list-locks {power}'").split()
        for key, value in (
            ("sleep-inactive-ac-type", "'nothing'"),
            ("sleep-inactive-battery-type", "'nothing'"),
            ("power-button-action", "'interactive'"),
        ):
            out = machine.succeed(f"su - grog -c 'dconf read {power}{key}'").strip()
            assert out == value, f"{key}: {out}"
            assert power + key in locks, locks
        # Nor does its screen lock, for good too: grog has no password to
        # unlock it with.
        desktop = "/org/gnome/desktop/"
        locks = machine.succeed(f"su - grog -c 'dconf list-locks {desktop}'").split()
        for key, value in (("screensaver/lock-enabled", "false"), ("lockdown/disable-lock-screen", "true")):
            out = machine.succeed(f"su - grog -c 'dconf read {desktop}{key}'").strip()
            assert out == value, f"{key}: {out}"
            assert desktop + key in locks, locks

    with subtest("grog answers the login user, with the key from the secrets file"):
        machine.succeed(
            "install -D -m 600 /dev/null /etc/grog/secrets.env",
            "echo OPENAI_API_KEY=test-key > /etc/grog/secrets.env",
            "systemctl restart grog",
        )
        machine.wait_for_open_port(8484)
        out = machine.succeed("su - grog -c 'grog ask hi'").strip()
        assert out == "messages=1 key=test-key name=ok", out

    with subtest("grog runs as its own user, with the API only on localhost and no nREPL"):
        pid = machine.succeed("systemctl show -p MainPID --value grog").strip()
        service_uid = machine.succeed(f"ps -o uid= -p {pid}").strip()
        assert service_uid != machine.succeed("id -u grog").strip(), service_uid
        # Java's sockets show IPv4 binds as [::ffff:127.0.0.1].
        sockets = machine.succeed("ss -ltnH 'sport = :8484'").strip().splitlines()
        assert sockets and all("127.0.0.1" in s for s in sockets), sockets
        machine.fail("ss -ltnH 'sport = :1667' | grep -q .")

    with subtest("grog is logged in at boot, and grog-voice listens in its session"):
        machine.wait_until_succeeds("loginctl list-sessions --no-legend | grep -q 'grog *seat0'")
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-voice.service")
        machine.wait_until_succeeds("journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service | grep -qx 'INFO listening'")
        restarts = machine.succeed("systemctl --user -M grog@ show -p NRestarts --value grog-voice.service").strip()
        assert restarts == "0", restarts

    with subtest("grog's hands wait in the same session, for grog only"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-hands.service")
        machine.wait_until_succeeds("test -S /run/grog-hands/hands.sock")
        machine.succeed("stat -c '%a %U %G' /run/grog-hands/hands.sock | grep -qx '660 grog grog-hands'")

    with subtest("grog's music player waits in the same session, for grog only"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-music.service")
        machine.wait_until_succeeds("test -S /run/grog-music/music.sock")
        machine.succeed("stat -c '%a %U %G' /run/grog-music/music.sock | grep -qx '660 grog grog-music'")
        out = machine.succeed("su - grog -c 'grog music'").strip()
        assert out.startswith("Nothing is playing"), out

    with subtest("grog's skin fills the screen once logged in, over the overview, keeping it on"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-skin.service")
        session = "su - grog -c 'DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/$(id -u)/bus gdbus call --session {}'"
        machine.wait_until_succeeds(
            session.format(
                "--dest org.gnome.Shell --object-path /org/gnome/Shell"
                " --method org.freedesktop.DBus.Properties.Get org.gnome.Shell OverviewActive"
            )
            + " | grep -qx '(<false>,)'"
        )
        out = machine.succeed(
            session.format(
                "--dest org.gnome.SessionManager --object-path /org/gnome/SessionManager"
                " --method org.gnome.SessionManager.IsInhibited 8"
            )
        ).strip()
        assert out == "(true,)", out

    with subtest("grog's skin shows what grog-voice does, and a turn as it comes, with captions"):
        # The VM has no GPU: the skin is drawn on the CPU. Once it faded in
        # (see below).
        machine.sleep(3)
        machine.wait_for_text("Listening", timeout=120)
        machine.succeed("su - grog -c 'grog ask --new hi'")
        # Once the skin drew it, faded in: OCR of a frame mid-fade can take
        # tesseract many minutes.
        machine.sleep(2)
        machine.wait_for_text("key=test-key", timeout=60)

    with subtest("grog's skin shows what grog's player plays, with its controls"):
        play = {"command": "play", "items": [{"url": "file://${song}", "title": "Tone", "artist": "Sox"}]}
        command = "grog-music --socket /run/grog-music/music.sock send " + shlex.quote(json.dumps(play))
        machine.succeed(f"su - grog -c {shlex.quote(command)}")
        # grog looks at once, when asked what plays.
        machine.wait_until_succeeds("su - grog -c 'grog music' | grep -q '^Playing Tone by Sox'")
        machine.wait_for_text("Tone", timeout=60)
        # To look at, in the test's output.
        machine.screenshot("skin")

    with subtest("headless: no desktop, and grog's session starts at boot, with grog-voice listening"):
        headless.wait_for_unit("multi-user.target")
        headless.fail("systemctl is-active display-manager.service")
        headless.wait_until_succeeds("loginctl show-user grog -p Linger | grep -qx Linger=yes")
        headless.wait_until_succeeds("systemctl --user -M grog@ is-active grog-voice.service")
        headless.wait_until_succeeds("journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service | grep -qx 'INFO listening'")
        headless.wait_until_succeeds("systemctl --user -M grog@ is-active grog-music.service")

    with subtest("headless: grog's skin is served, for a screen elsewhere"):
        headless.wait_for_open_port(8484)
        # Whole, first: grep -q stops reading at the first match, failing curl.
        headless.succeed("curl -sf -o /tmp/index.html http://127.0.0.1:8484/ && grep -q skin.js /tmp/index.html")
        headless.fail("systemctl --user -M grog@ is-active grog-skin.service")
  '';
}

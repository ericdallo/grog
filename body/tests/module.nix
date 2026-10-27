# The NixOS module (services.grog) in a minimal VM, against a fake OpenAI
# (fake-llm.clj): no credentials, then an API key and a conversation through
# the CLI, signing in to the ChatGPT subscription, what they cost, the
# sandbox, grog-voice: its user service, and a voice turn with the real
# models, which whoever watches grog sees go; its skin, served; its hands: a
# shell, the volume, root, and restarting grog when asked; its music
# (grog-music, playing a local file offline): paused, resumed, turned down,
# shown to whoever watches grog, as its skin's controls ask, going through its echo canceller, out of what grog-voice records, which grog
# tells when it plays, going down while someone talks to grog, through its
# tool; what grog asks of
# grog-voice: its mic off, listening without its name; a computer pairing
# with it (pc, with services.grog.computer and no grog), running what grog
# serves, which the model then acts on, as it allows; MCP servers the user
# adds (fake-mcp.py): one from npm, run apart by grog's runner, and one over
# HTTP, whose tools the model uses, asking first for those that could
# destroy something; Home Assistant (fake-ha.py), shipped with grog, found
# on the network through avahi, signed in to on its own page or with a
# token, whose lights the model turns off; and updating grog, from a flake
# of someone else's.
# Run: nix build .#checks.x86_64-linux.module -L
{ pkgs, module }:

let
  # What updates build: alice's flake, whose grog is a repository of hers,
  # and whose system is a fake one, switching to which only says it was
  # asked to.
  systemFlake = pkgs.writeText "flake.nix" ''
    {
      inputs.grog.url = "git+file:///home/alice/grog";
      outputs = { grog, ... }: {
        nixosConfigurations.machine.config.system.build.toplevel = derivation {
          name = "fake-system";
          system = "${pkgs.stdenv.hostPlatform.system}";
          builder = "${pkgs.bash}/bin/bash";
          args = [ ./build.sh ];
          PATH = "${pkgs.coreutils}/bin";
          grog = grog.outPath;
        };
      };
    }
  '';
  buildFakeSystem = pkgs.writeText "build.sh" ''
    test ! -e "$grog/broken" || { echo "this grog is broken" >&2; exit 1; }
    mkdir -p "$out/bin"
    echo "$grog" > "$out/grog"
    cat > "$out/bin/switch-to-configuration" <<'EOF'
    #!/bin/sh
    echo "$1" >> /tmp/switched
    EOF
    chmod +x "$out/bin/switch-to-configuration"
  '';
  # What grog's runner runs for npm:fake-mcp@1.0.0: a fake MCP server
  # (fake-mcp.py), as if npx had fetched it.
  fakeNpx = pkgs.writeShellScriptBin "npx" ''
    exec ${pkgs.python3}/bin/python3 ${./fake-mcp.py} "$@"
  '';
  voice = pkgs.callPackage ../packages/grog-voice.nix { };
  models = pkgs.callPackage ../packages/voice-models.nix { };
  # A spoken question, starting with grog's name (the wake word), in a voice
  # other than grog's.
  question = pkgs.runCommand "question.wav" { nativeBuildInputs = [ voice.piper pkgs.sox ]; } ''
    echo 'Test, qual é a previsão do tempo para amanhã em São Paulo?' \
      | piper -m ${models.jeff}/pt_BR-jeff-medium.onnx -f question.wav
    sox question.wav -r 16000 -c 1 -b 16 $out
  '';
  # A song for grog-music to play offline: two minutes of a quiet tone.
  song = pkgs.runCommand "song.flac" { nativeBuildInputs = [ pkgs.sox ]; } ''
    sox -n -r 44100 -c 2 -t flac $out synth 120 sine 440 vol 0.1
  '';
in
pkgs.testers.runNixOSTest {
  name = "grog-module";

  nodes.machine = { pkgs, lib, ... }: {
    imports = [ module ];

    services.grog = {
      enable = true;
      settings = {
        name = "Test";
        providers.openai = {
          baseUrl = "http://127.0.0.1:9999";
          subscription = {
            baseUrl = "http://127.0.0.1:9999/codex";
            authUrl = "http://127.0.0.1:9999";
          };
        };
      };
    };

    services.grog.user = "grog";
    # Recording from grog-mic: the virtual mic below, through the echo
    # canceller its music plays into (services.grog.music.echoCancel).
    services.grog.voice.enable = true;
    services.grog.hands.enable = true;
    services.grog.music.enable = true;
    # Served only: no one's desktop shows it.
    services.grog.skin = {
      enable = true;
      user = null;
    };
    # The VM has no sound card: a virtual mic, recording silence, and two
    # virtual speakers, playing to nowhere, the first one the default.
    services.pipewire = {
      enable = true;
      extraConfig.pipewire."90-null-devices"."context.objects" = [
        {
          factory = "adapter";
          args = {
            "factory.name" = "support.null-audio-sink";
            "node.name" = "null-mic";
            "media.class" = "Audio/Source/Virtual";
            "audio.position" = "MONO";
          };
        }
        {
          factory = "adapter";
          args = {
            "factory.name" = "support.null-audio-sink";
            "node.name" = "null-speaker";
            "node.description" = "Speaker";
            "media.class" = "Audio/Sink";
            "audio.position" = "FL,FR";
            "priority.session" = 1000;
          };
        }
        {
          factory = "adapter";
          args = {
            "factory.name" = "support.null-audio-sink";
            "node.name" = "null-other";
            "node.description" = "Other speaker";
            "media.class" = "Audio/Sink";
            "audio.position" = "FL,FR";
            "priority.session" = 500;
          };
        }
      ];
    };
    # The speech models, twice: the service and `grog-voice turn`, Pocket TTS
    # taking ~3 GB each, and their partials' Parakeet ~1 GB.
    virtualisation.memorySize = 12288;
    virtualisation.cores = 2;

    # A login user named like the service, which the service mustn't run as.
    # No one logs in: its systemd (PipeWire, grog-voice) starts at boot.
    users.users.grog = {
      isNormalUser = true;
      linger = true;
    };

    systemd.services.fake-llm = {
      wantedBy = [ "multi-user.target" ];
      serviceConfig.ExecStart = "${pkgs.babashka-unwrapped}/bin/bb ${./fake-llm.clj}";
    };

    # MCP servers: from npm, through grog's runner (its npx a fake one), and
    # one over HTTP, on this machine.
    services.grog.mcp = {
      enable = true;
      packages = [ fakeNpx ];
    };
    systemd.services.fake-mcp = {
      wantedBy = [ "multi-user.target" ];
      environment.HOME = "/var/lib/fake-mcp";
      serviceConfig = {
        ExecStart = "${pkgs.python3}/bin/python3 ${./fake-mcp.py} --http 8700";
        StateDirectory = "fake-mcp";
      };
    };

    # Home Assistant (fake-ha.py), on this machine, which avahi says is on
    # the network, as Home Assistant's zeroconf does.
    systemd.services.fake-ha = {
      wantedBy = [ "multi-user.target" ];
      serviceConfig = {
        ExecStart = "${pkgs.python3}/bin/python3 ${./fake-ha.py} 8123 /var/lib/fake-ha";
        StateDirectory = "fake-ha";
      };
    };
    services.avahi = {
      enable = true;
      publish = {
        enable = true;
        addresses = true;
      };
      extraServiceFiles.home-assistant = ''
        <?xml version="1.0" standalone="no"?>
        <!DOCTYPE service-group SYSTEM "avahi-service.dtd">
        <service-group>
          <name>Casa</name>
          <service>
            <type>_home-assistant._tcp</type>
            <port>8123</port>
            <txt-record>location_name=Casa</txt-record>
            <txt-record>internal_url=http://127.0.0.1:8123</txt-record>
            <txt-record>uuid=0123456789abcdef0123456789abcdef</txt-record>
            <txt-record>version=2026.10.0</txt-record>
          </service>
        </service-group>
      '';
    };

    # Updates, from alice's flake (systemFlake), like a machine whose system
    # is in someone's own repository.
    services.grog.update = {
      enable = true;
      flake = "/home/alice/nixos";
      host = "machine";
    };
    # Without its revision, an update goes by what the flake locked, and the
    # new grog only has to answer.
    services.grog.revision = lib.mkForce null;
    users.users.alice.isNormalUser = true;
    environment.systemPackages = [ pkgs.git ];
    nix.settings = {
      experimental-features = [ "nix-command" "flakes" ];
      # The fake system's builder comes from the store as it is, offline.
      sandbox = false;
      substituters = lib.mkForce [ ];
      flake-registry = "";
    };
  };

  # A computer of bob's, which grog acts on once paired with it.
  nodes.pc = {
    imports = [ ../modules/computer.nix ];
    services.grog.computer = {
      enable = true;
      user = "bob";
    };
    users.users.bob.isNormalUser = true;
  };

  testScript = ''
    import json
    import re
    import shlex
    from urllib.parse import parse_qs, urlsplit

    machine.wait_for_unit("multi-user.target")
    machine.wait_for_unit("fake-llm.service")
    machine.wait_for_unit("grog.service")
    machine.wait_for_open_port(9999)
    machine.wait_for_open_port(8484)


    def grog(args):
        return machine.succeed(f"su - grog -c 'grog {args}'").strip()


    with subtest("without credentials, the answer says what's missing"):
        out = machine.fail("su - grog -c 'grog ask hi'")
        assert "OPENAI_API_KEY" in out, out

    with subtest("with the secrets file, it answers using the key and the configured name"):
        machine.succeed(
            "install -D -m 600 /dev/null /etc/grog/secrets.env",
            "echo OPENAI_API_KEY=test-key > /etc/grog/secrets.env",
            "systemctl restart grog",
        )
        machine.wait_for_open_port(8484)
        assert (out := grog("ask hi")) == "messages=1 key=test-key name=ok", out

    with subtest("what the API key spent: the tokens, at the model's price"):
        status = grog("status")
        assert re.search(r"(?m)^Spent: +\$0\.\d{4} this month, \$0\.\d{4} today$", status), status
        machine.succeed("stat -c %a /var/lib/grog/usage.json | grep -qx 600")

    with subtest("remembers the conversation, until --new"):
        assert (out := grog("ask and now")) == "messages=3 key=test-key name=ok", out
        assert (out := grog("ask --new hi")) == "messages=1 key=test-key name=ok", out

    with subtest("signs in to the ChatGPT subscription, with a code entered elsewhere"):
        machine.succeed("(su - grog -c 'grog login' > /tmp/login.log 2>&1 &)")
        machine.wait_until_succeeds("grep -q 'TEST-CODE' /tmp/login.log")
        login_output = machine.succeed("cat /tmp/login.log")
        assert "open\n\n  http://127.0.0.1:9999/codex/device\n" in login_output, login_output
        assert "\n  TEST-CODE\n" in login_output, login_output
        # What someone does on their phone.
        machine.succeed("curl -sf -X POST http://127.0.0.1:9999/fake/enter-code")
        machine.wait_until_succeeds("grep -q 'Signed in as grog@example.com' /tmp/login.log")
        # Its connection, and its tokens apart, only grog can read.
        machine.succeed("stat -c %a /var/lib/grog/integrations.json | grep -qx 600")
        machine.succeed("stat -c %a /var/lib/grog/secrets | grep -qx 700")
        machine.succeed("stat -c %a /var/lib/grog/secrets/openai.json | grep -qx 600")
        assert "test-access-token" not in machine.succeed("cat /var/lib/grog/integrations.json")

    with subtest("the sign-in survives a restart"):
        # Conversations don't: they live in memory.
        machine.succeed("systemctl restart grog")
        machine.wait_for_open_port(8484)
        assert "subscription, signed in as grog@example.com" in grog("status")

    with subtest("the subscription takes over the API key, with web search"):
        out = grog("ask hi")
        assert out == "messages=1 key=test-access-token name=ok account=acct-test search=yes", out
        assert "* gpt-6-luna" in (models := grog("models")), models

    with subtest("how much of the subscription's limits is used, and when they reset"):
        status = grog("status")
        assert re.search(
            r"(?m)^Usage: +5h 12% used \(resets in 2h \d+m\), 7d 40% used \(resets in 3d \d+h\)$", status
        ), status

    with subtest("signing out goes back to the API key"):
        grog("logout")
        assert (out := grog("ask hi")) == "messages=3 key=test-key name=ok", out

    with subtest("or signs in with a browser, coming back to this machine"):
        machine.succeed("(su - grog -c 'grog login --browser' > /tmp/login-browser.log 2>&1 &)")
        machine.wait_until_succeeds("grep -q 'state=' /tmp/login-browser.log")
        login_output = machine.succeed("cat /tmp/login-browser.log")
        assert "Sign in with your ChatGPT account at:" in login_output, login_output
        match = re.search(r"state=([A-Za-z0-9_-]+)", login_output)
        assert match, login_output
        state = match.group(1)
        # What the browser does after signing in.
        machine.succeed(
            f"curl -sf 'http://127.0.0.1:1455/auth/callback?code=test-code&state={state}' | grep -q 'Signed in to Test'"
        )
        machine.wait_until_succeeds("grep -q 'Signed in as grog@example.com' /tmp/login-browser.log")
        machine.wait_until_fails("ss -ltnH 'sport = :1455' | grep -q .")
        grog("logout")

    with subtest("web pages can't use it"):
        status = machine.succeed(
            "curl -s -o /dev/null -w '%{http_code}' -H 'Host: evil.example' http://127.0.0.1:8484/v1/status"
        )
        assert status == "403", status
        status = machine.succeed(
            "curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: text/plain'"
            " -d '{\"message\": \"hi\"}' http://127.0.0.1:8484/v1/chat"
        )
        assert status == "415", status

    with subtest("sandboxed: its own user, the HTTP API only on localhost"):
        machine.succeed("systemctl show -p DynamicUser grog | grep -q yes")
        pid = machine.succeed("systemctl show -p MainPID --value grog").strip()
        service_uid = machine.succeed(f"ps -o uid= -p {pid}").strip()
        assert service_uid != machine.succeed("id -u grog").strip(), service_uid
        # Java's sockets show IPv4 binds as [::ffff:127.0.0.1].
        sockets = machine.succeed("ss -ltnH 'sport = :8484'").strip().splitlines()
        assert sockets and all("127.0.0.1" in s for s in sockets), sockets

    def voice_journal():
        return machine.succeed("journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service")

    with subtest("grog-voice listens in grog's session"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-voice.service")
        machine.wait_until_succeeds("journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service | grep -qx 'INFO listening'")

    with subtest("its skin is served at its address, and nothing else"):
        page = machine.succeed("curl -sf http://127.0.0.1:8484/")
        assert "skin.js" in page, page
        machine.succeed("curl -sf -o /dev/null http://127.0.0.1:8484/fonts/Inter.ttf")
        status = machine.succeed(
            "curl -s --path-as-is -o /dev/null -w '%{http_code}' http://127.0.0.1:8484/../../etc/passwd"
        )
        assert status == "404", status

    with subtest("a voice turn: the wake word, speech to text, grog searching the web, text to speech"):
        # Whoever watches grog sees it go.
        machine.succeed("(curl -sN http://127.0.0.1:8484/v1/events > /tmp/events.log 2>&1 &)")
        out = machine.succeed("su - grog -c 'grog-voice turn ${question} --no-play'")
        # Its name, from grog: what's after it is the question. Parakeet hears
        # "amanhã de São Paulo" in the endpointer's cut of it, "em" in the
        # whole file.
        assert re.search(r"(?m)^asked: qual é a previsão do tempo para amanhã (em|de) são paulo", out.lower()), out
        # A new conversation ("voice"), answered with the API key: first what it's
        # about to do, then the answer, without the source it cited.
        assert "answer: Vou ver a previsão. messages=1 key=test-key name=ok\n" in out, out
        assert "tempo.com" not in out, out
        assert "sounds: ack\n" in out, out
        # In the order they came: got it may come before the whole is transcribed.
        timings = re.search(r"since it ended: ([^;\n]*)", out)
        assert timings and all(
            re.search(rf"{mark} [\d.]+ s", timings[1]) for mark in ("got it", "transcript", "first words", "speaking")
        ), out
        assert "tools: web_search" in out, out

    def in_order(seen, expected):
        """Whether `expected` shows up in `seen`, in that order."""
        rest = iter(seen)
        return all(any(s == e for s in rest) for e in expected)

    with subtest("whoever watched saw it heard, the turn's steps and tools, but nothing said"):
        watched = machine.succeed("cat /tmp/events.log")
        data = [json.loads(line[len("data: "):]) for line in watched.splitlines() if line.startswith("data: ")]
        voice = [d["event"]["state"] for d in data if "state" in d.get("event", {})]
        assert in_order(voice, ["hearing", "answering", "speaking", "listening"]), voice
        state = data[-1]["state"]
        steps = [s["step"] for s in state["turn"]["steps"]]
        assert in_order(steps, ["heard", "thinking", "tool", "answering", "speaking"]), steps
        tools = [p["tool"] for p in state["turn"]["parts"] if "tool" in p]
        assert tools == ["web_search"], state["turn"]
        # Captions are off: grog says what it does, not what's said.
        assert "previsão" not in watched and "messages=" not in watched, watched

    with subtest("grog-voice kept listening, saying nothing of what it heard"):
        restarts = machine.succeed("systemctl --user -M grog@ show -p NRestarts --value grog-voice.service").strip()
        assert restarts == "0", restarts
        journal = voice_journal()
        assert "previsão" not in journal and "messages=" not in journal, journal

    with subtest("asked to, it changes its own settings, for good, until reset"):
        # The model calls the settings tool, then answers.
        assert (out := grog('ask --new "de agora em diante, seu nome é Jarvis"')) == "Pronto.", out
        status = grog("status")
        assert re.search(r"(?m)^Name: +Jarvis$", status) and re.search(r"(?m)^Changed: +name ", status), status
        machine.succeed("stat -c %a /var/lib/grog/settings.json | grep -qx 600")
        machine.succeed("systemctl restart grog")
        machine.wait_for_open_port(8484)
        assert re.search(r"(?m)^Name: +Jarvis$", status := grog("status")), status
        grog("settings reset")
        status = grog("status")
        assert re.search(r"(?m)^Name: +Test$", status) and "Changed" not in status, status

    with subtest("its hands run a shell command right away, as the speaker's user"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-hands.service")
        machine.wait_until_succeeds("test -S /run/grog-hands/hands.sock")
        machine.succeed("stat -c '%a %U %G' /run/grog-hands/hands.sock | grep -qx '660 grog grog-hands'")
        out = grog('ask --new "quem roda os comandos?"')
        assert out.startswith("Feito: ") and '"output":"grog\\n"' in out, out
        machine.succeed("grep -qx grog /tmp/hands-ran")

    with subtest("and change the speaker's volume"):
        assert (out := grog('ask --new "volume em 30"')) == 'Feito: {"level":30,"muted":false}', out
        out = machine.succeed("su - grog -c 'XDG_RUNTIME_DIR=/run/user/$(id -u) wpctl get-volume @DEFAULT_AUDIO_SINK@'")
        assert out.strip() == "Volume: 0.30", out

    def ask(message, new=False):
        """What grog answers `message`, in the conversation going on unless `new`."""
        command = "grog ask " + ("--new " if new else "") + shlex.quote(message)
        return machine.succeed(f"su - grog -c {shlex.quote(command)}").strip()

    def player(request):
        """What grog-music answers `request`, asked straight, as grog's user."""
        command = "grog-music --socket /run/grog-music/music.sock send " + shlex.quote(json.dumps(request))
        return json.loads(machine.succeed(f"su - grog -c {shlex.quote(command)}"))

    def player_is(check):
        """A command succeeding when grog-music's status passes `check`, for wait_until_succeeds."""
        def go(_):
            return check(player({"command": "status"}))
        return go

    def position(text):
        """Where the song is, in seconds, in what `grog music` says."""
        found = re.search(r"\((\d+):(\d\d) of 2:00\)", text)
        assert found, text
        return int(found.group(1)) * 60 + int(found.group(2))

    with subtest("grog plays music through its player, in its user's session"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-music.service")
        machine.wait_until_succeeds("test -S /run/grog-music/music.sock")
        machine.succeed("stat -c '%a %G' /run/grog-music/music.sock | grep -qx '660 grog-music'")
        machine.fail("su - alice -c 'ls /run/grog-music'")
        listed = grog("integrations")
        assert re.search(r"(?m)^YouTube Music \(grog connect youtube-music\)\n  nothing connected$", listed), listed
        assert (out := grog("music")).startswith("Nothing is playing"), out

    with subtest("a song plays, and grog pauses, resumes and turns it down"):
        reply = player({"command": "play", "items": [{"url": "file://${song}", "title": "Tone", "artist": "Sox"}]})
        assert reply["ok"], reply
        retry(player_is(lambda s: s["state"] == "playing" and s.get("positionS", 0) >= 1))
        assert (out := grog("music")).startswith("Playing Tone by Sox ("), out
        before = position(out)
        machine.sleep(3)
        assert position(after := grog("music")) > before, (out, after)
        assert (out := grog("music pause")) == "Paused.", out
        assert (out := grog("music")).startswith("Paused: Tone by Sox"), out
        assert player({"command": "status"})["state"] == "paused"
        assert (out := grog("music resume")).startswith("Playing Tone by Sox"), out
        assert (out := grog("music volume 40")) == "Volume 40.", out
        assert (out := grog("music")).startswith("Playing Tone") and out.endswith(", volume 40."), out
        assert (s := player({"command": "status"}))["volume"] == 40 and not s["ducked"], s

    def seen_state():
        """What whoever watches grog gets first: its state's snapshot."""
        out = machine.succeed("curl -sN --max-time 2 http://127.0.0.1:8484/v1/events || true")
        return json.loads(out.split("data: ", 1)[1].splitlines()[0])["state"]

    def music_shown(check):
        """A command succeeding once the music in what's watched passes `check`, for retry."""
        def go(_):
            return check(seen_state().get("music") or {})
        return go

    with subtest("whoever watches grog sees what it plays, for its screen to control it"):
        retry(music_shown(lambda m: m.get("state") == "playing"))
        m = seen_state()["music"]
        assert (m["title"], m["artist"], m["volume"], m["provider"]) == ("Tone", "Sox", 40, "youtube-music"), m
        assert round(m["durationS"]) == 120 and m["positionS"] > 0 and m["at"] > 0, m
        assert (out := grog("music pause")) == "Paused.", out
        retry(music_shown(lambda m: m.get("state") == "paused" and m.get("title") == "Tone"))
        # As the skin's buttons ask.
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{\"action\": \"resume\"}'"
            " http://127.0.0.1:8484/v1/music"
        )
        retry(music_shown(lambda m: m.get("state") == "playing"))
        # The user's playlists need their account.
        status = machine.succeed(
            "curl -s -o /tmp/library.json -w '%{http_code}' 'http://127.0.0.1:8484/v1/music/library?kind=playlists'"
        )
        library = json.loads(machine.succeed("cat /tmp/library.json"))
        assert status == "409" and library == {
            "error": "connect YouTube Music to see your playlists",
            "connect": "youtube-music",
        }, (status, library)

    def pipewire():
        """PipeWire's nodes in grog's session ({id: props}), its links ([(from props,
        to props)]), and its default nodes ({"default.audio.sink": name...})."""
        dump = json.loads(machine.succeed("su - grog -c 'XDG_RUNTIME_DIR=/run/user/$(id -u) pw-dump'"))
        nodes = {o["id"]: o["info"]["props"] for o in dump if o["type"] == "PipeWire:Interface:Node"}
        links = [
            (nodes.get(o["info"]["output-node-id"], {}), nodes.get(o["info"]["input-node-id"], {}))
            for o in dump
            if o["type"] == "PipeWire:Interface:Link"
        ]
        defaults = {
            m["key"]: m["value"].get("name")
            for o in dump
            if o["type"] == "PipeWire:Interface:Metadata" and o["props"].get("metadata.name") == "default"
            for m in o.get("metadata", [])
            if isinstance(m.get("value"), dict)
        }
        return nodes, links, defaults

    def is_pw_cat(props):
        return "pw-cat" in (props.get("application.process.binary", ""), props.get("application.name", ""))

    def linked(check):
        """A command succeeding once some link of grog's session passes `check` (its
        from and to props), for retry."""
        def go(_):
            return any(check(a, b) for a, b in pipewire()[1])
        return go

    with subtest("its music goes through its echo canceller, out of what grog-voice records"):
        machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-echo-cancel.service")
        nodes, _, defaults = pipewire()
        classes = {p.get("node.name"): p.get("media.class") for p in nodes.values()}
        assert classes.get("grog-mic") == "Audio/Source", classes
        assert classes.get("grog-music-out") == "Audio/Sink", classes
        # Never the defaults: grog's own voice and earcons go straight to the speaker.
        assert defaults.get("default.audio.sink") == "null-speaker", defaults
        assert defaults.get("default.audio.source") == "null-mic", defaults
        # The music into the canceller, the canceller on to the speaker.
        retry(linked(lambda a, b: a.get("node.name") == "grog-music" and b.get("node.name") == "grog-music-out"))
        retry(linked(lambda a, b: a.get("node.name") == "grog-echo-cancel-playback" and b.get("node.name") == "null-speaker"))
        # grog-voice records from the mic without the music, which comes from the mic.
        machine.succeed("pgrep -u grog -af 'pw-cat --record' | grep -q -- '--target grog-mic'")
        retry(linked(lambda a, b: a.get("node.name") == "grog-mic" and is_pw_cat(b)))
        retry(linked(lambda a, b: a.get("node.name") == "null-mic" and b.get("node.name") == "grog-echo-cancel-capture"))

    def voice_told():
        """What grog tells grog-voice first: the snapshot of what it asks of it."""
        out = machine.succeed("curl -sN --max-time 2 http://127.0.0.1:8484/v1/voice/commands || true")
        return json.loads(out.split("data: ", 1)[1].splitlines()[0])

    def music_told(playing):
        """A command succeeding once grog told grog-voice whether music plays, for retry."""
        def go(_):
            return voice_told().get("music") is playing
        return go

    with subtest("grog-voice is told whether the music plays"):
        retry(music_told(True))
        assert (out := grog("music pause")) == "Paused.", out
        retry(music_told(False))
        assert (out := grog("music resume")).startswith("Playing Tone by Sox"), out
        retry(music_told(True))

    with subtest("while someone talks to grog the music goes down, then back"):
        # Touching its face: grog-voice listens without its name for a while
        # (awake), then waits for its name again.
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/actions/listen"
        )
        retry(player_is(lambda s: s["ducked"]))
        assert (s := player({"command": "status"}))["volume"] == 40 and s["state"] == "playing", s
        retry(player_is(lambda s: not s["ducked"]))
        assert (s := player({"command": "status"}))["volume"] == 40 and s["state"] == "playing", s

    with subtest("touching grog's face pauses the music"):
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/actions/stop"
        )
        retry(player_is(lambda s: s["state"] == "paused"))

    with subtest("the model plays music with its tool"):
        out = ask('use tool music {"action": "resume"}', new=True)
        assert out.startswith("Feito: Playing Tone by Sox"), out
        assert player({"command": "status"})["state"] == "playing"
        assert (out := ask('use tool music {"action": "pause"}', new=True)) == "Feito: Paused.", out
        out = ask('use tool music {"action": "status"}', new=True)
        assert out.startswith("Feito: Paused: Tone by Sox (") and out.endswith(", volume 40."), out
        assert (out := grog("music stop")) == "Stopped.", out
        assert player({"command": "status"})["state"] == "idle"

    def speakers(body):
        """What grog answers its screen asking for its speakers with `body`."""
        return json.loads(machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d " + shlex.quote(json.dumps(body))
            + " http://127.0.0.1:8484/v1/actions/speakers"
        ))

    def volume_of(name):
        """The volume of the speaker called `name`, as wpctl says it."""
        sink = next(i for i, p in pipewire()[0].items() if p.get("node.name") == name)
        return machine.succeed(f"su - grog -c 'XDG_RUNTIME_DIR=/run/user/$(id -u) wpctl get-volume {sink}'").strip()

    with subtest("its screen picks the speakers of the music and of its voice, kept"):
        listed = speakers({})
        assert [(s["name"], s["label"], s["default"]) for s in listed["speakers"]] == [
            ("null-speaker", "Speaker", True),
            ("null-other", "Other speaker", False),
        ], listed
        assert listed["voice"] is None, listed
        # Its voice on the other one, and the volume that one's; its kind kept,
        # for its screen to know its own speakers.
        other = {"name": "null-other", "label": "Other speaker", "kind": "speaker"}
        assert (picked := speakers({"voice": "null-other"})["voice"]) == other, picked
        assert voice_told()["speaker"] == "null-other"
        machine.wait_until_succeeds(
            "journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service | grep -qx 'INFO speaking on null-other'"
        )
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{\"level\": 25}'"
            " http://127.0.0.1:8484/v1/actions/volume"
        )
        assert (volume_of("null-other"), volume_of("null-speaker")) == ("Volume: 0.25", "Volume: 0.30")
        # The music on the other one: the session's default, the echo canceller playing on it.
        moved = speakers({"music": "null-other"})
        assert [s["name"] for s in moved["speakers"] if s["default"]] == ["null-other"], moved
        assert pipewire()[2].get("default.configured.audio.sink") == "null-other"
        retry(linked(lambda a, b: a.get("node.name") == "grog-echo-cancel-playback" and b.get("node.name") == "null-other"))
        # Its voice's, kept across a restart.
        machine.succeed("systemctl restart grog")
        machine.wait_for_open_port(8484)
        assert voice_told()["speaker"] == "null-other"
        assert (seen := seen_state()["speaker"]) == other, seen
        # Back as they were.
        speakers({"music": "null-speaker"})
        assert speakers({"voice": None})["voice"] is None
        assert voice_told()["speaker"] is None

    with subtest("its hands' user may do anything: sudo without a password, and what polkit guards"):
        def like_hands(command):
            """`command` run as a service of grog's, like its hands: outside any session."""
            return machine.succeed(
                f"su - grog -c 'XDG_RUNTIME_DIR=/run/user/$(id -u) systemd-run --user --wait --pipe --quiet {command}'"
            ).strip()

        like_hands("sudo -n true")
        for can in ("CanReboot", "CanPowerOff"):
            out = like_hands(f"busctl call org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager {can}")
            assert out == 's "yes"', out

    with subtest("asked to, it restarts itself and its voice, once it answered"):
        def invocation(unit, user=False):
            systemctl = "systemctl --user -M grog@" if user else "systemctl"
            return machine.succeed(f"{systemctl} show -p InvocationID --value {unit}").strip()

        snapshot = machine.succeed("curl -sN --max-time 2 http://127.0.0.1:8484/v1/events || true")
        state = json.loads(snapshot.split("data: ", 1)[1].splitlines()[0])["state"]
        assert {"restart", "reboot", "power-off"} <= set(state["actions"]), state["actions"]
        brain, ears = invocation("grog"), invocation("grog-voice", user=True)
        assert (out := grog('ask --new "reinicie-se"')) == "Feito: it happens once your answer was said", out
        machine.wait_until_succeeds(f"test \"$(systemctl show -p InvocationID --value grog)\" != {brain}")
        machine.wait_until_succeeds(
            f"test \"$(systemctl --user -M grog@ show -p InvocationID --value grog-voice)\" != {ears}"
        )
        machine.wait_for_unit("grog.service")
        machine.wait_for_open_port(8484)
        # Asked for: not a failure.
        assert (result := machine.succeed("systemctl show -p Result --value grog").strip()) == "success", result
        machine.wait_until_succeeds(
            "test $(journalctl -o cat _SYSTEMD_USER_UNIT=grog-voice.service | grep -cx 'INFO listening') = 2"
        )

    def recording():
        return "pgrep -u grog -f 'pw-cat --record'"

    with subtest("asked, grog-voice switches its mic off, recording nothing, and listens without its name"):
        machine.wait_for_open_port(8484)
        machine.wait_until_succeeds(recording())
        machine.succeed("(curl -sN http://127.0.0.1:8484/v1/events > /tmp/events-asked.log 2>&1 &)")
        assert "The mic is off" in grog("mic off")
        machine.wait_until_fails(recording())
        machine.wait_until_succeeds("grep -q '\"state\":\"muted\"' /tmp/events-asked.log")
        machine.succeed("stat -c %a /var/lib/grog/voice.json | grep -qx 600")
        # Touching its face: its mic goes on, and it listens without its name.
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/actions/listen"
        )
        machine.wait_until_succeeds(recording())
        machine.wait_until_succeeds("grep -q '\"state\":\"awake\"' /tmp/events-asked.log")

    def session():
        return json.loads(machine.succeed("curl -sf http://127.0.0.1:8484/v1/connect"))

    with subtest("a computer pairs with grog, running what it serves: the same code on both, allowed at grog"):
        pc.wait_for_unit("sshd.service")
        pc.fail("command -v grog")
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{\"integration\": \"computer\"}'"
            " http://127.0.0.1:8484/v1/actions/connect"
        )
        machine.wait_for_open_port(8485)
        assert "curl -fsS machine:8485 | bash" in pc.succeed("curl -fsS -H 'Accept: text/html' machine:8485")
        # Its page, behind a token only grog's screen and CLI show.
        token = session()["link"].rsplit("/", 1)[1]
        page = json.loads(pc.succeed(f"curl -fsS machine:8485/c/{token}/state"))
        assert page["step"]["kind"] == "script", page
        pc.fail("curl -fsS machine:8485/c/ABCDEFGHJK/state")
        pc.succeed("(su - bob -c 'curl -fsS machine:8485 | bash' > /tmp/pair.log 2>&1 &)")
        pc.wait_until_succeeds("grep -q 'check the code is' /tmp/pair.log")
        code = re.search(r"\n  (\d{3} \d{3})\n", pc.succeed("cat /tmp/pair.log"))
        assert code, pc.succeed("cat /tmp/pair.log")
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"kind\":\"code-compare\"'")
        step = session()["step"]
        assert (step["name"], step["user"]) == ("pc", "bob"), step
        assert step["code"] == code.group(1).replace(" ", ""), (step, code.group(1))
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/connect/allow"
        )
        pc.wait_until_succeeds("grep -q 'Paired: grog can act on this computer now' /tmp/pair.log")
        keys = pc.succeed("cat /home/bob/.ssh/authorized_keys")
        assert re.match(
            r'restrict,from="[\d.]+\.0/24",command="/home/bob/\.local/share/grog/computer\.sh hands" ssh-ed25519 ', keys
        ), keys
        assert re.search(r"(?m)^  computer:pc +bob@", out := grog("integrations")), out
        # Only while connecting something.
        machine.wait_until_fails("ss -ltnH 'sport = :8485' | grep -q .")

    with subtest("the model acts on it as it allows: how it's doing, not a command nobody allowed"):
        out = grog('ask --new "como está o pc?"')
        assert out.startswith("Feito: ") and "name: pc" in out, out
        out = grog('ask --new "rode no pc o id"')
        assert "nobody is there to allow it on pc" in out, out

    with subtest("forgotten, the computer stops letting grog in"):
        assert "it doesn't let grog in anymore" in (out := grog("disconnect computer:pc")), out
        pc.fail("grep -q ssh-ed25519 /home/bob/.ssh/authorized_keys")
        assert "computer:pc" not in (out := grog("integrations")), out

    def uid_of(unit):
        pid = machine.succeed(f"systemctl show -p MainPID --value {unit}").strip()
        return machine.succeed(f"ps -o uid= -p {pid}").strip()

    def ran(call):
        """Whether the npm server ran `call` (its tool and arguments, as it notes them)."""
        return f"grep -qxF {shlex.quote(call)} /var/lib/private/grog-mcp/calls.log"

    # The npm server, as the runner runs it (not this shell, whose command has it too).
    npm_server = "pgrep -u grog-mcpd -f '[f]ake-mcp.py --yes fake-mcp@1.0.0'"

    with subtest("grog's runner runs MCP servers apart, its socket only for grog"):
        machine.wait_for_unit("grog-mcp.service")
        machine.wait_until_succeeds("test -S /run/grog-mcp/mcp.sock")
        machine.succeed("stat -c '%a %G' /run/grog-mcp/mcp.sock | grep -qx '660 grog-mcp'")
        machine.succeed("stat -c '%a %G' /run/grog-mcp | grep -qx '750 grog-mcp'")
        machine.fail("su - alice -c 'ls /run/grog-mcp'")
        machine.succeed("systemctl show -p DynamicUser grog-mcp | grep -q yes")
        assert uid_of("grog-mcp") != uid_of("grog")

    with subtest("an MCP server from npm is added and connected, and its tools reach the model"):
        out = grog("integrations add npm:fake-mcp@1.0.0")
        assert "Added fake-mcp: connect it with grog connect fake-mcp" in out, out
        assert "Connected: fake." in (out := grog("connect fake-mcp")), out
        listed = grog("integrations")
        assert re.search(r"(?m)^fake-mcp \(grog connect fake-mcp\): added by you$", listed), listed
        assert re.search(r"(?m)^  fake-mcp +fake, 3 tools(, since |$)", listed), listed
        tools = grog("tools fake-mcp")
        assert re.search(r"(?m)^ *lamp_on +allow \(default\) +shown +Turn a lamp on$", tools), tools
        assert re.search(r"(?m)^ *alarm_off +ask \(default\) +shown$", tools), tools
        assert re.search(r"(?m)^ *whoami +allow \(default\) +shown$", tools), tools
        # The fake model lists the tools it's offered when it isn't offered one.
        offered = ask("use tool fake-mcp__nothing {}", new=True).split()
        assert {"fake-mcp__lamp_on", "fake-mcp__alarm_off", "fake-mcp__whoami"} <= set(offered), offered

    with subtest("the model uses its tools, through the runner"):
        out = ask('use tool fake-mcp__lamp_on {"room": "kitchen"}', new=True)
        assert out == "Feito: the lamp is on in kitchen", out
        machine.succeed(ran('lamp_on {"room": "kitchen"}'))
        machine.succeed(npm_server)

    with subtest("what runs there can't read grog's state"):
        out = ask("use tool fake-mcp__whoami {}", new=True)
        found = re.fullmatch(r"Feito: uid=(\d+); grog's state isn't readable \(\w+\)", out)
        assert found, out
        assert found.group(1) == uid_of("grog-mcp") != uid_of("grog"), out

    with subtest("a destructive tool asks first, and runs once the user says yes"):
        out = ask("use tool fake-mcp__alarm_off {}", new=True)
        assert out.startswith("Feito: not done yet: ") and "asks the user first" in out, out
        machine.fail(ran("alarm_off {}"))
        # grog's screen asks too.
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"kind\":\"confirm\"'")
        assert (question := session()["step"]["question"]) == "fake-mcp: alarm_off?", question
        # The user said yes: the model calls it again.
        assert (out := ask("use tool fake-mcp__alarm_off {}")) == "Feito: the alarm is off in the room", out
        machine.succeed(ran("alarm_off {}"))
        assert (status := session()["status"]) == "done", status

    with subtest("or once someone allows it on grog's screen, which says yes for them"):
        out = ask('use tool fake-mcp__alarm_off {"room": "hall"}', new=True)
        assert "asks the user first" in out, out
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"kind\":\"confirm\"'")
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/connect/allow"
        )
        machine.wait_until_succeeds(ran('alarm_off {"room": "hall"}'))
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"status\":\"done\"'")

    with subtest("an MCP server on the network, over HTTP, the same way"):
        machine.wait_for_open_port(8700)
        out = grog("integrations add http://127.0.0.1:8700/mcp")
        assert "Added 127-0-0-1-8700-mcp: connect it with grog connect 127-0-0-1-8700-mcp" in out, out
        assert "Connected: fake." in (out := grog("connect 127-0-0-1-8700-mcp")), out
        # On this machine: not in the cloud.
        listed = grog("integrations")
        assert re.search(r"(?m)^127\.0\.0\.1 \(grog connect 127-0-0-1-8700-mcp\): added by you$", listed), listed
        out = ask('use tool 127-0-0-1-8700-mcp__lamp_on {"room": "porch"}', new=True)
        assert out == "Feito: the lamp is on in porch", out
        machine.succeed("grep -qxF 'lamp_on {\"room\": \"porch\"}' /var/lib/fake-mcp/calls.log")

    with subtest("disconnected and removed, its server stops, and the model doesn't see its tools"):
        assert "Disconnected fake-mcp." in (out := grog("disconnect fake-mcp")), out
        machine.wait_until_fails(npm_server)
        assert "Removed fake-mcp." in (out := grog("integrations remove fake-mcp")), out
        assert "Removed 127-0-0-1-8700-mcp." in (out := grog("integrations remove 127-0-0-1-8700-mcp")), out
        listed = grog("integrations")
        assert "fake-mcp" not in listed and "8700" not in listed, listed
        out = ask('use tool fake-mcp__lamp_on {"room": "kitchen"}', new=True)
        assert out.startswith("No tool fake-mcp__lamp_on, only "), out

    def connect_page(what="state", body=None):
        """The page of the session going on (`what`, its token from grog's link): its
        state, or what it answers `body`, sent as `what`."""
        token = session()["link"].rsplit("/", 1)[1]
        url = f"http://127.0.0.1:8485/c/{token}/{what}"
        if body is None:
            return json.loads(machine.succeed(f"curl -sf {url}"))
        sent = shlex.quote(json.dumps(body))
        return json.loads(machine.succeed(f"curl -sf -X POST -H 'Content-Type: application/json' -d {sent} {url}"))

    def connect_ha(way):
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json'"
            f" -d '{{\"way\": \"{way}\"}}' http://127.0.0.1:8484/v1/integrations/home-assistant/connect"
        )
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"kind\":\"form\"'")
        return {f["id"]: f for f in connect_page()["step"]["fields"]}

    def ha_ran(call):
        """Whether Home Assistant ran `call` (its tool and arguments, as it notes them)."""
        return f"grep -qxF {shlex.quote(call)} /var/lib/fake-ha/calls.log"

    def ha_heading():
        """What `grog integrations` says under Home Assistant."""
        listed = grog("integrations")
        found = re.search(r"(?m)^Home Assistant \(grog connect home-assistant\)\n((?:  .*\n?)*)", listed)
        assert found, listed
        return found.group(1)

    with subtest("Home Assistant ships with grog, and grog finds it on the network"):
        machine.wait_for_unit("fake-ha.service")
        machine.wait_for_open_port(8123)
        machine.wait_until_succeeds(
            "avahi-browse --resolve --parsable --terminate _home-assistant._tcp | grep -q location_name=Casa"
        )
        assert (under := ha_heading()).strip() == "nothing connected", under
        fields = connect_ha("sign-in")
        assert list(fields) == ["url"], fields
        assert fields["url"]["default"] == "http://127.0.0.1:8123", fields
        assert "Casa at http://127.0.0.1:8123" in fields["url"]["description"], fields

    with subtest("signed in on Home Assistant's own page, its tools reach the model"):
        connect_page("form", {"url": "http://127.0.0.1:8123/"})
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"kind\":\"oauth\"'")
        signin = connect_page()["step"]["url"]
        assert signin.startswith("http://127.0.0.1:8123/auth/authorize?"), signin
        query = parse_qs(urlsplit(signin).query)
        at = session()["link"].split("/c/")[0]
        assert query["client_id"] == [at + "/"], (query, at)
        assert query["redirect_uri"] == [at + "/c/callback"], (query, at)
        # The browser: Home Assistant's login page sends it back to grog.
        back = machine.succeed(f"curl -s -o /dev/null -w '%{{redirect_url}}' {shlex.quote(signin)}")
        assert back.startswith(at + "/c/callback?code="), back
        assert "Signed in to Test" in machine.succeed(f"curl -sf {shlex.quote(back)}")
        assert (s := session())["status"] == "done" and s["connected"] == "Casa", s
        assert re.match(r"  home-assistant +Casa, 4 tools(, since |$)", under := ha_heading()), under
        tools = grog("tools home-assistant")
        assert re.search(r"(?m)^ *homeassistant__GetLiveContext +allow \(default\) +shown +Get live context$", tools), tools
        assert re.search(r"(?m)^ *intent__HassTurnOn +allow \(default\) +shown +Turn on$", tools), tools
        assert re.search(r"(?m)^ *light__HassLightSet +allow \(default\) +shown +Set a light$", tools), tools
        assert "test-ha-refresh-token" not in machine.succeed("cat /var/lib/grog/integrations.json")
        offered = ask("use tool nothing-here {}", new=True).split()
        assert "lights" in offered, offered

    with subtest("the lights, by voice, through Home Assistant"):
        out = ask('use tool lights {"action": "off", "area": "Sala"}', new=True)
        assert out.startswith("Feito: ") and "Turned off the lights" in out, out
        machine.succeed(ha_ran('intent__HassTurnOff {"area": "Sala", "domain": ["light"]}'))
        out = ask('use tool lights {"action": "set", "area": "Sala", "brightness": 40}', new=True)
        assert out.startswith("Feito: ") and "Brightness set" in out, out
        machine.succeed(ha_ran('light__HassLightSet {"area": "Sala", "brightness": 40}'))

    with subtest("disconnected, Home Assistant forgets grog's token"):
        assert "Disconnected home-assistant." in (out := grog("disconnect home-assistant")), out
        machine.succeed("grep -qx test-ha-refresh-token /var/lib/fake-ha/revoked.log")
        assert (under := ha_heading()).strip() == "nothing connected", under
        out = ask('use tool lights {"action": "off"}', new=True)
        assert out.startswith("No tool lights, only "), out

    with subtest("or with a long-lived token, pasted on its page"):
        fields = connect_ha("token")
        assert list(fields) == ["url", "token"] and fields["token"]["secret"], fields
        connect_page("form", {"url": fields["url"]["default"], "token": "test-long-lived-token"})
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/connect | grep -q '\"status\":\"done\"'")
        assert re.match(r"  home-assistant +Casa, 4 tools(, since |$)", under := ha_heading()), under
        out = ask('use tool lights {"action": "on", "name": "Teto"}', new=True)
        assert out.startswith("Feito: ") and "Turned on the lights" in out, out
        machine.succeed(ha_ran('intent__HassTurnOn {"domain": ["light"], "name": "Teto"}'))
        assert "Disconnected home-assistant." in (out := grog("disconnect home-assistant")), out
        # Nothing to revoke: the token is the user's, in Home Assistant.
        assert machine.succeed("cat /var/lib/fake-ha/revoked.log").split() == ["test-ha-refresh-token"]

    def alice(command):
        return machine.succeed(f"su - alice -c {shlex.quote(command)}").strip()

    def commit(repo, message):
        alice(f"cd ~/{repo} && git add -A && git -c user.name=alice -c user.email=alice@example.com commit -qm {message}")

    with subtest("updates: grog can, from alice's flake, a repository of hers"):
        machine.succeed(
            "install -d -o alice -g users /home/alice/grog /home/alice/nixos",
            "echo '{ outputs = { self }: { }; }' > /home/alice/grog/flake.nix",
            "cp ${systemFlake} /home/alice/nixos/flake.nix",
            "cp ${buildFakeSystem} /home/alice/nixos/build.sh",
            "chown alice:users /home/alice/grog/flake.nix /home/alice/nixos/flake.nix /home/alice/nixos/build.sh",
        )
        alice("cd ~/grog && git init -q")
        commit("grog", "first")
        alice("cd ~/nixos && git init -q")
        commit("nixos", "first")
        alice("cd ~/nixos && nix flake lock")
        commit("nixos", "lock")
        snapshot = machine.succeed("curl -sN --max-time 2 http://127.0.0.1:8484/v1/events || true")
        state = json.loads(snapshot.split("data: ", 1)[1].splitlines()[0])["state"]
        assert "update" in state["actions"], state["actions"]

    with subtest("asked through its hands, it finds nothing new"):
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/actions/update"
        )
        machine.wait_until_succeeds("grep -q '\"stage\":\"current\"' /run/grog-update/status.json")
        machine.fail("test -e /tmp/switched")

    with subtest("something new is built and switched to, and the flake gets the new lock, still alice's"):
        alice("echo new > ~/grog/new")
        commit("grog", "new")
        rev = alice("git -C ~/grog rev-parse HEAD")
        assert f"Done: grog is {rev[:7]} (" in (out := grog("update")), out
        assert machine.succeed("cat /tmp/switched").split() == ["switch"]
        assert "fake-system" in machine.succeed("readlink -f /nix/var/nix/profiles/system")
        machine.succeed(f"grep -q {rev} /home/alice/nixos/flake.lock")
        machine.succeed("stat -c %U /home/alice/nixos/flake.lock | grep -qx alice")

    with subtest("a grog that doesn't build: nothing switches, the lock stays"):
        alice("touch ~/grog/broken")
        commit("grog", "broken")
        out = machine.fail("su - grog -c 'grog update'")
        assert "Couldn't build it" in out, out
        assert machine.succeed("cat /tmp/switched").split() == ["switch"]
        machine.succeed(f"grep -q {rev} /home/alice/nixos/flake.lock")
  '';
}

# The NixOS module (services.grog) in a minimal VM, against a fake OpenAI
# (fake-llm.clj): no credentials, then an API key and a conversation through
# the CLI, signing in to the ChatGPT subscription, what they cost, the
# sandbox, grog-voice: its user service, and a voice turn with the real
# models, which whoever watches grog sees go; its skin, served; its hands: a
# shell, the volume, root, and restarting grog when asked; what grog asks of
# grog-voice: its mic off, listening without its name; a computer pairing
# with it (pc, with services.grog.computer), which the model then acts on,
# as it allows; and updating grog, from a flake of someone else's.
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
  voice = pkgs.callPackage ../packages/grog-voice.nix { };
  models = pkgs.callPackage ../packages/voice-models.nix { };
  # A spoken question, starting with grog's name (the wake word), in a voice
  # other than grog's.
  question = pkgs.runCommand "question.wav" { nativeBuildInputs = [ voice.piper pkgs.sox ]; } ''
    echo 'Test, qual é a previsão do tempo para amanhã em São Paulo?' \
      | piper -m ${models.jeff}/pt_BR-jeff-medium.onnx -f question.wav
    sox question.wav -r 16000 -c 1 -b 16 $out
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
    services.grog.voice = {
      enable = true;
      settings.mic = "null-mic";
    };
    services.grog.hands.enable = true;
    # Served only: no one's desktop shows it.
    services.grog.skin = {
      enable = true;
      user = null;
    };
    # The VM has no sound card: a virtual mic, recording silence, and a virtual
    # speaker, playing to nowhere.
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
            "media.class" = "Audio/Sink";
            "audio.position" = "FL,FR";
          };
        }
      ];
    };
    # The speech models, twice: the service and `grog-voice turn`, Supertonic
    # taking ~1 GB each.
    virtualisation.memorySize = 6144;
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
        machine.succeed("stat -c %a /var/lib/grog/auth.json | grep -qx 600")

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
            f"curl -sf 'http://127.0.0.1:1455/auth/callback?code=test-code&state={state}' | grep -q 'Signed in to grog'"
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
        # Its name, from grog: what's after it is the question.
        assert re.search(r"(?m)^asked: qual é a previsão do tempo para amanhã em são paulo", out.lower()), out
        # A new conversation ("voice"), answered with the API key: first what it's
        # about to do, then the answer, without the source it cited.
        assert "answer: Vou ver a previsão. messages=1 key=test-key name=ok\n" in out, out
        assert "tempo.com" not in out, out
        assert "sounds: ack, searching" in out, out
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

    def computers():
        return json.loads(machine.succeed("curl -sf http://127.0.0.1:8484/v1/computers"))

    with subtest("a computer pairs with grog: the same code on both, allowed at grog"):
        pc.wait_for_unit("sshd.service")
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/actions/pair"
        )
        machine.wait_for_open_port(8485)
        pc.succeed("(su - bob -c 'grog pair machine' > /tmp/pair.log 2>&1 &)")
        pc.wait_until_succeeds("grep -q 'check the code is' /tmp/pair.log")
        code = re.search(r"\n  (\d{3} \d{3})\n", pc.succeed("cat /tmp/pair.log"))
        assert code, pc.succeed("cat /tmp/pair.log")
        machine.wait_until_succeeds("curl -sf http://127.0.0.1:8484/v1/computers | grep -q '\"status\":\"asking\"'")
        pairing = computers()["pairing"]
        assert (pairing["name"], pairing["user"]) == ("pc", "bob"), pairing
        assert pairing["code"] == code.group(1).replace(" ", ""), (pairing, code.group(1))
        machine.succeed(
            "curl -sf -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8484/v1/pairing/allow"
        )
        pc.wait_until_succeeds("grep -q 'Paired: grog can act on this computer now' /tmp/pair.log")
        keys = pc.succeed("cat /home/bob/.ssh/authorized_keys")
        assert re.match(
            r'restrict,from="[\d.]+\.0/24",command="/run/current-system/sw/bin/grog hands --stdio" ssh-ed25519 ', keys
        ), keys
        assert re.search(r"(?m)^pc +bob@", out := grog("computers")), out
        # Only while letting one pair.
        machine.wait_until_fails("ss -ltnH 'sport = :8485' | grep -q .")

    with subtest("the model acts on it as it allows: how it's doing, not a command nobody allowed"):
        out = grog('ask --new "como está o pc?"')
        assert out.startswith("Feito: ") and "name: pc" in out, out
        out = grog('ask --new "rode no pc o id"')
        assert "nobody is there to allow it on pc" in out, out

    with subtest("forgotten, the computer stops letting grog in"):
        assert "it doesn't let grog in anymore" in (out := grog("computers forget pc")), out
        pc.fail("grep -q ssh-ed25519 /home/bob/.ssh/authorized_keys")
        assert "No computers yet" in grog("computers")

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

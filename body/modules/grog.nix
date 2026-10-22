{ config, lib, pkgs, utils, ... }:

let
  cfg = config.services.grog;
  json = pkgs.formats.json { };
  configFile = json.generate "grog.json" cfg.settings;
  # The defaults of brain/src/grog/config.clj.
  port = cfg.settings.server.port or 8484;
  host = cfg.settings.server.host or "127.0.0.1";
  # Where the CLI (and grog-voice) find the server: IPv6 addresses go in
  # brackets.
  clientHost =
    if builtins.elem host [ "0.0.0.0" "::" ] then "127.0.0.1"
    else if lib.hasInfix ":" host && !(lib.hasPrefix "[" host) then "[${host}]"
    else host;
  grogUrl = "http://${clientHost}:${toString port}";
  packages = import ../packages pkgs;

  # grog's hands' socket: its user creates it, grog (in the group) connects.
  handsDir = "/run/grog-hands";
  handsSocket = "${handsDir}/hands.sock";

  # grog's runner's socket: its dynamic user creates it, grog (in the group)
  # connects.
  mcpSocket = "/run/grog-mcp/mcp.sock";
  mcpPython = lib.findFirst (p: lib.getName p == "python3") null cfg.mcp.packages;

  # How an update goes (`grog updater`, as root), for grog to read.
  updateStatus = "/run/grog-update/status.json";

  # Where grog listens while someone at it connects something: its page, and
  # what a computer pairing asks (brain/src/grog/config.clj).
  connectPort = cfg.settings.integrations.port or 8485;

  models = pkgs.callPackage ../packages/voice-models.nix { };
  voiceConfigFile = json.generate "grog-voice.json" (lib.recursiveUpdate
    {
      inherit grogUrl;
      stt.model = "${cfg.voice.sttModel}";
      tts = {
        engine = cfg.voice.ttsEngine;
        voice = cfg.voice.ttsVoice;
      } // lib.optionalAttrs (cfg.voice.ttsEngine != "piper") {
        model = "${cfg.voice.ttsModel}";
      };
    }
    cfg.voice.settings);
in
{
  imports = [
    (lib.mkRenamedOptionModule [ "services" "grog" "computers" "openFirewall" ] [ "services" "grog" "integrations" "openFirewall" ])
  ];

  options.services.grog = {
    enable = lib.mkEnableOption "grog, the home voice assistant (LLM chat server and `grog` CLI)";

    package = lib.mkOption {
      type = lib.types.package;
      default = packages.grog;
      defaultText = lib.literalMD "grog, built from this repo (`body/packages`)";
      description = "The grog package.";
    };

    user = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "alice";
      description = ''
        The user whose session has the microphone, the speaker and the
        screen: whose user services run grog-voice, its hands and its skin
        (the default of `voice.user`, `hands.user` and `skin.user`).
      '';
    };

    settings = lib.mkOption {
      inherit (json) type;
      default = { };
      example = lib.literalExpression ''
        {
          name = "Jarvis";
          location = { city = "São Paulo"; region = "SP"; country = "BR"; };
          providers.openai.reasoningEffort = "medium";
        }
      '';
      description = ''
        Settings rendered to JSON (lowerCamelCase keys) and deep-merged over
        the app defaults (brain/src/grog/config.clj, documented in brain/README.md),
        checked when the service starts. Never put API keys here: they would
        end up in the world-readable Nix store. `settings.nrepl.enable` lets any local
        user run code as the service: only turn it on for machines you alone
        use.
      '';
    };

    environmentFile = lib.mkOption {
      # A string on purpose: a path literal would copy the secrets to the store.
      type = lib.types.nullOr lib.types.str;
      default = "/etc/grog/secrets.env";
      description = ''
        File with the provider's API key (`OPENAI_API_KEY=...`), read by
        systemd when starting the service. It lives only on the
        machine (root-owned, mode 0600), out of git and of the Nix store. The
        service still starts without it, answering with an error that says
        which variable is missing.
      '';
    };

    revision = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      defaultText = lib.literalMD "the revision of grog's flake, with its module (`nixosModules.default`)";
      example = "fbd2aadd94d1c3ae9adb3aa40b1e600dbdf7fb33";
      description = ''
        The revision of grog that `package` was built from: what grog says
        it is, and what an update compares with the latest grog pushed.
      '';
    };

    integrations.openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = ''
        Whether to open the port where grog listens while someone at it
        connects something (`settings.integrations.port`): the page a phone
        or computer follows the steps on, and what a computer pairing asks.
        It only listens then, for a few minutes, and the page is behind a
        token only grog's screen and CLI show.
      '';
    };

    update = {
      enable = lib.mkEnableOption ''
        updating grog when asked (by voice, on its skin, or with `grog
        update`): as root, it builds this system from `flake` with its
        `input` at the latest grog pushed, and switches to it once nobody is
        talking to grog, going back to the previous system if the new grog
        doesn't answer. Whoever can push to that input can then run code as
        root here, once someone asks'';

      flake = lib.mkOption {
        type = lib.types.str;
        default = "/etc/nixos";
        example = "/home/alice/nixos";
        description = ''
          The directory of the flake this system is built from. Its owner
          evaluates and builds it (nix won't open someone else's git
          repository as root), and its flake.lock only changes once the new
          system works.
        '';
      };

      host = lib.mkOption {
        type = lib.types.str;
        default = config.networking.hostName;
        defaultText = lib.literalExpression "config.networking.hostName";
        description = "This system in the flake: `nixosConfigurations.<host>`.";
      };

      input = lib.mkOption {
        type = lib.types.str;
        default = "grog";
        description = "The flake's input that is grog.";
      };
    };

    voice = {
      enable = lib.mkEnableOption ''
        grog-voice, grog's ears and mouth: it listens to a microphone and
        answers through a speaker, hands-free (voice/ in the repo)'';

      package = lib.mkOption {
        type = lib.types.package;
        default = packages.grog-voice;
        defaultText = lib.literalMD "grog-voice, built from this repo (`body/packages`)";
        description = "The grog-voice package.";
      };

      user = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = cfg.user;
        defaultText = lib.literalExpression "config.services.grog.user";
        example = "alice";
        description = ''
          The user whose PipeWire session has the microphone and the speaker:
          grog-voice runs as a systemd user service of theirs. Their systemd
          instance must be running for it to listen: log them in (say, with
          the display manager's auto-login), or set
          `users.users.<name>.linger = true` to start it at boot.
        '';
      };

      sttPrecision = lib.mkOption {
        type = lib.types.enum [ "int8" "fp32" ];
        default = "int8";
        description = ''
          Speech to text: Parakeet in int8, or in fp32, which hears more
          (from afar, through a poor mic) for ~1.3 GB more RAM per Parakeet:
          grog-voice runs two, with partials. With AVX2, fp32 on 4 threads
          (`settings.stt.threads`) is about as fast as int8 on 2.
        '';
      };

      sttModel = lib.mkOption {
        type = lib.types.path;
        default = if cfg.voice.sttPrecision == "fp32" then models.parakeetFp32 else models.parakeet;
        defaultText = lib.literalMD "Parakeet TDT 0.6B v3, in `sttPrecision` (`body/packages/voice-models.nix`)";
        description = ''
          Speech to text: the directory of a Parakeet TDT model for
          sherpa-onnx, int8 or fp32. The default knows 25 European languages.
        '';
      };

      ttsEngine = lib.mkOption {
        type = lib.types.enum [ "pocket" "supertonic" "piper" ];
        default = "pocket";
        description = ''
          Text to speech: Pocket TTS, in a voice cloned from a recording
          (Portuguese only), its speech playing as it's made; Supertonic 3,
          in 31 languages, ~8 times as fast, a sentence at a time; or Piper,
          lighter (~1 GB less than Supertonic) and faster to start speaking
          than Supertonic, but flatter. Pocket TTS takes 0.5 s per second of
          speech on a Core Ultra 7 155H, and ~3 GB of RAM; it wasn't
          measured on slower CPUs, where it may not keep up: grognix's runs
          Supertonic.
        '';
      };

      ttsModel = lib.mkOption {
        type = lib.types.path;
        default = if cfg.voice.ttsEngine == "supertonic" then models.supertonic else models.pocket;
        defaultText = lib.literalMD ''
          Pocket TTS's Portuguese model with 24 layers, and its voice
          `deep-calm`; with Supertonic, Supertonic 3, its graphs and its ten
          voices (`body/packages/voice-models.nix`)'';
        description = ''
          Pocket TTS: its directory, as `voice/tools/export_pocket.py` makes
          it. Supertonic: its directory, laid out as Supertone/supertonic-3
          on Hugging Face (`onnx/`, `voice_styles/`). Piper has none.
        '';
      };

      ttsVoice = lib.mkOption {
        type = lib.types.str;
        default = {
          pocket = "deep-calm";
          supertonic = "M1";
          piper = "${models.faber}/pt_BR-faber-medium.onnx";
        }.${cfg.voice.ttsEngine};
        defaultText = lib.literalMD ''
          `"deep-calm"`; with Supertonic, `"M1"`; with Piper, its pt_BR
          `faber` voice, medium quality (`body/packages/voice-models.nix`)'';
        example = "F2";
        description = ''
          Pocket TTS: one of its model's voices (in its `voices/`), or a
          voice file (`.npz`, made by `voice/tools/export_pocket.py`).
          Supertonic: one of its voices (F1 to F5, M1 to M5), or a voice
          style file (`.json`). Piper: a voice, its `.onnx` file with the
          `.onnx.json` next to it (https://huggingface.co/rhasspy/piper-voices).
        '';
      };

      settings = lib.mkOption {
        inherit (json) type;
        default = { };
        example = lib.literalExpression ''
          {
            mic = "alsa_input.usb-Focusrite_Scarlett_2i2_4th_Gen-00.HiFi__Mic1__source";
            endSilenceMs = 800;
          }
        '';
        description = ''
          Settings rendered to JSON (lowerCamelCase keys) and merged over
          grog-voice's defaults (voice/grog_voice/config.py, documented in
          voice/README.md). `grogUrl` points at services.grog on this machine,
          `stt.model` and `tts.engine`, `tts.voice` and `tts.model` come from
          the options above.
        '';
      };
    };

    hands = {
      enable = lib.mkEnableOption ''
        grog's hands: the tools that act on the machine (the speaker's volume,
        how the machine is doing, restarting it or grog, a shell), run by a
        small service of `user`. Whatever that user can do, grog can,
        through them, without asking (with `sudo`, anything). And so can
        anyone who can run code in grog (`settings.nrepl.enable`)'';

      sudo = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = ''
          Whether `user` may use sudo without a password, and do what polkit
          guards (restarting services, rebooting, networks...) without being
          asked for one: grog can then do anything on the machine. Off, it
          can do what `user` could already.
        '';
      };

      user = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = cfg.user;
        defaultText = lib.literalExpression "config.services.grog.user";
        example = "alice";
        description = ''
          Whose systemd user service runs grog's commands, in their session:
          the user with the speaker (`voice.user`), for the volume to work.
        '';
      };
    };

    mcp = {
      enable = lib.mkEnableOption ''
        grog's runner, for MCP servers from npm and PyPI: a service of its
        own, apart from grog, with a user of its own, the network, a cache
        and a state directory, but no access to grog's state or anyone's
        home. It installs the servers grog asks for at run time (with npx
        and uvx), the versions grog knows pinned'';

      packages = lib.mkOption {
        type = lib.types.listOf lib.types.package;
        default = [ pkgs.nodejs pkgs.uv pkgs.python3 ];
        defaultText = lib.literalExpression "[ pkgs.nodejs pkgs.uv pkgs.python3 ]";
        description = ''
          What servers run with, on the runner's PATH: `npx` and `uvx`, and
          the Python uv uses (the first `python3` here: uv doesn't download
          its own).
        '';
      };
    };

    skin = {
      enable = lib.mkEnableOption ''
        grog's skin: a page showing what grog is up to (what it hears, the
        steps and tools of its turns, what it consumed), served at grog's
        address (skin/ in the repo). What's said only shows with
        `settings.skin.captions`'';

      package = lib.mkOption {
        type = lib.types.package;
        default = packages.grog-skin;
        defaultText = lib.literalMD "grog-skin, built from this repo (`body/packages`)";
        description = "The grog-skin package.";
      };

      user = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = cfg.user;
        defaultText = lib.literalExpression "config.services.grog.user";
        example = "alice";
        description = ''
          Whose graphical session shows the skin fullscreen as soon as it
          starts (a systemd user service of theirs; on GNOME, it closes the
          overview and keeps the screen on). null: it's only served, at
          grog's address.
        '';
      };
    };
  };

  config = lib.mkMerge [
    {
      assertions = [
        {
          assertion = cfg.voice.enable || cfg.hands.enable || cfg.skin.enable || cfg.update.enable -> cfg.enable;
          message = "services.grog.voice, .hands, .skin and .update go with grog: enable services.grog.";
        }
        {
          assertion = cfg.voice.enable -> cfg.voice.user != null;
          message = "services.grog.voice runs in a user's session: set services.grog.user.";
        }
        {
          assertion = cfg.hands.enable -> cfg.hands.user != null;
          message = "services.grog.hands run in a user's session: set services.grog.user.";
        }
      ];
    }

    (lib.mkIf cfg.enable {
      environment.systemPackages = [ cfg.package ];
      environment.variables.GROG_URL = grogUrl;
      networking.firewall.allowedTCPPorts = lib.mkIf cfg.integrations.openFirewall [ connectPort ];

      systemd.services.grog = {
        description = "grog, the home voice assistant";
        wantedBy = [ "multi-user.target" ];
        wants = [ "network-online.target" ];
        after = [ "network-online.target" ];
        # ssh and ssh-keygen, for the computers it acts on (grog.computers).
        path = [ pkgs.openssh ];
        environment = lib.mkIf (cfg.revision != null) { GROG_REVISION = cfg.revision; };

        serviceConfig = {
          ExecStart = "${lib.getExe cfg.package} serve --config ${configFile}";
          EnvironmentFile = lib.mkIf (cfg.environmentFile != null) "-${cfg.environmentFile}";
          Restart = "on-failure";
          RestartSec = 5;
          # How grog restarts itself, when asked to (grog.actions).
          SuccessExitStatus = 75;
          RestartForceExitStatus = 75;

          DynamicUser = true;
          # Named apart from the unit: systemd would otherwise reuse an existing
          # user called grog, like grognix's login user (wheel, desktop session).
          User = "grogd";
          StateDirectory = "grog";
          WorkingDirectory = "/var/lib/grog";

          # Hardening. Audio is grog-voice's business (services.grog.voice).
          CapabilityBoundingSet = "";
          LockPersonality = true;
          # Babashka is a native image: no JIT.
          MemoryDenyWriteExecute = true;
          NoNewPrivileges = true;
          PrivateDevices = true;
          PrivateTmp = true;
          ProcSubset = "pid";
          ProtectClock = true;
          ProtectControlGroups = true;
          ProtectHome = true;
          ProtectHostname = true;
          ProtectKernelLogs = true;
          ProtectKernelModules = true;
          ProtectKernelTunables = true;
          ProtectProc = "invisible";
          ProtectSystem = "strict";
          RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_NETLINK" "AF_UNIX" ];
          RestrictNamespaces = true;
          RestrictRealtime = true;
          RestrictSUIDSGID = true;
          SystemCallArchitectures = "native";
          SystemCallFilter = [ "@system-service" ];
          SystemCallErrorNumber = "EPERM";
          UMask = "0077";
        };
      };
    })

    (lib.mkIf cfg.voice.enable {
      assertions = [{
        assertion = config.services.pipewire.enable;
        message = "services.grog.voice records and plays through PipeWire: enable services.pipewire.";
      }];

      environment.systemPackages = [ cfg.voice.package ];
      # Where `grog-voice turn` finds the service's settings.
      environment.variables.GROG_VOICE_CONFIG = "${voiceConfigFile}";

      # In the session of the user with the microphone and the speaker.
      systemd.user.services.grog-voice = {
        description = "grog-voice, grog's ears and mouth";
        wantedBy = [ "default.target" ];
        wants = [ "pipewire.service" ];
        after = [ "pipewire.service" "wireplumber.service" ];
        unitConfig.ConditionUser = cfg.voice.user;
        serviceConfig = {
          ExecStart = "${lib.getExe cfg.voice.package} --config ${voiceConfigFile} run";
          # Also when the mic goes away (unplugged, say): it picks it up again.
          Restart = "always";
          RestartSec = 5;
          LockPersonality = true;
          NoNewPrivileges = true;
        };
      };
    })

    (lib.mkIf cfg.hands.enable {
      users.groups.grog-hands = { };
      systemd.tmpfiles.rules = [ "d ${handsDir} 2750 ${cfg.hands.user} grog-hands -" ];
      services.grog.settings.hands.socket = handsSocket;
      systemd.services.grog.serviceConfig.SupplementaryGroups = [ "grog-hands" ];

      # In the session of the user with the speaker (PipeWire, Bluetooth).
      systemd.user.services.grog-hands = {
        description = "grog's hands, what it does on the machine";
        wantedBy = [ "default.target" ];
        unitConfig.ConditionUser = cfg.hands.user;
        # sudo, the user's programs, and the system's (wpctl, nmcli, systemctl,
        # and the coreutils the default path has).
        environment.PATH = lib.mkForce "/run/wrappers/bin:/etc/profiles/per-user/${cfg.hands.user}/bin:/run/current-system/sw/bin";
        serviceConfig = {
          ExecStart = "${lib.getExe cfg.package} hands --socket ${handsSocket}";
          Restart = "always";
          RestartSec = 5;
          # Unlike grog-voice, no NoNewPrivileges: shell commands may use sudo.
          LockPersonality = true;
        };
      };
    })

    (lib.mkIf (cfg.hands.enable && cfg.hands.sudo) (
      let
        noPassword = [{
          users = [ cfg.hands.user ];
          commands = [{ command = "ALL"; options = [ "NOPASSWD" ]; }];
        }];
      in
      {
        security.sudo.extraRules = lib.mkIf config.security.sudo.enable noPassword;
        security.sudo-rs.extraRules = lib.mkIf config.security.sudo-rs.enable noPassword;
        # systemctl, reboot, nmcli... without sudo too.
        security.polkit.enable = true;
        security.polkit.extraConfig = ''
          polkit.addRule(function (action, subject) {
            if (subject.user == ${builtins.toJSON cfg.hands.user}) return polkit.Result.YES;
          });
        '';
      }
    ))

    (lib.mkIf cfg.update.enable {
      services.grog.settings.update.status = updateStatus;

      # Started by grog's hands when someone asks (polkit lets them, below),
      # or by hand: sudo systemctl start grog-update.
      systemd.services.grog-update = {
        description = "Updating grog to the latest pushed";
        # It's what switches the system: switching mustn't stop it.
        restartIfChanged = false;
        unitConfig.X-StopOnRemoval = false;
        path = [ config.nix.package config.systemd.package pkgs.coreutils pkgs.git pkgs.util-linux ];
        environment = {
          GROG_URL = grogUrl;
        } // lib.optionalAttrs (cfg.revision != null) { GROG_REVISION = cfg.revision; };
        serviceConfig = {
          Type = "oneshot";
          ExecStart = utils.escapeSystemdExecArgs [
            (lib.getExe cfg.package)
            "updater"
            "--flake"
            cfg.update.flake
            "--host"
            cfg.update.host
            "--input"
            cfg.update.input
            "--status"
            updateStatus
          ];
          # What it says stays there once it's done, for grog to read.
          RuntimeDirectory = "grog-update";
          RuntimeDirectoryMode = "0755";
          RuntimeDirectoryPreserve = true;
          # grog-voice, on the same CPU, comes first.
          Nice = 10;
          IOSchedulingClass = "idle";
        };
      };
    })

    (lib.mkIf (cfg.update.enable && cfg.hands.enable) {
      # With sudo or not.
      security.polkit.enable = true;
      security.polkit.extraConfig = ''
        polkit.addRule(function (action, subject) {
          if (action.id == "org.freedesktop.systemd1.manage-units" &&
              action.lookup("unit") == "grog-update.service" &&
              action.lookup("verb") == "start" &&
              subject.user == ${builtins.toJSON cfg.hands.user}) return polkit.Result.YES;
        });
      '';
    })

    (lib.mkIf cfg.mcp.enable {
      assertions = [{
        assertion = cfg.enable;
        message = "services.grog.mcp goes with grog: enable services.grog.";
      }];

      users.groups.grog-mcp = { };
      services.grog.settings.integrations.runner = mcpSocket;
      systemd.services.grog.serviceConfig.SupplementaryGroups = [ "grog-mcp" ];

      systemd.services.grog-mcp = {
        description = "grog's runner, for MCP servers from npm and PyPI";
        wantedBy = [ "multi-user.target" ];
        wants = [ "network-online.target" ];
        after = [ "network-online.target" ];
        path = cfg.mcp.packages;
        environment = {
          HOME = "/var/lib/grog-mcp";
          XDG_CACHE_HOME = "/var/cache/grog-mcp";
          npm_config_cache = "/var/cache/grog-mcp/npm";
          UV_CACHE_DIR = "/var/cache/grog-mcp/uv";
          UV_TOOL_DIR = "/var/lib/grog-mcp/uv-tools";
          UV_PYTHON_DOWNLOADS = "never";
        } // lib.optionalAttrs (mcpPython != null) {
          UV_PYTHON = "${mcpPython}/bin/python3";
        };

        serviceConfig = {
          ExecStart = "${lib.getExe cfg.package} mcp --socket ${mcpSocket}";
          Restart = "on-failure";
          RestartSec = 5;

          DynamicUser = true;
          # Named apart from its group: systemd won't make a dynamic user
          # named like a static group.
          User = "grog-mcpd";
          # Static, for grog to be in it.
          Group = "grog-mcp";
          RuntimeDirectory = "grog-mcp";
          RuntimeDirectoryMode = "0750";
          StateDirectory = "grog-mcp";
          CacheDirectory = "grog-mcp";
          WorkingDirectory = "/var/lib/grog-mcp";

          # Hardening: grog's, but Node's JIT needs memory that's writable
          # and executable, and it reads /proc's CPUs and memory.
          CapabilityBoundingSet = "";
          LockPersonality = true;
          NoNewPrivileges = true;
          PrivateDevices = true;
          PrivateTmp = true;
          ProtectClock = true;
          ProtectControlGroups = true;
          ProtectHome = true;
          ProtectHostname = true;
          ProtectKernelLogs = true;
          ProtectKernelModules = true;
          ProtectKernelTunables = true;
          ProtectProc = "invisible";
          ProtectSystem = "strict";
          RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_NETLINK" "AF_UNIX" ];
          RestrictNamespaces = true;
          RestrictRealtime = true;
          RestrictSUIDSGID = true;
          SystemCallArchitectures = "native";
          SystemCallFilter = [ "@system-service" ];
          SystemCallErrorNumber = "EPERM";
          UMask = "0077";
        };
      };
    })

    (lib.mkIf cfg.skin.enable {
      services.grog.settings.skin.dir = lib.mkDefault "${cfg.skin.package}/share/grog-skin/web";
    })

    (lib.mkIf (cfg.skin.enable && cfg.skin.user != null) {
      environment.systemPackages = [ cfg.skin.package ];

      # As soon as the user's graphical session starts.
      systemd.user.services.grog-skin = {
        description = "grog's skin, fullscreen";
        wantedBy = [ "graphical-session.target" ];
        partOf = [ "graphical-session.target" ];
        after = [ "graphical-session.target" ];
        unitConfig.ConditionUser = cfg.skin.user;
        serviceConfig = {
          ExecStart = "${lib.getExe cfg.skin.package} ${grogUrl}";
          # Closed on purpose (Ctrl+Q), it stays closed until the next login.
          Restart = "on-failure";
          RestartSec = 2;
          # grog-voice, on the same CPU, comes first.
          Nice = 10;
          CPUWeight = 20;
          LockPersonality = true;
          NoNewPrivileges = true;
        };
      };
    })
  ];
}

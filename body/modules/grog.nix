{ config, lib, pkgs, ... }:

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

  models = pkgs.callPackage ../packages/voice-models.nix { };
  voiceConfigFile = json.generate "grog-voice.json" (lib.recursiveUpdate
    {
      inherit grogUrl;
      stt.model = "${cfg.voice.sttModel}";
      tts.voice = "${cfg.voice.ttsVoice}";
    }
    cfg.voice.settings);
in
{
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
        the app defaults (brain/src/grog/config.clj, documented in README.md),
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

      sttModel = lib.mkOption {
        type = lib.types.path;
        default = models.parakeet;
        defaultText = lib.literalMD "Parakeet TDT 0.6B v3, int8 (`body/packages/voice-models.nix`)";
        description = ''
          Speech to text: the directory of a Parakeet TDT model for
          sherpa-onnx (int8). The default knows 25 European languages.
        '';
      };

      ttsVoice = lib.mkOption {
        type = lib.types.path;
        default = "${models.faber}/pt_BR-faber-medium.onnx";
        defaultText = lib.literalMD "Piper's pt_BR `faber` voice, medium quality (`body/packages/voice-models.nix`)";
        description = ''
          Text to speech: a Piper voice, its `.onnx` file with the
          `.onnx.json` next to it. Voices: https://huggingface.co/rhasspy/piper-voices
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
          README.md). `grogUrl` points at services.grog on this machine,
          `stt.model` and `tts.voice` come from the options above.
        '';
      };
    };

    hands = {
      enable = lib.mkEnableOption ''
        grog's hands: the tools that act on the machine (the speaker's volume,
        how the machine is doing, a shell), run by a small service of `user`.
        That user can usually use sudo, and so then can grog, through them,
        without asking. And anyone who can run code in grog
        (`settings.nrepl.enable`) can too'';

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
          assertion = cfg.voice.enable || cfg.hands.enable || cfg.skin.enable -> cfg.enable;
          message = "services.grog.voice, .hands and .skin go with grog: enable services.grog.";
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

      systemd.services.grog = {
        description = "grog, the home voice assistant";
        wantedBy = [ "multi-user.target" ];
        wants = [ "network-online.target" ];
        after = [ "network-online.target" ];

        serviceConfig = {
          ExecStart = "${lib.getExe cfg.package} serve --config ${configFile}";
          EnvironmentFile = lib.mkIf (cfg.environmentFile != null) "-${cfg.environmentFile}";
          Restart = "on-failure";
          RestartSec = 5;

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

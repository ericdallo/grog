# A computer grog acts on, once paired with it (`curl -fsS <grog's
# address>:8485 | bash`, run by `user`, while grog lets a computer pair): its
# SSH server, through which grog comes in to run its hands here (the script
# pairing keeps in ~/.local/share/grog, nothing to install), and polkit
# letting the user suspend the computer and turn it off from there. What
# grog may do is the user's to say, in ~/.config/grog/hands.json
# (brain/README.md, Computers).
{ config, lib, ... }:

let
  cfg = config.services.grog.computer;
in
{
  options.services.grog.computer = {
    enable = lib.mkEnableOption ''
      letting a grog act on this computer, once paired with it: `curl -fsS
      <grog's address>:8485 | bash`, run as `user`'';

    user = lib.mkOption {
      type = lib.types.str;
      example = "alice";
      description = ''
        Who grog acts as here: the user who pairs it, whose
        ~/.config/grog/hands.json says what grog may do.
      '';
    };

    power = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = ''
        Whether `user` may suspend the computer and turn it off from
        anywhere, through polkit: for grog to, when it may.
      '';
    };
  };

  config = lib.mkIf cfg.enable (lib.mkMerge [
    {
      services.openssh.enable = true;
    }

    (lib.mkIf cfg.power {
      security.polkit.enable = true;
      security.polkit.extraConfig = ''
        polkit.addRule(function (action, subject) {
          var power = ["org.freedesktop.login1.suspend", "org.freedesktop.login1.suspend-multiple-sessions",
                       "org.freedesktop.login1.power-off", "org.freedesktop.login1.power-off-multiple-sessions"];
          if (subject.user == ${builtins.toJSON cfg.user} && power.indexOf(action.id) >= 0) return polkit.Result.YES;
        });
      '';
    })
  ]);
}

{
  description = "grog, a home voice assistant, and grognix, a PC dedicated to it";

  inputs = {
    # Stable, for a server. Bump with `nix flake update`.
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    # Formats the disk when installing a grognix.
    disko = {
      url = "github:nix-community/disko/latest";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, disko }:
    let
      inherit (nixpkgs) lib;
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      # The installer also runs on Macs.
      forInstallerSystems = f: lib.genAttrs (systems ++ [ "aarch64-darwin" ]) (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = lib.recursiveUpdate
        (forAllSystems (pkgs: import ./body/packages pkgs // {
          default = self.packages.${pkgs.stdenv.hostPlatform.system}.grog;
        }))
        # `nix run github:ericdallo/grog#install -- <user@host>`: installs
        # grognix on a machine, see body/README.md.
        (forInstallerSystems (pkgs: {
          install = pkgs.callPackage ./body/install { };
        }));

      overlays.default = final: _prev: import ./body/packages final;

      # services.grog, running this flake's packages unless set otherwise.
      nixosModules.default = { pkgs, ... }:
        let
          packages = self.packages.${pkgs.stdenv.hostPlatform.system};
        in
        {
          imports = [ ./body/modules/grog.nix ];
          services.grog.revision = lib.mkDefault (self.rev or self.dirtyRev or null);
          services.grog.package = lib.mkDefault packages.grog;
          services.grog.voice.package = lib.mkDefault packages.grog-voice;
          services.grog.music.package = lib.mkDefault packages.grog-music;
          services.grog.skin.package = lib.mkDefault packages.grog-skin;
        };

      # A computer grog acts on, once paired with it: see body/README.md.
      nixosModules.computer = ./body/modules/computer.nix;

      # grognix, a PC dedicated to grog: see body/README.md.
      nixosModules.grognix = {
        imports = [ disko.nixosModules.disko self.nixosModules.default ./body/grognix ];
      };

      # A grognix, with the modules saying what's particular to the machine:
      # its /etc/nixos/flake.nix has
      #   nixosConfigurations.grognix = grog.lib.grognix { modules = [ ./machine.nix ]; };
      lib.grognix = { modules ? [ ] }: lib.nixosSystem {
        modules = [ self.nixosModules.grognix ] ++ modules;
      };

      # A made-up grognix, for `make build` and `make vm`: not one to deploy.
      nixosConfigurations.example = self.lib.grognix {
        modules = [ ./body/grognix/example.nix ];
      };

      checks = forAllSystems (pkgs: {
        # Building the packages runs the unit tests (and shellcheck, for the
        # installer).
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) grog grog-voice grog-music grog-skin install;
        # The NixOS module in a minimal VM, against a fake OpenAI.
        module = import ./body/tests/module.nix {
          inherit pkgs;
          module = self.nixosModules.default;
        };
      } // lib.optionalAttrs (pkgs.stdenv.hostPlatform.system == "x86_64-linux") {
        # A grognix in a VM, with its desktop and headless.
        grognix = import ./body/tests/grognix.nix {
          inherit pkgs;
          modules = [ self.nixosModules.grognix ./body/grognix/example.nix ];
        };
        # Its disk, formatted over what an older system left.
        disk = import ./body/tests/disk.nix {
          inherit pkgs;
          diskoScript = (self.lib.grognix {
            modules = [ ./body/grognix/example.nix { grognix.disk = lib.mkForce "/dev/vdb"; } ];
          }).config.system.build.diskoScript;
        };
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # qrencode: the brain's tests check its QR codes against it.
          packages = with pkgs; [ babashka clj-kondo nodejs-slim python3 qrencode ruff shellcheck ];
        };
      });
    };
}

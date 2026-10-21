NIX := nix --experimental-features 'nix-command flakes'
SERVER ?= grog@grognix.local

.PHONY: test lint check check-vm build vm install-grognix deploy login

# Unit tests, no Nix needed: the brain's (Babashka), the voice's (Python,
# standard library only) and the skin's (Node, no dependencies).
test:
	cd brain && bb test
	cd voice && python3 -m unittest discover -s tests
	cd skin && node --test

# clj-kondo, ruff and node --check (all in `nix develop`).
lint:
	cd brain && clj-kondo --lint src test
	cd voice && ruff check . && ruff format --check .
	cd skin && for f in kiosk.js web/*.js test/*.js; do node --check "$$f" || exit 1; done

# The package builds, which run the unit tests in the sandbox (and
# shellcheck, for the installer).
check:
	system=$$($(NIX) eval --impure --raw --expr builtins.currentSystem) && \
	$(NIX) build --no-link -L ".#checks.$$system.grog" ".#checks.$$system.grog-voice" \
	  ".#checks.$$system.grog-skin" ".#checks.$$system.install"

# The VM tests, one at a time (each takes 4 to 7 GB of RAM; `nix flake check`
# runs everything at once): the NixOS module, grognix, and grognix's disk.
check-vm:
	system=$$($(NIX) eval --impure --raw --expr builtins.currentSystem) && \
	for check in module grognix disk; do $(NIX) build --no-link -L ".#checks.$$system.$$check" || exit 1; done

# A made-up grognix's system closure (body/grognix/example.nix).
build:
	$(NIX) build '.#nixosConfigurations.example.config.system.build.toplevel'

# The made-up grognix in a local VM: console login grog/grog, ssh -p 2222 grog@localhost.
vm:
	$(NIX) build '.#nixosConfigurations.example.config.system.build.vm'
	QEMU_OPTS="-cpu host -smp 2 -m 4096M -machine type=q35,accel=kvm" ./result/bin/run-grognix-vm

# Installs grognix on TARGET (user@host: a NixOS installer, or any Linux) with
# this checkout as grog, ERASING one of its disks: it asks which, and before.
# See body/README.md.
install-grognix:
	@test -n "$(TARGET)" || { echo "Usage: make install-grognix TARGET=<user@host>" >&2; exit 2; }
	$(NIX) run '.#install' -- --grog 'git+file://$(CURDIR)' $(TARGET)

# Builds the server's own config (its /etc/nixos: machine.nix, facter.json)
# here, with this checkout as grog, and switches the server to it over SSH;
# the server fetches what it can from the binary cache itself. Extra ssh
# options go in NIX_SSHOPTS.
deploy:
	@ssh $(NIX_SSHOPTS) $(SERVER) test -f /etc/nixos/flake.nix || { \
	  echo "$(SERVER) has no /etc/nixos/flake.nix: only machines set up by the installer can be deployed to (body/README.md)." >&2; \
	  exit 1; }
	@dir=$$(mktemp -d); trap 'rm -rf "$$dir"' EXIT; \
	ssh $(NIX_SSHOPTS) $(SERVER) tar -C /etc/nixos -cf - . | tar -C "$$dir" -xf - && \
	nixos-rebuild switch --flake "path:$$dir#grognix" --override-input grog "git+file://$(CURDIR)" \
	  --target-host $(SERVER) --sudo --use-substitutes

# Signs grog in to your ChatGPT subscription. The tunnel carries the browser's
# redirect (to localhost:1455) from this machine to grog on the server.
login:
	ssh $(NIX_SSHOPTS) -t -o ExitOnForwardFailure=yes -L 1455:127.0.0.1:1455 $(SERVER) grog login

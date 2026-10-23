# grog's Wi-Fi, through its hands and NetworkManager (grog.wifi), as its
# skin asks: against radios the kernel simulates (mac80211_hwsim), hostapd
# serving two networks on one, a password-protected one and an open one,
# and NetworkManager managing the other. What it sees, a wrong password
# (said plainly, nothing kept, never logged), the right one (kept for any
# Wi-Fi device), an open network, and the saved one again, without asking.
# Run: nix build .#checks.x86_64-linux.wifi -L
{ pkgs, module }:

let
  password = "grog's secret 123";
in
pkgs.testers.runNixOSTest {
  name = "grog-wifi";

  nodes.machine = { lib, ... }: {
    imports = [ module ];

    # Two radios: wlan0 for the access point, wlan1 for the machine.
    boot.kernelModules = [ "mac80211_hwsim" ];
    services.hostapd = {
      enable = true;
      radios.wlan0 = {
        band = "2g";
        channel = 6;
        countryCode = "US";
        networks = {
          wlan0 = {
            ssid = "grog-test";
            authentication = {
              mode = "wpa2-sha1";
              wpaPassword = password;
            };
            bssid = "02:00:00:00:00:00";
          };
          wlan0-1 = {
            ssid = "grog-open";
            authentication.mode = "none";
            bssid = "02:00:00:00:00:01";
          };
        };
      };
    };

    # The machine's Wi-Fi; the test's own network, and the access point's
    # radio, aren't NetworkManager's.
    networking.networkmanager = {
      enable = true;
      unmanaged = [ "interface-name:eth*" "interface-name:wlan0*" ];
    };
    # NetworkManager's wpa_supplicant, which test VMs turn off.
    networking.wireless.enable = lib.mkOverride 0 true;
    # For the networks to give addresses, as a router would (see below).
    environment.systemPackages = [ pkgs.dnsmasq ];
    networking.firewall.trustedInterfaces = [ "wlan0" "wlan0-1" ];

    services.grog = {
      enable = true;
      settings.name = "Test";
      user = "grog";
      hands.enable = true;
    };
    # A login user named like the service, its hands' user: no one logs in,
    # its systemd starts at boot.
    users.users.grog = {
      isNormalUser = true;
      linger = true;
    };
  };

  testScript = ''
    import json
    import shlex

    machine.wait_for_unit("hostapd.service")
    # Each network gives addresses, as a router would: NetworkManager has
    # joined one once it has one.
    machine.wait_until_succeeds("ip link show wlan0-1")
    machine.succeed(
        "ip address add 10.42.0.1/24 dev wlan0",
        "ip address add 10.43.0.1/24 dev wlan0-1",
        "dnsmasq --port=0 --bind-interfaces --interface=wlan0 --interface=wlan0-1"
        " --dhcp-range=10.42.0.10,10.42.0.99,1h --dhcp-range=10.43.0.10,10.43.0.99,1h",
    )
    machine.wait_for_unit("NetworkManager.service")
    machine.wait_for_unit("grog.service")
    machine.wait_for_open_port(8484)
    machine.wait_until_succeeds("systemctl --user -M grog@ is-active grog-hands.service")
    machine.wait_until_succeeds("test -S /run/grog-hands/hands.sock")

    def wifi(body):
        """grog's answer to its screen asking `body` of its Wi-Fi: (status, JSON)."""
        status = machine.succeed(
            "curl -s -o /tmp/wifi.json -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d "
            + shlex.quote(json.dumps(body))
            + " http://127.0.0.1:8484/v1/actions/wifi"
        )
        return int(status), json.loads(machine.succeed("cat /tmp/wifi.json"))

    def networks(answer):
        return {n["ssid"]: n for n in answer.get("networks", [])}

    def saved():
        return machine.succeed("nmcli -t -g NAME connection show").split()

    with subtest("grog sees the networks around, through its hands"):
        def both_seen(last):
            status, answer = wifi({"rescan": True})
            if last:
                print("what grog last said:", status, answer)
                print(machine.succeed("nmcli device status; nmcli -t device wifi list --rescan no || true"))
            return status == 200 and {"grog-test", "grog-open"} <= set(networks(answer))
        retry(both_seen, timeout_seconds=90)
        status, answer = wifi({})
        seen = networks(answer)
        assert (status, answer["radio"]) == (200, "on"), (status, answer)
        assert [d["device"] for d in answer["devices"]] == ["wlan1"], answer
        protected, open_one = seen["grog-test"], seen["grog-open"]
        assert (protected["security"], protected["saved"], protected["active"]) == ("password", False, False), protected
        assert (open_one["security"], open_one["device"]) == ("open", "wlan1"), open_one

    with subtest("without its password, it asks for it"):
        status, answer = wifi({"connect": "grog-test"})
        assert status == 400 and answer["error"] == "that network needs its password", (status, answer)

    with subtest("a wrong password: said plainly, nothing kept, never logged"):
        status, answer = wifi({"connect": "grog-test", "password": "not the one"})
        assert status == 400 and answer["error"] == "the password didn't work", (status, answer)
        assert "grog-test" not in saved(), saved()
        machine.fail("journalctl --no-pager | grep -F 'not the one'")

    with subtest("the right one joins it, kept for any Wi-Fi device"):
        status, answer = wifi({"connect": "grog-test", "password": "${password}"})
        assert status == 200 and answer["joined"] == "grog-test", (status, answer)
        joined = networks(answer)["grog-test"]
        assert joined["active"] and joined["saved"], joined
        machine.succeed("nmcli -t -f GENERAL.STATE device show wlan1 | grep -q '(connected)'")
        bound = machine.succeed("nmcli -g connection.interface-name connection show grog-test").strip()
        assert bound == "", bound
        machine.fail("journalctl --no-pager | grep -F \"${password}\"")

    with subtest("an open network, then the saved one again, without its password"):
        status, answer = wifi({"connect": "grog-open"})
        assert status == 200 and networks(answer)["grog-open"]["active"], (status, answer)
        status, answer = wifi({"connect": "grog-test"})
        assert status == 200 and networks(answer)["grog-test"]["active"], (status, answer)
        assert not networks(answer)["grog-open"]["active"], answer
  '';
}

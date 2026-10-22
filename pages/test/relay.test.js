import { test } from "node:test";
import assert from "node:assert/strict";
import { addressOf, isPrivate, problem, target } from "../oauth/callback/relay.js";

test("the address in a state", () => {
  assert.equal(addressOf("192.168.1.20:8485~abc_DEF-123"), "192.168.1.20:8485");
  assert.equal(addressOf("grognix.local~x"), "grognix.local");
  assert.equal(addressOf("[fd00::1]:8485~x"), "[fd00::1]:8485");
});

test("malformed states", () => {
  for (const state of [undefined, null, "", "abc", "~abc", "grognix~", "grognix~a b", "grognix~a/b", "grognix~a~b"]) {
    assert.equal(addressOf(state), null, String(state));
  }
});

test("private addresses", () => {
  for (const address of [
    "10.0.0.1",
    "10.255.255.255:8485",
    "172.16.0.1",
    "172.31.255.254:1",
    "192.168.1.20:8485",
    "127.0.0.1:65535",
    "169.254.10.10",
    "grognix",
    "localhost:8485",
    "grognix.local",
    "GrogNix.Local:8485",
    "my-grog.home.local",
    "[fd12:3456:789a::1]:8485",
    "[fc00::1]",
    "[fe80::1ff:fe23:4567:890a]:8485",
    "[febf::1]",
    "[::1]",
    "[::1]:8485",
  ]) {
    assert.equal(isPrivate(address), true, address);
  }
});

test("public or odd addresses", () => {
  for (const address of [
    "8.8.8.8",
    "172.15.0.1",
    "172.32.0.1",
    "192.169.0.1",
    "11.0.0.1",
    "1.2.3.4:8485",
    "010.0.0.1",
    "10.0.0.256",
    "10.0.0",
    "3232235777",
    "0x7f000001",
    "example.com",
    "evil.com.local.example.com",
    "grognix.local.evil.com",
    "10.0.0.1.evil.com",
    "evil.com/x@10.0.0.1",
    "10.0.0.1@evil.com",
    "grognix:0",
    "grognix:65536",
    "grognix:abc",
    "grognix:",
    "-grognix",
    "[2001:db8::1]",
    "[::ffff:10.0.0.1]",
    "[fec0::1]",
    "[::2]",
    "[fd00::1::2]",
    "[fd00:1:2:3:4:5:6:7:8]",
    "fd00::1",
    "[fe80::1%25eth0]",
    "",
    undefined,
  ]) {
    assert.equal(isPrivate(address), false, String(address));
  }
});

test("the target keeps the query untouched", () => {
  const search = "?code=abc%2Bdef&state=192.168.1.20%3A8485~r4nd0m&iss=https%3A%2F%2Fauth.example.com";
  assert.deepEqual(target(search), {
    url: "http://192.168.1.20:8485/c/callback?code=abc%2Bdef&state=192.168.1.20%3A8485~r4nd0m&iss=https%3A%2F%2Fauth.example.com",
    address: "192.168.1.20:8485",
    reason: null,
  });
  assert.equal(
    target("?state=[fd00::1]:8485~r&code=c").url,
    "http://[fd00::1]:8485/c/callback?state=[fd00::1]:8485~r&code=c",
  );
});

test("errors go back to grog too", () => {
  const search = "?error=access_denied&error_description=No+thanks&state=grognix.local~r";
  assert.equal(target(search).url, `http://grognix.local/c/callback${search}`);
  assert.equal(problem(search), "No thanks");
  assert.equal(problem("?error=access_denied"), "access_denied");
  assert.equal(problem("?code=c"), null);
});

test("no target without a good state", () => {
  for (const search of ["", "?code=c", "?code=c&state=nope", "?code=c&state=example.com~r", "?state=8.8.8.8:80~r"]) {
    const { url, reason } = target(search);
    assert.equal(url, null, search);
    assert.equal(typeof reason, "string");
  }
});

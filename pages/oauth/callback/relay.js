// grog's OAuth relay: an authorization server sends the browser here with a
// code; the state says where grog is on the home network
// (<address>~<random>), and the page sends the browser on to it, the query
// untouched. It only goes to private addresses. The logic is pure and
// exported, for the tests; the page's part is at the end.

const RANDOM = /^[A-Za-z0-9_.-]+$/;
const LABEL = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/i;
// What a browser reads as an IPv4 address when it ends a host name.
const NUMERIC = /^(0x[0-9a-f]*|[0-9]+)$/i;

// The address in `state` (host or host:port, IPv6 in brackets), or null
// when the state isn't <address>~<random>.
export function addressOf(state) {
  if (typeof state !== "string") return null;
  const at = state.indexOf("~");
  if (at <= 0) return null;
  const random = state.slice(at + 1);
  return RANDOM.test(random) ? state.slice(0, at) : null;
}

// {host, port} of `address`, the host of an IPv6 address without its
// brackets, or null when it isn't host[:port].
export function split(address) {
  const m = /^(?:\[([0-9a-fA-F:]+)\]|([^:[\]]+))(?::([0-9]{1,5}))?$/.exec(address);
  if (!m) return null;
  return { host: m[1] ?? m[2], ipv6: m[1] !== undefined, port: m[3] ?? null };
}

function validPort(port) {
  if (port === null) return true;
  const n = Number(port);
  return n >= 1 && n <= 65535;
}

function ipv4(host) {
  const parts = host.split(".");
  if (parts.length !== 4) return null;
  if (!parts.every((p) => /^(0|[1-9][0-9]{0,2})$/.test(p))) return null;
  const octets = parts.map(Number);
  return octets.every((o) => o <= 255) ? octets : null;
}

function privateIPv4([a, b]) {
  return (
    a === 10 ||
    a === 127 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254)
  );
}

// The 8 groups of an IPv6 address, as numbers, or null.
function ipv6(host) {
  const halves = host.split("::");
  if (halves.length > 2) return null;
  const groups = (s) => (s === "" ? [] : s.split(":"));
  const head = groups(halves[0]);
  const tail = halves.length === 2 ? groups(halves[1]) : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 2 ? missing < 1 : missing !== 0) return null;
  const all = [...head, ...Array(halves.length === 2 ? missing : 0).fill("0"), ...tail];
  if (!all.every((g) => /^[0-9a-f]{1,4}$/i.test(g))) return null;
  return all.map((g) => parseInt(g, 16));
}

function privateIPv6(groups) {
  const loopback = groups.slice(0, 7).every((g) => g === 0) && groups[7] === 1;
  return loopback || (groups[0] & 0xfe00) === 0xfc00 || (groups[0] & 0xffc0) === 0xfe80;
}

function privateName(host) {
  const labels = host.split(".");
  if (!labels.every((l) => LABEL.test(l))) return false;
  if (NUMERIC.test(labels[labels.length - 1])) return false;
  return labels.length === 1 || (labels.length >= 2 && labels[labels.length - 1].toLowerCase() === "local");
}

// Whether `address` is on the home network: a private, loopback or
// link-local IPv4 address, a single-label or .local name, or a ULA,
// link-local or loopback IPv6 address in brackets; with a port or not.
export function isPrivate(address) {
  const parts = typeof address === "string" ? split(address) : null;
  if (!parts || !validPort(parts.port)) return false;
  if (parts.ipv6) {
    const groups = ipv6(parts.host);
    return groups !== null && privateIPv6(groups);
  }
  const octets = ipv4(parts.host);
  if (octets) return privateIPv4(octets);
  if (/^[0-9.]+$/.test(parts.host)) return false;
  return privateName(parts.host);
}

// Where to send the browser for the query `search` (location.search):
// {url, address} on grog's page, or {url: null, reason} saying why not.
export function target(search) {
  const query = typeof search === "string" ? search.replace(/^\?/, "") : "";
  const refuse = (reason) => ({ url: null, address: null, reason });
  const state = new URLSearchParams(query).get("state");
  if (!state) return refuse("There's no state in this address: it didn't come from grog.");
  const address = addressOf(state);
  if (address === null) return refuse("The state in this address isn't one grog makes.");
  if (!isPrivate(address)) return refuse(`${address} isn't on a home network: this page only sends you to grog there.`);
  return { url: `http://${address}/c/callback?${query}`, address, reason: null };
}

// What the authorization server said went wrong, or null.
export function problem(search) {
  const params = new URLSearchParams(typeof search === "string" ? search : "");
  const error = params.get("error");
  return error ? params.get("error_description") || error : null;
}

function show(document, id, text) {
  const el = document.getElementById(id);
  el.textContent = text;
  el.hidden = false;
}

async function copy(document, field, button) {
  try {
    await navigator.clipboard.writeText(field.value);
  } catch {
    field.focus();
    field.select();
    document.execCommand("copy");
  }
  button.textContent = "Copied";
}

function start(document, location) {
  const { url, address, reason } = target(location.search);
  const field = document.getElementById("address");
  field.value = location.href;
  const button = document.getElementById("copy");
  button.addEventListener("click", () => copy(document, field, button));
  field.addEventListener("focus", () => field.select());
  const wrong = problem(location.search);
  if (wrong) show(document, "problem", `The sign-in said: ${wrong}`);
  if (url) {
    show(document, "status", `Taking you back to grog at ${address}…`);
    location.replace(url);
  } else {
    show(document, "status", reason);
    document.body.dataset.state = "refused";
  }
}

if (typeof document !== "undefined" && typeof location !== "undefined") start(document, location);

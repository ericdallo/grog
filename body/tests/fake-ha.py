"""A fake Home Assistant for the VM tests (`fake-ha.py PORT STATE_DIR`), as
much of it as grog talks to: signing in (/auth/authorize answers at once,
as if someone had logged in, /auth/token, /auth/revoke), /api/config, and
its MCP server (/api/mcp/assist, and /api/mcp: stateless JSON-RPC over
POST, the 2025-06-18 handshake), whose tools are named and annotated like
the real ones. Everything under /api wants a token it gave, or the
long-lived one (LONG_LIVED). Each tool call is noted in calls.log, and each
revoked token in revoked.log, in STATE_DIR, for the tests to see. It keeps
connections open between requests, over HTTP/1.1, as Home Assistant's
aiohttp does."""

import base64
import hashlib
import json
import os
import secrets
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

LONG_LIVED = "test-long-lived-token"
REFRESH = "test-ha-refresh-token"

TOOLS = [
    {
        "name": "intent__HassTurnOn",
        "title": "Turn on",
        "description": "Turns on/opens/presses a device or entity.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "area": {"type": "string"},
                "domain": {"type": "array", "items": {"type": "string"}},
            },
        },
        "annotations": {"openWorldHint": False},
    },
    {
        "name": "intent__HassTurnOff",
        "title": "Turn off",
        "description": "Turns off/closes a device or entity.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "area": {"type": "string"},
                "domain": {"type": "array", "items": {"type": "string"}},
            },
        },
        "annotations": {"idempotentHint": True, "openWorldHint": False},
    },
    {
        "name": "light__HassLightSet",
        "title": "Set a light",
        "description": "Sets the brightness percentage or color of a light.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "area": {"type": "string"},
                "brightness": {"type": "integer", "minimum": 0, "maximum": 100},
                "color": {"type": "string"},
                "temperature": {"type": "integer"},
            },
        },
        "annotations": {"idempotentHint": True},
    },
    {
        "name": "homeassistant__GetLiveContext",
        "title": "Get live context",
        "description": "Provides real-time information about the devices in the home.",
        "inputSchema": {"type": "object", "properties": {}},
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
]

SAID = {
    "intent__HassTurnOn": "Turned on the lights",
    "intent__HassTurnOff": "Turned off the lights",
    "light__HassLightSet": "Brightness set",
}

lock = threading.Lock()
codes = {}
tokens = {LONG_LIVED}


def note(name, line):
    with lock, open(os.path.join(STATE_DIR, name), "a") as f:
        f.write(line + "\n")


def origin(url):
    u = urlsplit(url)
    return (u.scheme, u.hostname, u.port)


def s256(verifier):
    digest = hashlib.sha256(verifier.encode()).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def form_fields(body):
    """A form-encoded body as a dict, the first value of each field."""
    return {k: v[0] for k, v in parse_qs(body.decode()).items()}


def call(name, arguments):
    note("calls.log", f"{name} {json.dumps(arguments, sort_keys=True)}")
    if name == "homeassistant__GetLiveContext":
        text = {"success": True, "result": "Sala: light Teto is on"}
    else:
        text = {
            "speech": {"plain": {"speech": SAID[name]}},
            "response_type": "action_done",
            "data": {"success": [{"name": arguments.get("area") or arguments.get("name") or "?"}]},
        }
    return {"content": [{"type": "text", "text": json.dumps(text)}], "isError": False}


def result(method, params):
    """The result of request `method`, or an error: (result, error)."""
    if method == "initialize":
        return {
            "protocolVersion": "2025-06-18",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "home-assistant", "version": "2026.10.0"},
            "instructions": "Home Assistant",
        }, None
    if method == "tools/list":
        return {"tools": TOOLS}, None
    if method == "tools/call":
        name = params.get("name")
        if name not in SAID and name != "homeassistant__GetLiveContext":
            return None, {"code": -32602, "message": f"Tool {name} not found"}
        return call(name, params.get("arguments") or {}), None
    if method == "ping":
        return {}, None
    return None, {"code": -32601, "message": "Method not found"}


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.0 would close each connection after its answer without saying
    # so, and grog's client, keeping it, could send its next request on it:
    # a tool call, which grog never sends twice, then fails.
    protocol_version = "HTTP/1.1"

    def send(self, status, body=b"", content_type="application/json", headers=()):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(status)
        if body:
            self.send_header("Content-Type", content_type)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_body(self):
        """The request's body, read whole, so that the connection's next request starts after it."""
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def authorized(self):
        """Whether it carries a token this Home Assistant gave, else answers 401."""
        auth = self.headers.get("Authorization") or ""
        with lock:
            ok = auth.startswith("Bearer ") and auth[len("Bearer ") :] in tokens
        if not ok:
            base = f"http://{self.headers.get('Host')}"
            self.send(
                401,
                {"message": "Unauthorized"},
                headers=[
                    ("WWW-Authenticate", f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource"')
                ],
            )
        return ok

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/auth/authorize":
            q = {k: v[0] for k, v in parse_qs(url.query).items()}
            client, redirect = q.get("client_id", ""), q.get("redirect_uri", "")
            if (
                q.get("response_type") != "code"
                or not client
                or origin(client) != origin(redirect)
                or q.get("code_challenge_method") != "S256"
                or not q.get("code_challenge")
            ):
                self.send(400, {"error": "invalid_request", "got": q})
                return
            code = secrets.token_urlsafe(16)
            with lock:
                codes[code] = (client, q["code_challenge"])
            back = redirect + ("&" if "?" in redirect else "?") + urlencode({"code": code, "state": q.get("state", "")})
            self.send(302, headers=[("Location", back)])
        elif url.path == "/api/config":
            if self.authorized():
                self.send(200, {"location_name": "Casa", "version": "2026.10.0"})
        elif url.path.startswith("/api/"):
            if self.authorized():
                self.send(405)
        else:
            self.send(404)

    def do_POST(self):
        path = urlsplit(self.path).path
        body = self.read_body()
        if path == "/auth/token":
            self.token(form_fields(body))
        elif path == "/auth/revoke":
            note("revoked.log", form_fields(body).get("token", ""))
            self.send(200)
        elif path in ("/api/mcp/assist", "/api/mcp"):
            if self.authorized():
                self.mcp(body)
        else:
            self.send(404)

    def token(self, form):
        grant = form.get("grant_type")
        if grant == "authorization_code":
            with lock:
                client, challenge = codes.pop(form.get("code", ""), (None, None))
            if not client or client != form.get("client_id") or s256(form.get("code_verifier", "")) != challenge:
                self.send(400, {"error": "invalid_request"})
                return
            access = secrets.token_urlsafe(16)
            with lock:
                tokens.add(access)
            self.send(
                200,
                {"access_token": access, "token_type": "Bearer", "refresh_token": REFRESH, "expires_in": 1800},
            )
        elif grant == "refresh_token" and form.get("refresh_token") == REFRESH and form.get("client_id"):
            access = secrets.token_urlsafe(16)
            with lock:
                tokens.add(access)
            self.send(200, {"access_token": access, "token_type": "Bearer", "expires_in": 1800})
        else:
            self.send(400, {"error": "invalid_grant"})

    def mcp(self, body):
        if "application/json" not in (self.headers.get("Accept") or ""):
            self.send(406, {"message": "Accept application/json"})
            return
        try:
            msg = json.loads(body)
        except ValueError:
            self.send(400)
            return
        if not isinstance(msg, dict) or "id" not in msg:
            self.send(202)
            return
        res, err = result(msg.get("method"), msg.get("params") or {})
        out = {"jsonrpc": "2.0", "id": msg["id"]}
        if err:
            out["error"] = err
        else:
            out["result"] = res
        self.send(200, out)

    def do_DELETE(self):
        self.read_body()
        self.send(405)


if __name__ == "__main__":
    STATE_DIR = sys.argv[2]
    ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), Handler).serve_forever()

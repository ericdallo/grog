"""A fake MCP server for the VM tests, speaking 2026-07-28: over stdio
(newline-delimited JSON, run as a fake `npx --yes fake-mcp@1.0.0`), or over
Streamable HTTP (`--http PORT`: POST /mcp, JSON answers). Its tools: lamp_on
(changes things, not destructive), alarm_off (no annotations: destructive),
whoami (read-only: its uid, and whether it can read grog's state). Each call
is noted in calls.log, in $HOME, for the tests to see what ran."""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {
        "name": "lamp_on",
        "title": "Turn a lamp on",
        "description": "Turns the lamp of a room on.",
        "inputSchema": {"type": "object", "properties": {"room": {"type": "string"}}},
        "annotations": {"readOnlyHint": False, "destructiveHint": False},
    },
    {
        "name": "alarm_off",
        "description": "Turns the alarm off.",
        "inputSchema": {"type": "object", "properties": {"room": {"type": "string"}}},
    },
    {
        "name": "whoami",
        "description": "Who runs this server.",
        "inputSchema": {"type": "object", "properties": {}},
        "annotations": {"readOnlyHint": True},
    },
]


def grog_state():
    """Whether this process can read grog's state."""
    for path in ("/var/lib/grog", "/var/lib/private/grog"):
        try:
            os.listdir(path)
            return f"{path} is readable"
        except OSError as e:
            last = type(e).__name__
    return f"grog's state isn't readable ({last})"


def note(name, arguments):
    log = os.path.join(os.environ.get("HOME", "/tmp"), "calls.log")
    with open(log, "a") as f:
        f.write(f"{name} {json.dumps(arguments, sort_keys=True)}\n")


def call(name, arguments):
    note(name, arguments)
    room = arguments.get("room") or "the room"
    if name == "lamp_on":
        return f"the lamp is on in {room}"
    if name == "alarm_off":
        return f"the alarm is off in {room}"
    if name == "whoami":
        return f"uid={os.getuid()}; {grog_state()}"
    return None


def result(method, params):
    """The result of request `method`, or an error: (result, error)."""
    if method == "server/discover":
        return {
            "resultType": "complete",
            "supportedVersions": ["2026-07-28"],
            "capabilities": {"tools": {}},
            "ttlMs": 0,
            "cacheScope": "private",
            "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "fake", "version": "1.0.0"}},
        }, None
    if method == "tools/list":
        return {"resultType": "complete", "tools": TOOLS, "ttlMs": 0}, None
    if method == "tools/call":
        text = call(params.get("name"), params.get("arguments") or {})
        if text is None:
            return None, {"code": -32602, "message": "Unknown tool"}
        return {"resultType": "complete", "content": [{"type": "text", "text": text}]}, None
    if method == "ping":
        return {}, None
    return None, {"code": -32601, "message": "Method not found"}


def respond(msg):
    """The response to `msg`, or None for a notification."""
    if not isinstance(msg, dict) or "id" not in msg or "method" not in msg:
        return None
    res, err = result(msg["method"], msg.get("params") or {})
    out = {"jsonrpc": "2.0", "id": msg["id"]}
    if err:
        out["error"] = err
    else:
        out["result"] = res
    return out


def stdio():
    while True:
        line = sys.stdin.readline()
        if not line:
            return
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        out = respond(msg)
        if out is not None:
            sys.stdout.write(json.dumps(out) + "\n")
            sys.stdout.flush()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/mcp":
            self.send_error(404)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try:
            out = respond(json.loads(body))
        except ValueError:
            self.send_error(400)
            return
        if out is None:
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = json.dumps(out).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.send_error(405)

    def do_DELETE(self):
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


if __name__ == "__main__":
    if sys.argv[1:2] == ["--http"]:
        ThreadingHTTPServer(("127.0.0.1", int(sys.argv[2])), Handler).serve_forever()
    elif sys.argv[1:] == ["--yes", "fake-mcp@1.0.0"]:
        stdio()
    else:
        sys.exit(f"fake npx: unexpected arguments {sys.argv[1:]}")

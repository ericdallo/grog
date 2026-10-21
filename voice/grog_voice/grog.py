"""Client of grog's HTTP API: GET /v1/status, POST /v1/chat, whose answer
streams as server-sent events, and POST /v1/voice, what grog-voice is
doing."""

import http.client
import json
import urllib.parse

# What a connection to grog may fail with: HTTPException (a bad status line,
# a cut answer) isn't an OSError.
_UNREACHABLE = (OSError, http.client.HTTPException)


class GrogError(Exception):
    """grog couldn't answer."""


def _connect(url, timeout):
    parts = urllib.parse.urlsplit(url)
    connection = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    return connection(parts.hostname, parts.port, timeout=timeout), parts.path.rstrip("/")


def _error(resp):
    body = resp.read().decode("utf-8", "replace")
    try:
        return json.loads(body)["error"]
    except (ValueError, KeyError, TypeError):
        return f"grog answered {resp.status}: {body.strip()}"


def status(url, timeout=5):
    """grog's status: its name, provider, model and credentials."""
    conn, path = _connect(url, timeout)
    try:
        conn.request("GET", path + "/v1/status")
        resp = conn.getresponse()
        if resp.status != 200:
            raise GrogError(_error(resp))
        return json.loads(resp.read())
    except (*_UNREACHABLE, ValueError) as e:
        raise GrogError(f"Couldn't reach grog at {url}: {e}") from e
    finally:
        conn.close()


def report(url, state, conversation_id, heard=None, timeout=2):
    """Tells grog what grog-voice is doing (`state`, see reporter.py) in its
    conversation, and what it `heard` for grog ({"text", "final"}), if
    anything. Returns grog's answer: whether it shows that, and its name
    ({"captions", "name"}). Raises GrogError when it can't."""
    conn, path = _connect(url, timeout)
    body = json.dumps(
        {"state": state, "conversationId": conversation_id} | ({"heard": heard} if heard is not None else {})
    )
    try:
        conn.request("POST", path + "/v1/voice", body, {"Content-Type": "application/json"})
        resp = conn.getresponse()
        if resp.status != 200:
            raise GrogError(_error(resp))
        try:
            answer = json.loads(resp.read())
        except ValueError:
            answer = None
        return answer if isinstance(answer, dict) else {}
    except _UNREACHABLE as e:
        raise GrogError(f"Couldn't reach grog at {url}: {e}") from e
    finally:
        conn.close()


def _events(resp):
    """The server-sent events of `resp`, as (type, data)."""
    buf = b""
    while chunk := resp.read1(4096):
        buf += chunk
        while b"\n\n" in buf:
            block, buf = buf.split(b"\n\n", 1)
            fields = [line.split(":", 1) for line in block.decode("utf-8").splitlines() if ":" in line]
            event = next((v.strip() for k, v in fields if k == "event"), "message")
            data = "\n".join(v[1:] if v.startswith(" ") else v for k, v in fields if k == "data")
            yield event, json.loads(data) if data else {}


def ask(url, conversation_id, message, said=None, timeout=90):
    """Yields grog's answer to `message` as it streams in: ("text", text) and
    ("tool", name) when the model uses a tool, like "web_search". `said`:
    what was said aloud, as heard, the wake word included, if grog may see it.
    Raises GrogError when grog can't answer, also midway."""
    conn, path = _connect(url, timeout)
    body = json.dumps(
        {"message": message, "conversationId": conversation_id} | ({"said": said} if said is not None else {})
    )
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    try:
        try:
            conn.request("POST", path + "/v1/chat", body, headers)
            resp = conn.getresponse()
            if resp.status != 200:
                raise GrogError(_error(resp))
        except _UNREACHABLE as e:
            raise GrogError(f"Couldn't reach grog at {url}: {e}") from e
        try:
            for event, data in _events(resp):
                if event == "text":
                    yield "text", data["text"]
                elif event == "tool":
                    yield "tool", data["name"]
                elif event == "error":
                    raise GrogError(data["error"])
                elif event == "done":
                    return
        except (*_UNREACHABLE, ValueError, KeyError) as e:
            raise GrogError(f"grog stopped answering: {e}") from e
        raise GrogError("grog stopped answering midway")
    finally:
        conn.close()

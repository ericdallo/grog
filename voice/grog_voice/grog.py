"""Client of grog's HTTP API: GET /v1/status, POST /v1/chat, whose answer
streams as server-sent events, POST /v1/voice, what grog-voice is doing,
GET /v1/voice/commands, what grog asks of it, POST /v1/voice/speakers, the
speakers it asked for, and POST /v1/actions/mic, switching its mic (from a
mic array's mute button, see ring.py)."""

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


def report(url, state, conversation_id, heard=None, direction=None, timeout=2):
    """Tells grog what grog-voice is doing (`state`, see reporter.py) in its
    conversation, what it `heard` for grog ({"text", "final"}), and where
    who talks to grog is (`direction`, see ring.py), if anything. Returns
    grog's answer: whether it shows what's heard, and its name ({"captions",
    "name"}). Raises GrogError when it can't."""
    conn, path = _connect(url, timeout)
    body = json.dumps(
        {"state": state, "conversationId": conversation_id}
        | ({"heard": heard} if heard is not None else {})
        | ({"direction": direction} if direction is not None else {})
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


def _post(url, route, body, timeout):
    """POSTs `body` as JSON to grog's `route`, its answer read and dropped.
    Raises GrogError when it can't."""
    conn, path = _connect(url, timeout)
    try:
        conn.request("POST", path + route, json.dumps(body), {"Content-Type": "application/json"})
        resp = conn.getresponse()
        if resp.status != 200:
            raise GrogError(_error(resp))
        resp.read()
    except _UNREACHABLE as e:
        raise GrogError(f"Couldn't reach grog at {url}: {e}") from e
    finally:
        conn.close()


def report_speakers(url, answer, timeout=5):
    """Tells grog which speakers there are, as it asked (`answer`: {"id",
    "speakers"} or {"id", "error"}, see speakers.py). Raises GrogError when
    it can't."""
    _post(url, "/v1/voice/speakers", answer, timeout)


def set_mic(url, on, timeout=2):
    """Asks grog to switch its mic on or off, as someone pressed a mic
    array's mute button (see ring.py): grog keeps it, and tells grog-voice
    (GET /v1/voice/commands). Raises GrogError when it can't."""
    _post(url, "/v1/actions/mic", {"on": on}, timeout)


def _events(resp):
    """The server-sent events of `resp`, as (type, data). Comments (pings)
    aren't events."""
    buf = b""
    while chunk := resp.read1(4096):
        buf += chunk
        while b"\n\n" in buf:
            block, buf = buf.split(b"\n\n", 1)
            lines = [line for line in block.decode("utf-8").splitlines() if not line.startswith(":")]
            fields = [line.split(":", 1) for line in lines if ":" in line]
            if not fields:
                continue
            event = next((v.strip() for k, v in fields if k == "event"), "message")
            data = "\n".join(v[1:] if v.startswith(" ") else v for k, v in fields if k == "data")
            yield event, json.loads(data) if data else {}


def ask(url, conversation_id, message, said=None, cut_off=None, timeout=90, on_connect=None):
    """Yields grog's answer to `message` as it streams in: ("text", text) and
    ("tool", name) when the model uses a tool, like "web_search". `said`:
    what was said aloud, as heard, the wake word included, if grog may see it.
    `cut_off`: how much of grog's last answer was said aloud before it was
    cut off, if it was (it may be nothing). Raises GrogError when grog can't
    answer, also midway. `on_connect` gets the connection once it's asked:
    shutting its socket down, from another thread, stops the answer (and
    grog's turn)."""
    conn, path = _connect(url, timeout)
    body = json.dumps(
        {"message": message, "conversationId": conversation_id}
        | ({"said": said} if said is not None else {})
        | ({"cutOff": cut_off} if cut_off is not None else {})
    )
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    try:
        try:
            conn.request("POST", path + "/v1/chat", body, headers)
            if on_connect is not None:
                on_connect(conn)
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


def commands(url, timeout=60):
    """Yields what grog asks of grog-voice as it comes: ("snapshot", {"mic":
    "on" or "off", "music", "speaker"}) first, then ("mic", {"mic"}),
    ("music", {"music"}) or ("speaker", {"speaker"}) when one changes,
    ("listen", {}), ("stop", {}) and ("speakers", {"id", "music"?}). grog
    pings every 15 s: past `timeout` without a word from it, it's gone.
    Raises GrogError when grog can't be reached, or goes away."""
    conn, path = _connect(url, timeout)
    try:
        try:
            conn.request("GET", path + "/v1/voice/commands", headers={"Accept": "text/event-stream"})
            resp = conn.getresponse()
            if resp.status != 200:
                raise GrogError(_error(resp))
        except _UNREACHABLE as e:
            raise GrogError(f"Couldn't reach grog at {url}: {e}") from e
        try:
            yield from _events(resp)
        except (*_UNREACHABLE, ValueError) as e:
            raise GrogError(f"grog stopped saying what to do: {e}") from e
        raise GrogError("grog went away")
    finally:
        conn.close()

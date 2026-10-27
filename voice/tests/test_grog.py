import json
import socket
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from grog_voice.grog import GrogError, ask, commands, report, report_speakers, set_mic, status


def event(kind, data):
    # Like grog's (cheshire's) JSON: UTF-8, not \u escapes.
    return f"event: {kind}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


def fake_grog(test, status=200, chunks=(), error=None, status_body=None, voice_body=None, commands=None):
    """grog's API on a free port. POST /v1/chat answers `status`, then `chunks`
    (bytes, or seconds to wait) one by one with chunked transfer encoding, or
    `error` as JSON; GET /v1/status answers `status_body`, POST /v1/voice
    `voice_body` when given, GET /v1/voice/commands `commands` (chunks),
    when given. Returns (url, requests)."""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def reply_json(self, code, body):
            payload = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def stream(self, chunks):
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for chunk in chunks:
                if isinstance(chunk, float):
                    time.sleep(chunk)
                    continue
                self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")

        def do_GET(self):
            if commands is not None and self.path == "/v1/voice/commands":
                requests.append({"path": self.path, "accept": self.headers["Accept"]})
                self.send_response(200)
                self.stream(commands)
                return
            self.reply_json(200, status_body)

        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            requests.append(
                {
                    "path": self.path,
                    "content_type": self.headers["Content-Type"],
                    "accept": self.headers["Accept"],
                    "body": json.loads(body),
                }
            )
            if error is not None:
                self.reply_json(status, {"error": error})
                return
            if voice_body is not None and self.path == "/v1/voice":
                self.reply_json(200, voice_body)
                return
            self.send_response(status)
            self.stream(chunks)

        def log_message(self, *args):
            pass

    class Server(ThreadingHTTPServer):
        def handle_error(self, request, client_address):
            # Clients that stop reading early, as some tests do, hang up.
            if not isinstance(sys.exc_info()[1], ConnectionError):
                super().handle_error(request, client_address)

    server = Server(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    test.addCleanup(server.server_close)
    test.addCleanup(server.shutdown)
    return f"http://127.0.0.1:{server.server_address[1]}", requests


class AskTest(unittest.TestCase):
    def test_streams_the_answer_and_the_tools_used(self):
        stream = (
            event("tool", {"name": "web_search"})
            + event("text", {"text": "De boa! "})
            + event("text", {"text": "E você?"})
            + event("done", {})
        )
        # Events split anywhere, even within "ê".
        cut = stream.index("ê".encode()) + 1
        url, requests = fake_grog(self, chunks=[stream[:20], stream[20:cut], stream[cut:]])
        self.assertEqual(
            [("tool", "web_search"), ("text", "De boa! "), ("text", "E você?")],
            list(ask(url, "voice", "tudo bem?")),
        )
        self.assertEqual(
            [
                {
                    "path": "/v1/chat",
                    "content_type": "application/json",
                    "accept": "text/event-stream",
                    "body": {"message": "tudo bem?", "conversationId": "voice"},
                }
            ],
            requests,
        )

    def test_says_what_was_said_when_given(self):
        url, requests = fake_grog(self, chunks=[event("done", {})])
        list(ask(url, "voice", "que horas são?", said="Grog, que horas são?"))
        self.assertEqual(
            {"message": "que horas são?", "conversationId": "voice", "said": "Grog, que horas são?"},
            requests[0]["body"],
        )

    def test_error_before_the_answer(self):
        url, _ = fake_grog(self, status=502, error="OpenAI API error: boom")
        with self.assertRaisesRegex(GrogError, "^OpenAI API error: boom$"):
            list(ask(url, "voice", "oi"))

    def test_error_midway(self):
        url, _ = fake_grog(
            self,
            chunks=[
                event("text", {"text": "Amanhã deve"}),
                event("error", {"error": "The answer was cut off."}),
            ],
        )
        events = []
        with self.assertRaisesRegex(GrogError, "^The answer was cut off.$"):
            for e in ask(url, "voice", "previsão?"):
                events.append(e)
        self.assertEqual([("text", "Amanhã deve")], events)

    def test_an_answer_without_its_end_is_an_error(self):
        url, _ = fake_grog(self, chunks=[event("text", {"text": "Amanhã"})])
        with self.assertRaisesRegex(GrogError, "stopped answering"):
            list(ask(url, "voice", "previsão?"))

    def test_hanging_up_stops_the_answer(self):
        url, _ = fake_grog(self, chunks=[event("text", {"text": "Amanhã"}), 3.0, event("done", {})])
        connections, got = [], []
        started = time.monotonic()
        with self.assertRaisesRegex(GrogError, "stopped answering"):
            for e in ask(url, "voice", "previsão?", on_connect=connections.append):
                got.append(e)
                connections[0].sock.shutdown(socket.SHUT_RDWR)
        self.assertEqual([("text", "Amanhã")], got)
        self.assertLess(time.monotonic() - started, 2, "right away")

    def test_unreachable(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        with self.assertRaisesRegex(GrogError, f"Couldn't reach grog at http://127.0.0.1:{port}"):
            list(ask(f"http://127.0.0.1:{port}", "voice", "oi"))
        with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
            status(f"http://127.0.0.1:{port}")


def garbling(test, reply):
    """A server answering `reply` to anything, then hanging up: not HTTP."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen()
    test.addCleanup(server.close)

    def serve():
        while True:
            try:
                conn, _ = server.accept()
            except OSError:
                return
            with conn:
                conn.recv(65536)
                conn.sendall(reply)

    threading.Thread(target=serve, daemon=True).start()
    return f"http://127.0.0.1:{server.getsockname()[1]}"


class GarbledTest(unittest.TestCase):
    def test_an_answer_that_isnt_http_is_an_error(self):
        for reply in (b"garbage\r\n\r\n", b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n{}"):
            url = garbling(self, reply)
            with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
                status(url)
            with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
                report(url, "listening", "voice")
            with self.assertRaises(GrogError):
                list(ask(url, "voice", "oi"))


class CommandsTest(unittest.TestCase):
    def test_what_grog_asks_as_it_comes(self):
        said = (
            event("snapshot", {"mic": "on"})
            + b": ping\n\n"
            + event("listen", {})
            + event("mic", {"mic": "off"})
        )
        url, requests = fake_grog(self, commands=[said])
        got = []
        with self.assertRaisesRegex(GrogError, "^grog went away$"):
            for e in commands(url):
                got.append(e)
        self.assertEqual(
            [("snapshot", {"mic": "on"}), ("listen", {}), ("mic", {"mic": "off"})], got, "pings aren't"
        )
        self.assertEqual([{"path": "/v1/voice/commands", "accept": "text/event-stream"}], requests)

    def test_unreachable(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
            list(commands(f"http://127.0.0.1:{port}"))


class StatusTest(unittest.TestCase):
    def test_status(self):
        url, _ = fake_grog(self, status_body={"name": "Grog", "provider": "openai"})
        self.assertEqual({"name": "Grog", "provider": "openai"}, status(url))


class ReportTest(unittest.TestCase):
    def test_tells_grog_what_it_does(self):
        url, requests = fake_grog(self)
        report(url, "hearing", "voice")
        self.assertEqual(
            [
                {
                    "path": "/v1/voice",
                    "content_type": "application/json",
                    "accept": None,
                    "body": {"state": "hearing", "conversationId": "voice"},
                }
            ],
            requests,
        )

    def test_grog_says_whether_it_shows_what_it_hears(self):
        url, _ = fake_grog(self, voice_body={"captions": True, "name": "Grog"})
        self.assertEqual({"captions": True, "name": "Grog"}, report(url, "hearing", "voice"))
        url, _ = fake_grog(self)
        self.assertEqual({}, report(url, "hearing", "voice"), "an older grog says nothing")

    def test_and_what_it_heard(self):
        url, requests = fake_grog(self)
        report(url, "hearing", "voice", {"text": "Grog, que horas são?", "final": True})
        self.assertEqual(
            {
                "state": "hearing",
                "conversationId": "voice",
                "heard": {"text": "Grog, que horas são?", "final": True},
            },
            requests[0]["body"],
        )

    def test_and_where_who_talks_to_it_is(self):
        url, requests = fake_grog(self)
        report(url, "hearing", "voice", direction=-25)
        expected = {"state": "hearing", "conversationId": "voice", "direction": -25}
        self.assertEqual(expected, requests[0]["body"])

    def test_refused(self):
        url, _ = fake_grog(self, status=400, error='Send {"state": ...}')
        with self.assertRaisesRegex(GrogError, "^Send"):
            report(url, "dancing", "voice")

    def test_unreachable(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
            report(f"http://127.0.0.1:{port}", "listening", "voice")


class ReportSpeakersTest(unittest.TestCase):
    def test_tells_grog_which_speakers_there_are(self):
        url, requests = fake_grog(self)
        speakers = [{"name": "bt", "label": "EDIFIER", "kind": "bluetooth", "default": True}]
        report_speakers(url, {"id": 3, "speakers": speakers})
        self.assertEqual(
            [
                {
                    "path": "/v1/voice/speakers",
                    "content_type": "application/json",
                    "accept": None,
                    "body": {"id": 3, "speakers": speakers},
                }
            ],
            requests,
        )

    def test_refused(self):
        url, _ = fake_grog(self, status=400, error='Send {"id": ...}')
        with self.assertRaisesRegex(GrogError, "^Send"):
            report_speakers(url, {"id": "x"})
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        with self.assertRaisesRegex(GrogError, "Couldn't reach grog"):
            report_speakers(f"http://127.0.0.1:{port}", {"id": 1, "error": "pw-dump exited with 1"})


class SetMicTest(unittest.TestCase):
    def test_asks_grog_to_switch_its_mic(self):
        url, requests = fake_grog(self)
        set_mic(url, False)
        set_mic(url, True)
        self.assertEqual(
            [
                {"path": "/v1/actions/mic", "content_type": "application/json", "accept": None, "body": body}
                for body in ({"on": False}, {"on": True})
            ],
            requests,
        )

    def test_refused(self):
        url, _ = fake_grog(self, status=400, error='say whether it\'s on: {"on": true or false}')
        with self.assertRaisesRegex(GrogError, "^say whether"):
            set_mic(url, False)


if __name__ == "__main__":
    unittest.main()

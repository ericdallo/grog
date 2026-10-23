"""Command line: `grog-music --socket PATH`, the service; `grog-music
--socket PATH send JSON`, one request to it, to try it out."""

import argparse
import functools
import json
import logging
import os
import signal
import sys
import tempfile
import threading

from . import app, config
from .mpv import Mpv, MpvError
from .player import Player
from .server import Server, ask
from .youtube_music import YouTubeMusic, ytmusic

log = logging.getLogger("grog_music")

# How often it checks that mpv still runs.
WATCH_EVERY_S = 2.0


def mpv_socket(settings, environ=os.environ):
    """mpv's control socket: mpvSocket, else grog-music/mpv.sock in
    $XDG_RUNTIME_DIR (made only its user's), else in a new temporary
    directory."""
    if settings["mpvSocket"]:
        return settings["mpvSocket"]
    runtime = environ.get("XDG_RUNTIME_DIR")
    if not runtime:
        return os.path.join(tempfile.mkdtemp(prefix="grog-music-"), "mpv.sock")
    directory = os.path.join(runtime, "grog-music")
    os.makedirs(directory, mode=0o700, exist_ok=True)
    os.chmod(directory, 0o700)
    return os.path.join(directory, "mpv.sock")


def build(settings, spawn=None, client=None):
    """The player (with mpv, not started yet), the YouTube Music provider and
    their commands."""
    player = Player(
        volume=settings["volume"], duck_level=settings["duckLevel"], max_queue=settings["maxQueue"]
    )
    mpv_args = {"spawn": spawn} if spawn is not None else {}
    player.mpv = Mpv(
        settings["mpv"],
        mpv_socket(settings),
        settings["ytdlp"],
        on_start=player.on_mpv_start,
        audio_device=settings["audioDevice"],
        **mpv_args,
    )
    youtube = settings["youtubeMusic"]
    provider = YouTubeMusic(
        player,
        client or functools.partial(ytmusic, youtube["language"], youtube["location"]),
        max_queue=settings["maxQueue"],
    )
    return player, provider, app.commands(player, provider)


def serve(settings, stop=None):
    """Starts mpv and listens, until `stop` (an Event) is set."""
    stop = stop or threading.Event()
    player, _, commands = build(settings)
    try:
        player.mpv.ensure()
    except MpvError as e:
        log.warning("%s: trying again when asked", e)
    server = Server(settings["socket"], commands).start()
    try:
        while not stop.wait(WATCH_EVERY_S):
            try:
                player.mpv.ensure()
            except MpvError as e:
                log.warning("%s", e)
    finally:
        server.close()
        player.mpv.close()


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="grog-music", description="grog's music player, and YouTube Music's search and library"
    )
    parser.add_argument("--config", default=os.environ.get("GROG_MUSIC_CONFIG"), help="a JSON settings file")
    parser.add_argument("--socket", help="where grog asks it things (the socket setting)")
    sub = parser.add_subparsers(dest="action")
    send = sub.add_parser("send", help="send one request to the running grog-music, and print its reply")
    send.add_argument("request", help='a JSON object, like {"command": "status"}')
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        settings = config.load(args.config)
    except config.ConfigError as e:
        sys.exit(str(e))
    if args.socket:
        settings["socket"] = args.socket
    if problems := config.problems(settings):
        print("Invalid settings:\n" + "\n".join(f"  {p}" for p in problems), file=sys.stderr)
        sys.exit(2)

    if args.action == "send":
        try:
            request = json.loads(args.request)
        except ValueError:
            sys.exit("The request must be a JSON object.")
        try:
            reply = ask(settings["socket"], request)
        except OSError as e:
            sys.exit(f"Couldn't reach grog-music at {settings['socket']}: {e.strerror or type(e).__name__}")
        print(json.dumps(reply, ensure_ascii=False, indent=2))
        sys.exit(0 if reply.get("ok") else 1)

    stop = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
    serve(settings, stop)

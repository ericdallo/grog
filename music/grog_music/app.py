"""What grog can ask grog-music: the player's commands, generic, and each
provider's, named `<provider>.<command>`."""


def _flag(request, key):
    return request.get(key) is True


def commands(player, youtube_music):
    """{name: function of the request (a dict) returning the reply's fields}."""
    return {
        "status": lambda r: player.status(),
        "play": lambda r: player.play(r.get("items"), shuffle=_flag(r, "shuffle")),
        "enqueue": lambda r: player.enqueue(r.get("items")),
        "pause": lambda r: player.pause(),
        "resume": lambda r: player.resume(),
        "stop": lambda r: player.stop(),
        "next": lambda r: player.next(),
        "previous": lambda r: player.previous(),
        "volume": lambda r: player.volume(level=r.get("level"), change=r.get("change")),
        "duck": lambda r: player.duck(r.get("level")),
        "unduck": lambda r: player.unduck(),
        "youtube-music.search": lambda r: youtube_music.search(r.get("query"), r.get("kind", "top")),
        "youtube-music.play": lambda r: youtube_music.play(
            query=r.get("query"),
            kind=r.get("kind"),
            id=r.get("id"),
            shuffle=_flag(r, "shuffle"),
            auth=r.get("auth"),
        ),
        "youtube-music.library": lambda r: youtube_music.library(r.get("kind"), r.get("auth")),
    }

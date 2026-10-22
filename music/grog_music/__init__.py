"""grog-music: the player grog plays music with, in the user's session."""


class Refused(Exception):
    """What was asked can't be done. Its message, a plain sentence, goes back
    to grog as the error: never put what was asked or played in it."""

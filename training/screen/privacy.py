"""Privacy filter shared by collect.py and make_goals.py."""
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
WORDS_FILE = HERE / "private_words.txt"   # git-ignored, optional: one word/name per line


def _words():
    if not WORDS_FILE.is_file():
        return []
    return [w.strip().lower() for w in WORDS_FILE.read_text().splitlines() if w.strip() and not w.startswith('#')]


_BLOCK = _words()
# Wi-Fi / Bluetooth / account lists show other people's network and device names: drop those screens.
_LIST_HINTS = ("bars", "secure network", "open network", "saved network", "paired device", "available device",
               "connected device", "disconnected", "signal strength")


def looks_private(label):
    """True for phone numbers, emails, long digit runs, or any word from private_words.txt."""
    if not label:
        return False
    if '@' in label:
        return True
    if sum(c.isdigit() for c in label) >= 6:
        return True
    low = label.lower()
    if any(w in low for w in _LIST_HINTS):
        return True
    return any(w in low for w in _BLOCK)

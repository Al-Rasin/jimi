"""Feature version 1; mirrored by NotificationModel.kt."""

import re

FEATURE_VERSION = 1
_LOWER = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")
_TOKEN = re.compile(r"[a-z0-9]+")


def features(row):
    title = row.get("title", "").translate(_LOWER)
    text = row.get("text", "").translate(_LOWER)
    result = {"pkg:" + row.get("pkg", "").translate(_LOWER),
              "hour:" + str((int(row.get("timeMs", 0)) // 3_600_000) % 24)}
    result.update("title:" + token for token in _TOKEN.findall(title))
    result.update("text:" + token for token in _TOKEN.findall(text))
    combined = title + " " + text
    tokens = _TOKEN.findall(combined)
    if re.search(r"[0-9]", combined):
        result.add("has_digits")
    if (set(tokens) & {"otp", "passcode", "pin"}
            or any(pair in {("verification", "code"), ("one", "time")}
                   for pair in zip(tokens, tokens[1:]))):
        result.add("has_otp")
    if row.get("isConversation", False) is True:
        result.add("is_conversation")
    if row.get("isGroupConversation", False) is True:
        result.add("is_group_conversation")
    return sorted(result)


def label(row, include_kept=False):
    explicit = row.get("label")
    if explicit in ("important", "noise"):
        return int(explicit == "important")
    if explicit not in (None, ""):
        return None
    if row.get("status") == "dismissed":
        return 0
    if include_kept and row.get("status") == "kept":
        return 1
    return None

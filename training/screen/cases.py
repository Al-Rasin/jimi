"""15 held-out test cases (ROADMAP #66). (screen, goal, accepted answers). Screens are <screens dir>/<screen>.xml.
Only indices and goals live here; the XML files (home.xml has private text) stay outside the repo.
answer: ("click", {idx...}) / ("open_app", name) / ("scroll", dir) / ("back",)"""
CASES = [
    ("settings", "Open About phone", [("click", {3})]),
    ("settings", "Open Wi-Fi settings", [("click", {8, 9})]),
    ("settings", "Open Bluetooth settings", [("click", {10, 11})]),
    ("settings", "Change the ringtone", [("click", {17})]),
    ("settings", "Turn on dark mode", [("click", {16, 1})]),
    ("display", "Turn on dark mode", [("click", {5})]),
    ("display", "Go back to the previous screen", [("click", {0}), ("back",)]),
    ("display", "Change the refresh rate", [("click", {15, 16})]),
    ("display", "Turn on reading mode", [("click", {11, 12, 13})]),
    ("display", "Change the font size", [("click", {17}), ("scroll", "down")]),
    ("home", "Open Messages", [("click", {28, 29}), ("open_app", "messages")]),
    ("home", "Open the phone dialer", [("click", {26, 27}), ("open_app", "phone")]),
    ("home", "Open Settings", [("open_app", "settings")]),
    ("home", "Lock the phone", [("click", {30, 31})]),
    ("home", "Boost speed", [("click", {22, 23, 24})]),
]

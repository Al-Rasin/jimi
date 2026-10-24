#!/usr/bin/env python3
"""Walk SYSTEM apps on the phone over adb and save uiautomator dumps (ROADMAP #66).

    ANDROID_SERIAL=<serial> python3 training/screen/collect.py --depth 2 --max-screens 40 [--png] [--apps settings,clock]

Safety: only allow-listed packages; only taps on clickable elements; never types text; never taps switches,
checkboxes, edit fields or risky words (reset, erase, ...). Screens with private-looking labels are dropped.
Output: training/data/screen/raw/<app>_<hash>.xml (+ .png). Needs the phone unlocked and awake.
"""
import argparse
import hashlib
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from privacy import looks_private  # noqa: E402
from prompt import parse_elements  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = ROOT / "training/data/screen/raw"

# name -> (launcher package, allowed packages while inside). Add apps here; nothing else is ever opened.
APPS = {
    "settings": ("com.android.settings", {"com.android.settings", "com.miui.securitycenter"}),
    "security": ("com.miui.securitycenter", {"com.miui.securitycenter", "com.miui.powerkeeper", "com.android.settings"}),
    "clock": ("com.android.deskclock", {"com.android.deskclock"}),
    "calculator": ("com.miui.calculator", {"com.miui.calculator", "com.android.calculator2"}),
    "filemanager": ("com.mi.android.globalFileexplorer", {"com.mi.android.globalFileexplorer", "com.android.fileexplorer"}),
    "camera": ("com.android.camera", {"com.android.camera"}),
}
# Android settings screens opened by action (the sub-screens a user asks for most).
SETTINGS_ACTIONS = [
    "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS", "android.settings.DISPLAY_SETTINGS",
    "android.settings.SOUND_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS", "android.settings.APPLICATION_SETTINGS",
    "android.settings.LOCATION_SOURCE_SETTINGS", "android.settings.DATE_SETTINGS", "android.settings.LOCALE_SETTINGS",
    "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.AIRPLANE_MODE_SETTINGS",
    "android.settings.ACCESSIBILITY_SETTINGS", "android.settings.NFC_SETTINGS", "android.settings.HOTSPOT_SETTINGS",
]
SKIP_WORDS = ("reset", "erase", "factory", "delete", "format", "uninstall", "clear", "power off", "restart", "reboot",
              "shut down", "account", "sign in", "log in", "unlock", "password", "pin", "fingerprint", "face", "sim",
              "phone number", "call", "message", "sms", "contact", "imei", "emergency", "feedback", "update")
SKIP_CLASSES = ("Switch", "CheckBox", "EditText", "RadioButton", "ToggleButton", "SeekBar")


def adb(*args, check=True, out=False):
    r = subprocess.run(["adb", *args], capture_output=True, text=not out, timeout=60)
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr}")
    return r.stdout


def sh(cmd):
    return adb("shell", cmd)


def go_home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(0.5)


def launch(app, action=None):
    pkg = APPS[app][0]
    sh(f"am force-stop {pkg}")
    if action:
        sh(f"am start -a {action}")
    else:
        sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
    time.sleep(1.5)


def dump(path):
    """Dump the current screen to [path]; returns False when it failed."""
    sh("uiautomator dump /sdcard/jv_dump.xml")
    data = adb("exec-out", "cat", "/sdcard/jv_dump.xml", out=True)
    if b"<hierarchy" not in data:
        return False
    path.write_bytes(data)
    return True


def screen_hash(els):
    sig = "|".join(f"{c}:{l}:{','.join(f)}" for c, l, _, f, _ in els)
    return hashlib.sha1(sig.encode()).hexdigest()[:10]


def is_private(els):
    return any(looks_private(l) for _, l, _, _, _ in els)


def row_taps(path):
    """MIUI: the clickable row has no text; its child TextView has it. Rows holding a switch are skipped
    (tapping them would toggle a setting). Returns (index, centre x, centre y, label) in screen pixels."""
    out = []
    for n in ET.parse(path).getroot().iter("node"):
        if n.get("clickable") != "true" or n.get("package") == "com.android.systemui":
            continue
        kids = list(n.iter("node"))
        if any(k.get("checkable") == "true" or any(k.get("class", "").endswith(c) for c in SKIP_CLASSES) for k in kids):
            continue
        label = next((k.get("text") or k.get("content-desc") for k in kids if k.get("text") or k.get("content-desc")), "")
        low = label.lower()
        if not label or looks_private(label) or any(w in low for w in SKIP_WORDS):
            continue
        b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
        if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
            out.append((len(out), (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, label))
    return out


def tappable(els):
    """Elements that are safe to tap for navigation: (index, centre x, centre y, label)."""
    out = []
    for i, (c, label, _, flags, b) in enumerate(els):
        if "click" not in flags or "check" in flags or "edit" in flags:
            continue
        if any(c.endswith(k) for k in SKIP_CLASSES) or not label:
            continue
        low = label.lower()
        if looks_private(label) or any(w in low for w in SKIP_WORDS):
            continue
        out.append((i, (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, label))
    return out


def replay(app, action, taps, allowed):
    """Open the app and tap the recorded path; False if we left the allow-list."""
    launch(app, action)
    for x, y in taps:
        if focused_package() not in allowed:
            return False
        sh(f"input tap {x} {y}")
        time.sleep(1.0)
    return focused_package() in allowed


def focused_package():
    out = sh("dumpsys window | grep -m1 mCurrentFocus")
    m = re.search(r" ([\w.]+)/", out)
    return m[1] if m else None


def current_ok(path, allowed):
    if not dump(path):
        return None
    try:
        _, _, pkg, els = parse_elements(path)
    except Exception:
        return None
    return els if pkg in allowed else None


def walk(app, action, a):
    pkg, allowed = APPS[app]
    seen = set()
    saved = 0
    tmp = RAW / ".tmp.xml"
    queue = [([], 0)]   # (tap path, depth)
    while queue and saved < a.max_screens:
        taps, depth = queue.pop(0)
        if not replay(app, action, taps, allowed):
            print(f"  skip (left allow-list) path={len(taps)} taps")
            go_home()
            continue
        els = current_ok(tmp, allowed)
        if els is None:
            print(f"  skip (left allow-list / dump failed) path={len(taps)} taps")
            continue
        h = screen_hash(els)
        if h in seen:
            continue
        seen.add(h)
        if is_private(els):
            print(f"  drop private screen {h}")
            continue   # never tap inside private screens (a Wi-Fi row would connect)
        else:
            name = f"{app}_{h}"
            (RAW / f"{name}.xml").write_bytes(tmp.read_bytes())
            if a.png:
                (RAW / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", out=True))
            saved += 1
            print(f"  saved {name} depth={depth} elements={len(els)}")
        if depth >= a.depth:
            continue
        for _, x, y, label in row_taps(tmp)[: a.fanout]:
            queue.append((taps + [(x, y)], depth + 1))
    tmp.unlink(missing_ok=True)
    return saved


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apps", default=",".join(APPS), help=f"comma list from: {', '.join(APPS)}")
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--max-screens", type=int, default=40, help="per app")
    ap.add_argument("--fanout", type=int, default=12, help="max taps tried per screen")
    ap.add_argument("--png", action="store_true")
    ap.add_argument("--settings-actions", action="store_true", help="also open the Settings sub-screens by action")
    a = ap.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    total = 0
    for app in a.apps.split(","):
        if app not in APPS:
            ap.error(f"unknown app {app}; allowed: {', '.join(APPS)}")
        print("app", app)
        total += walk(app, None, a)
        if app == "settings" and a.settings_actions:
            for act in SETTINGS_ACTIONS:
                print(" action", act)
                total += walk(app, act, argparse.Namespace(**{**vars(a), "depth": 1, "max_screens": 10}))
    go_home()
    print("saved", total, "screens in", RAW)


if __name__ == "__main__":
    main()

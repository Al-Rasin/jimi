#!/usr/bin/env python3
"""Shared prompt format for the screen model (ROADMAP #66, D-070). Everything else imports this.

The base model (SmolLM2-135M-android-control) was trained on exactly this text, so do not change it.
Run `python3 prompt.py screen.xml` to print the element list of one uiautomator dump.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET

SYS = ('You are an Android UI control agent. Given the instruction and the current screen, output the single next action as raw JSON and nothing else.\n'
       'Actions: {"action_type":"click","target":N} | {"action_type":"long_press","target":N} | {"action_type":"input_text","text":"..."} | '
       '{"action_type":"scroll","direction":"up|down|left|right"} | {"action_type":"open_app","app_name":"..."} | {"action_type":"wait"} | '
       '{"action_type":"navigate_back"} | {"action_type":"navigate_home"}\n'
       'N is an element index from the UI list. If no element fits, use {"action_type":"click","x":X,"y":Y} with X and Y in 0-1000.')

IM_END = "<|im_end|>"


def parse_elements(path, limit=32):
    """uiautomator XML -> (W, H, pkg, [(class, label, [l,t,r,b in 0-1000], flags, raw_bounds)])."""
    root = ET.parse(path).getroot()
    W = H = None
    out = []
    pkg = None
    for n in root.iter('node'):
        b = list(map(int, re.findall(r'\d+', n.get('bounds', '[0,0][0,0]'))))
        if W is None or b[2] > W:
            W = max(W or 0, b[2])
            H = max(H or 0, b[3])
    for n in root.iter('node'):
        pkg = pkg or n.get('package')
        if n.get('package') == 'com.android.systemui':
            continue
        b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
        if b[2] <= b[0] or b[3] <= b[1]:
            continue
        label = n.get('text') or n.get('content-desc') or ''
        flags = [f for f, k in (('click', 'clickable'), ('scroll', 'scrollable'), ('check', 'checkable')) if n.get(k) == 'true']
        if n.get('class', '').endswith('EditText'):
            flags.append('edit')
        if n.get('checked') == 'true':
            flags.append('checked')
        if not label and not flags:
            continue
        if not label and 'scroll' not in flags:
            continue
        nb = [round(b[0] * 1000 / W), round(b[1] * 1000 / H), round(b[2] * 1000 / W), round(b[3] * 1000 / H)]
        out.append((n.get('class', '').split('.')[-1], label[:40], nb, flags, b))
    return W, H, pkg, out[:limit]


def elements(path, limit=32):
    W, H, pkg, els = parse_elements(path, limit)
    lines = [f'[{i}] {c} "{l}" [{",".join(map(str, bb))}] {",".join(f)}'.rstrip() for i, (c, l, bb, f, _) in enumerate(els)]
    return f"screen {W}x{H} app={pkg}\n" + "\n".join(lines)


def user_turn(goal, elems):
    return f'Goal: {goal}\nInstruction: {goal}\nCurrent Android UI:\n{elems}\nPrevious actions:\nnone\nReturn exactly one action.'


def messages(goal, elems):
    return [{'role': 'system', 'content': SYS}, {'role': 'user', 'content': user_turn(goal, elems)}]


def compact(action):
    return json.dumps(action, separators=(',', ':'), ensure_ascii=False)


def parse_action(text):
    """First JSON object in text -> dict with 'action_type' (also accepts 'action'), or None."""
    for m in re.finditer(r'\{.*?\}', text or '', re.S):
        try:
            a = json.loads(m[0])
        except Exception:
            continue
        if isinstance(a, dict) and (a.get('action_type') or a.get('action')):
            a['action_type'] = a.get('action_type') or a.get('action')
            return a
    return None


def action_key(a):
    """Comparable identity of an action: same type and same target / direction / app."""
    t = a['action_type']
    if t in ('click', 'long_press'):
        if 'target' in a:
            try:
                return (t, int(a['target']))
            except (TypeError, ValueError):
                return None
        return None  # raw x,y clicks are never used as labels
    if t == 'scroll':
        return (t, a.get('direction'))
    if t == 'open_app':
        return (t, str(a.get('app_name', '')).lower())
    if t in ('navigate_back', 'navigate_home', 'wait'):
        return (t,)
    return None


def boxes(xml_path):
    out = {}
    for line in elements(xml_path).splitlines()[1:]:
        m = re.match(r'\[(\d+)\].*\[(\d+),(\d+),(\d+),(\d+)\]', line)
        out[int(m[1])] = tuple(map(int, m.group(2, 3, 4, 5)))
    return out


def score(xml_path, text, ok):
    """True if the model answer [text] is one of the accepted answers [ok] for the screen [xml_path].
    ok items: ("click", {idx...}) / ("open_app", name) / ("scroll", dir) / ("back",)."""
    p = re.search(r'\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)', text)
    if '{' not in text:
        if 'BACK' in text:
            text = '{"action_type":"navigate_back"}'
        elif 'SCROLL' in text:
            text = '{"action_type":"scroll","direction":"down"}'
        elif p:
            text = json.dumps({'action_type': 'click', 'x': p[1], 'y': p[2]})
    a = parse_action(text)
    if not a:
        return False
    t = a['action_type']
    hits = set()
    if t == 'click':
        if 'target' in a:
            try:
                hits = {int(a['target'])}
            except (TypeError, ValueError):
                pass
        elif 'x' in a:
            x, y = float(a['x']), float(a['y'])
            hits = {i for i, (l, tp, r, b) in boxes(xml_path).items() if l - 10 <= x <= r + 10 and tp - 10 <= y <= b + 10}
    for o in ok:
        if o[0] == 'click' and t == 'click' and hits & o[1]:
            return True
        if o[0] == 'back' and t == 'navigate_back':
            return True
        if o[0] == 'scroll' and t == 'scroll' and a.get('direction') == o[1]:
            return True
        if o[0] == 'open_app' and t == 'open_app' and o[1] in str(a.get('app_name', '')).lower():
            return True
    return False


def chat_text(sys_text, user_text):
    """The exact ChatML text the model's template builds up to the assistant answer."""
    return (f'<|im_start|>system\n{sys_text}{IM_END}\n<|im_start|>user\n{user_text}{IM_END}\n<|im_start|>assistant\n')


if __name__ == '__main__':
    print(elements(sys.argv[1]))

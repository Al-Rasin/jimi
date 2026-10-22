#!/usr/bin/env python3
"""Synthetic tool-call dataset for FunctionGemma (PLAN 13.1).

Templates + slot values for every tool → train.jsonl / valid.jsonl in training/data/toolcall/ (git-ignored).
Each row: {"prompt": <exact app prompt>, "completion": <exact target>, "text": ..., "tool": ...}.
The prompt prefix comes from app/build/llm/prompt_prefix.txt (written by the ToolPromptDump unit test), so
training sees byte-for-byte what the app sends. Phrases in cases.jsonl (the eval set) are never used.

    python3 training/toolcall/make_dataset.py [--per-tool 60] [--seed 1]
"""
import argparse
from collections import Counter
import datetime as dt
import json
import pathlib
import random
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREFIX = ROOT / "app/build/llm/prompt_prefix.txt"
OUT = ROOT / "training/data/toolcall"
EVAL = pathlib.Path(__file__).with_name("cases.jsonl")
ESC = "<escape>"
CHAT = "chat"   # target when no tool fits; the app then asks the chat model

APPS = ["YouTube", "WhatsApp", "Facebook", "Messenger", "Instagram", "Chrome", "Gmail", "Maps", "Camera", "Gallery",
        "Settings", "Calculator", "Spotify", "Telegram", "Clock", "Calendar", "Play Store", "bKash", "Daraz", "Foodpanda",
        "Pathao", "Netflix", "TikTok", "Twitter", "LinkedIn", "Files", "Notes", "Contacts", "Phone", "Messages", "Imo",
        "Google Drive", "Truecaller", "Nagad", "Zoom", "Discord", "Snapchat", "Pinterest", "Reddit", "Shazam"]
CONTACTS = ["mom", "dad", "Rahim", "Karim", "Sara", "Nila", "Tanvir", "Ayesha", "Fahim", "Nusrat", "my brother", "my sister",
            "Rafiq bhai", "Sumon", "Jamal", "Mitu", "boss", "Hasan", "Shuvo", "Rina", "Abir", "Tania", "uncle", "Arif"]
MESSAGES = ["I'm coming", "I will be late", "call me back", "where are you", "good night", "happy birthday",
            "I reached home", "see you tomorrow", "send me the file", "I'm in a meeting", "ok", "on my way",
            "did you eat", "bring some milk", "the meeting is at 5", "thank you so much"]
SONGS = ["Arijit Singh", "lofi music", "Tahsan songs", "Coke Studio Bangla", "Ed Sharpe", "Imagine Dragons", "relaxing music",
         "Shape of You", "Taylor Swift", "Bohemian Rhapsody", "Hindi songs", "the news", "Atif Aslam", "James", "Ayub Bachchu",
         "rain sounds", "Quran recitation", "Linkin Park", "Coldplay", "jazz", "workout music", "Kishore Kumar"]
CITIES = ["Dhaka", "Chittagong", "Sylhet", "Khulna", "London", "Dubai", "Kolkata", "Rajshahi", "Cox's Bazar", "Tokyo",
          "New York", "Singapore", "Barisal", "Comilla"]
TOPICS = ["cricket", "football", "technology", "Bangladesh", "politics", "business", "science", "AI", "the stock market",
          "the weather", "movies", "health", "Bitcoin", "space", "education"]
PEOPLE = ["Rabindranath Tagore", "Albert Einstein", "Elon Musk", "Shakib Al Hasan", "Kazi Nazrul Islam", "Marie Curie",
          "Sheikh Hasina", "Muhammad Yunus", "Newton", "Messi", "Cristiano Ronaldo", "Alan Turing"]
THINGS = ["black holes", "the Eiffel Tower", "photosynthesis", "the Padma Bridge", "World War 2", "Mars", "the internet",
          "Python programming", "the Sundarbans", "Bitcoin", "quantum computing"]
SEARCHES = ["best phones under 20000 taka", "how to cook biryani", "flight to Dubai price", "cricket score",
            "nearest pharmacy", "how to fix wifi", "Flutter jobs in Dhaka", "dollar rate today", "train schedule Dhaka to Sylhet",
            "how to make tea", "Bangla to English translation"]
SITES = ["google.com", "facebook.com", "prothomalo.com", "github.com", "youtube.com", "bbc.com", "daraz.com.bd",
         "wikipedia.org", "linkedin.com", "stackoverflow.com"]
NOTES = ["the wifi password is 4567", "buy eggs and bread", "car parked on level 2", "meeting with Rahim on Sunday",
         "idea for the app: dark mode", "my locker code is 2210", "call the bank tomorrow", "Sara's birthday is 5 March",
         "pay the electricity bill", "book the tickets"]
FACTS = ["my sister's name is Nila", "I like black coffee", "my birthday is 12 June", "I work as a Flutter developer",
         "my car is white", "I live in Dhaka", "I am allergic to peanuts", "my favourite team is Argentina",
         "my wife's name is Sara", "I wake up at 6"]
TASKS = ["drink water", "call mom", "take my medicine", "pay the bill", "go to the gym", "send the report", "buy milk",
         "check the email", "pray", "water the plants", "charge the phone", "submit the assignment"]
BUTTONS = ["send", "OK", "next", "allow", "cancel", "install", "search", "submit", "accept", "skip", "login", "yes"]
TYPED = ["hello", "how are you", "I am fine", "Dhaka", "good morning", "thanks", "see you soon", "pizza near me"]
CHATS = ["tell me a story", "why is the sky blue", "how do airplanes fly", "what should I eat tonight", "I'm bored",
         "explain gravity simply", "give me a motivational quote", "what is 25 times 4", "how far is the moon",
         "translate thank you to bangla", "what's the meaning of life", "write a short poem", "I feel sad today",
         "how many days in a leap year", "do you like music", "who made you",
         "give me a cooking tip", "is coffee healthy", "suggest a name for my cat", "how do I learn english fast",
         "what's a good movie to watch", "can you sing", "are you smart", "tell me something interesting",
         "how old is the earth", "what is love", "I love you", "you are funny", "good job",
         "how to be more productive", "explain how vaccines work", "convert 10 km to miles",
         "how do I make friends", "is it healthy to skip breakfast", "say something nice"]
# Broad negative examples teach chat intent even when tool-related nouns occur.
CHAT_SUBJECTS = [
    "the moon", "cats", "dogs", "penguins", "octopuses", "bees", "butterflies", "dinosaurs",
    "rainbows", "clouds", "snowflakes", "oceans", "volcanoes", "deserts", "forests", "stars",
    "Saturn", "comets", "rain", "trees", "flowers", "mushrooms", "chocolate", "coffee",
    "tea", "bread", "cheese", "bananas", "robots", "books", "pencils", "bicycles",
    "trains", "kites", "balloons", "mirrors", "bubbles", "magnets", "music", "dreams",
]
CHATS += [template.format(subject=subject)
          for subject in CHAT_SUBJECTS
          for template in (
              "tell me a fun fact about {subject}", "share something surprising about {subject}",
              "tell me a joke about {subject}", "make up a silly story about {subject}",
              "write a little poem about {subject}", "give me a riddle about {subject}",
              "what do you like about {subject}", "why do people find {subject} interesting",
          )]
CHATS += [template.format(feeling=feeling)
          for feeling in ("lonely", "happy", "nervous", "excited", "tired", "hopeful", "frustrated", "shy",
                          "confused", "proud", "disappointed", "homesick")
          for template in ("I feel {feeling}", "do you ever feel {feeling}",
                           "what would you say to someone feeling {feeling}")]
CHATS += [
    "do you love me", "what can you do", "hello", "hi", "hey there", "hello Jimi",
    "hi Jimi", "hey jimmy", "good afternoon", "nice to meet you", "how is your day going",
    "are you there Jimi", "Jimi are you awake", "what is your name", "why are you called Jimi",
    "are you a person", "are you a robot", "do you have feelings", "can you feel love",
    "do you get lonely", "do you like talking to me", "are we friends", "can we be friends",
    "do you have a family", "do you sleep", "do you dream", "what makes you happy",
    "what makes you laugh", "what is your favourite colour", "what is your favourite animal",
    "what kind of music do you like", "do you prefer cats or dogs", "what do you think of poetry",
    "is kindness important", "what does friendship mean to you", "why do people laugh",
    "can you explain what you do", "how do you understand my voice", "can you work offline",
    "are you always listening", "how are you different from a person", "can you make mistakes",
    "tell me about yourself Jimi", "what are your limitations", "can you understand jokes",
    "tell me a joke about messages", "write a poem about a flashlight", "why do phones need batteries",
    "why is loud music exciting", "what is a polite way to end a conversation",
    "I just want to talk", "I had a wonderful day", "that was helpful", "you made me smile",
    "tell me something cheerful", "give me encouragement", "let us imagine a flying cat",
]
CHATS = list(dict.fromkeys(CHATS))

TARGETS = {
    "flashlight": ["flashlight", "torch", "flash light", "the flashlight", "the torch"],
    "wifi": ["wifi", "wi-fi", "the wifi", "wireless"],
    "bluetooth": ["bluetooth", "the bluetooth"],
    "data": ["mobile data", "data", "the internet", "cellular data"],
    "dnd": ["do not disturb", "DND", "silent mode", "focus mode"],
    "airplane": ["airplane mode", "flight mode", "aeroplane mode"],
    "location": ["location", "GPS", "the location"],
    "hotspot": ["hotspot", "mobile hotspot", "the hotspot", "tethering"],
    "battery_saver": ["battery saver", "power saving mode", "power saver"],
    "auto_rotate": ["auto rotate", "screen rotation", "auto rotation"],
}
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

r = random.Random()


def pick(xs):
    return r.choice(xs)


def hm(h, m=0):
    return f"{h:02d}:{m:02d}"


def spoken_time():
    """('7:30 pm', 19, 30) style random times."""
    h = r.randint(1, 12)
    m = pick([0, 0, 0, 15, 30, 45, 10, 5, 20])
    ampm = pick(["am", "pm", "a.m.", "p.m.", "in the morning", "in the evening", "at night", ""])
    if ampm == "at night":
        pm = h >= 6 and h != 12   # "2 at night" = 02:00, "10 at night" = 22:00
    else:
        pm = ampm in ("pm", "p.m.", "in the evening") or (ampm == "" and 1 <= h <= 6)
    h24 = (h % 12) + (12 if pm else 0)
    word = f"{h}" if m == 0 else f"{h}:{m:02d}"
    if m == 0 and r.random() < 0.3:
        word = f"{h} o'clock"
    return (f"{word} {ampm}".strip(), h24, m)


def call(tool, **args):
    return tool, {k: str(v) for k, v in args.items() if v not in (None, "")}


def gen_tool(tool, now):
    """One (text, tool, args) for [tool]. [now] is the context time."""
    c, a, s, t, ct = pick(CONTACTS), pick(APPS), pick(SONGS), pick(TOPICS), pick(CITIES)
    if tool == "open_app":
        return pick(["open {a}", "launch {a}", "start {a}", "open the {a} app", "go to {a}", "show me {a}",
                     "can you open {a}", "please open {a}", "{a} open koro", "run {a}", "i want to use {a}",
                     "take me to {a}", "bring up {a}"]).format(a=a), *call(tool, app=a)
    if tool == "close_app":
        return pick(["close {a}", "close the {a} app", "exit {a}", "kill {a}", "force stop {a}", "shut {a}",
                     "quit {a}", "stop {a} app"]).format(a=a), *call(tool, app=a)
    if tool == "open_url":
        u = pick(SITES)
        return pick(["open {u}", "go to {u}", "visit {u}", "open the website {u}", "take me to {u}",
                     "load {u}", "browse {u}"]).format(u=u), *call(tool, url=u)
    if tool == "set_alarm":
        sp, h, m = spoken_time()
        kind = r.random()
        if kind < 0.15:
            d = pick(DAYS)
            return pick(["set an alarm for {t} every {d}", "alarm at {t} on {d}s", "wake me up at {t} every {d}"]).format(t=sp, d=d), *call(tool, time=hm(h, m), repeat=d)
        if kind < 0.3:
            return pick(["set a daily alarm at {t}", "alarm every day at {t}", "wake me up at {t} every day",
                         "set alarm for {t} daily"]).format(t=sp), *call(tool, time=hm(h, m), repeat="daily")
        if kind < 0.42:
            lb = pick(["gym", "medicine", "prayer", "office", "class", "meeting"])
            return pick(["set an alarm at {t} for {l}", "alarm {t} called {l}", "set a {l} alarm for {t}"]).format(t=sp, l=lb), *call(tool, time=hm(h, m), label=lb)
        return pick(["set an alarm for {t}", "wake me up at {t}", "alarm at {t}", "set alarm {t}", "please set an alarm at {t}",
                     "i need an alarm at {t}", "wake me at {t}", "put an alarm for {t}", "alarm for {t} please",
                     "can you wake me up at {t}"]).format(t=sp), *call(tool, time=hm(h, m))
    if tool == "set_timer":
        n = pick([1, 2, 3, 5, 10, 15, 20, 25, 30, 40, 45, 60, 90])
        unit = pick(["minute", "minute", "second", "hour"]) if n <= 3 else pick(["minute", "minute", "second"])
        mult = {"second": 1, "minute": 60, "hour": 3600}[unit]
        if unit == "second" and n < 10:
            n *= 10
        words = f"{n} {unit}" + ("s" if n != 1 else "")
        if r.random() < 0.15:
            lb = pick(["tea", "eggs", "rice", "workout", "nap", "pasta"])
            return pick(["set a {w} timer for {l}", "{l} timer {w}", "start a timer for {w} for the {l}"]).format(w=words, l=lb), *call(tool, seconds=n * mult, label=lb)
        return pick(["set a timer for {w}", "start a {w} timer", "timer {w}", "countdown {w}", "{w} timer please",
                     "set timer {w}", "time {w} for me", "start a countdown of {w}"]).format(w=words), *call(tool, seconds=n * mult)
    if tool == "cancel_alarm":
        return pick(["cancel my alarm", "turn off the alarm", "stop the timer", "delete all alarms", "cancel the timer",
                     "dismiss the alarm", "no alarm tomorrow", "remove my alarms", "stop alarm", "switch off the alarms"]), *call(tool)
    if tool == "call":
        if r.random() < 0.25:
            return pick(["whatsapp call {c}", "call {c} on whatsapp", "video call {c} on whatsapp", "ring {c} on whatsapp"]).format(c=c), *call(tool, contact=c, app="whatsapp")
        return pick(["call {c}", "phone {c}", "ring {c}", "dial {c}", "give {c} a call", "make a call to {c}", "can you call {c}",
                     "call {c} please", "i want to talk to {c}", "get {c} on the phone"]).format(c=c), *call(tool, contact=c)
    if tool == "send_sms":
        msg = pick(MESSAGES)
        return pick(["text {c} {m}", "send a message to {c} saying {m}", "sms {c} that {m}", "message {c} {m}",
                     "tell {c} {m} by sms", "send {c} a text saying {m}", "text {c} that {m}"]).format(c=c, m=msg), *call(tool, contact=c, text=msg)
    if tool == "send_whatsapp":
        msg = pick(MESSAGES)
        return pick(["whatsapp {c} {m}", "send a whatsapp to {c} saying {m}", "send {c} a whatsapp message {m}",
                     "message {c} on whatsapp {m}", "tell {c} on whatsapp that {m}", "whatsapp message to {c}: {m}"]).format(c=c, m=msg), *call(tool, contact=c, text=msg)
    if tool == "read_messages":
        return pick(["read my messages", "any new texts", "read my sms", "what are my latest messages", "check my messages",
                     "did anyone text me", "read the last message", "show my inbox"]), *call(tool)
    if tool == "compose_email":
        subj = pick(["the meeting", "leave request", "the invoice", "project update", "tomorrow's plan"])
        return pick(["write an email to {c} about {s}", "email {c} about {s}", "send a mail to {c} regarding {s}",
                     "compose an email to {c} about {s}"]).format(c=c, s=subj), *call(tool, to=c, subject=subj)
    if tool == "play_media":
        if r.random() < 0.35:
            app = pick(["youtube", "spotify"])
            return pick(["play {s} on {a}", "put on {s} on {a}", "{a} play {s}", "open {a} and play {s}"]).format(s=s, a=app), *call(tool, query=s, app=app)
        return pick(["play {s}", "put on {s}", "i want to listen to {s}", "play some {s}", "can you play {s}",
                     "start playing {s}", "let's hear {s}", "play me {s}"]).format(s=s), *call(tool, query=s)
    if tool == "media_control":
        act = pick(["play", "pause", "next", "previous"])
        ph = {"play": ["resume the music", "continue playing", "unpause", "resume", "play it again", "keep playing"],
              "pause": ["pause the music", "pause", "stop the song", "hold the music", "pause the video", "stop playing"],
              "next": ["next song", "skip this", "skip", "play the next one", "next track", "skip this song", "change the song"],
              "previous": ["previous song", "go back a song", "last track", "play the previous one", "previous track", "play that again from before"]}[act]
        return pick(ph), *call(tool, action=act)
    if tool == "set_volume":
        k = r.random()
        if k < 0.5:
            v = pick([0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100])
            return pick(["volume {v}", "set volume to {v}", "set the volume at {v} percent", "change volume to {v}%",
                         "make the volume {v}"]).format(v=v), *call(tool, level=v)
        if k < 0.75:
            return pick(["louder", "volume up", "turn it up", "increase the volume", "i can't hear, louder", "raise the sound"]), *call(tool, level="up")
        return pick(["quieter", "volume down", "turn it down", "lower the volume", "too loud", "reduce the sound"]), *call(tool, level="down")
    if tool == "toggle":
        tg = pick(list(TARGETS))
        nm = pick(TARGETS[tg])
        st = pick(["on", "off", "on", "off", "toggle"])
        if st == "toggle":
            return pick(["toggle {n}", "switch {n}"]).format(n=nm), *call(tool, target=tg, state="toggle")
        on = st == "on"
        tpl = pick(["turn {s} {n}", "turn {n} {s}", "switch {s} {n}", "{e} {n}", "please turn {s} {n}", "can you turn {s} {n}",
                    "put {n} {s}", "{n} {s}", "set {n} {s}"])
        return tpl.format(s=st, n=nm, e=("enable" if on else "disable")), *call(tool, target=tg, state=st)
    if tool == "set_brightness":
        k = r.random()
        if k < 0.4:
            v = pick([10, 20, 30, 50, 70, 80, 100])
            return pick(["brightness {v}", "set brightness to {v} percent", "screen brightness {v}%", "make brightness {v}"]).format(v=v), *call(tool, level=v)
        if k < 0.6:
            return pick(["brighter", "increase brightness", "brightness up", "make the screen brighter", "too dark", "i can't see the screen"]), *call(tool, level="up")
        if k < 0.85:
            return pick(["dim the screen", "lower brightness", "brightness down", "screen is too bright", "make it darker", "reduce brightness"]), *call(tool, level="down")
        return pick(["auto brightness", "turn on adaptive brightness", "set brightness to automatic"]), *call(tool, level="auto")
    if tool == "device_status":
        w = pick(["time", "date", "battery", "storage", "all"])
        ph = {"time": ["what time is it", "tell me the time", "time please", "what's the time now", "current time", "do you know the time"],
              "date": ["what's the date", "what day is it", "today's date", "which day is today", "what is the date today"],
              "battery": ["battery", "how much charge is left", "battery percentage", "is my phone charged", "check the battery", "how much battery do i have"],
              "storage": ["how much storage is free", "free space", "is my phone full", "check storage", "how much space left"],
              "all": ["phone status", "give me a status report", "how is my phone", "device info"]}[w]
        return pick(ph), *call(tool, what=w)
    if tool == "take_screenshot":
        return pick(["take a screenshot", "screenshot", "capture the screen", "screenshot this", "grab the screen", "save the screen"]), *call(tool)
    if tool == "lock_phone":
        return pick(["lock the phone", "lock it", "lock screen", "turn off the screen", "screen off", "lock my phone"]), *call(tool)
    if tool == "unlock_phone":
        return pick(["unlock the phone", "unlock it", "unlock my phone", "open the lock", "unlock screen"]), *call(tool)
    if tool == "read_screen":
        return pick(["read the screen", "what does it say", "read this page", "what's on the screen", "read this for me",
                     "describe the screen", "what is written here"]), *call(tool)
    if tool == "tap":
        b = pick(BUTTONS)
        return pick(["tap {b}", "click {b}", "press the {b} button", "tap on {b}", "select {b}", "hit {b}", "touch {b}"]).format(b=b), *call(tool, text=b)
    if tool == "type_text":
        x = pick(TYPED)
        return pick(["type {x}", "write {x}", "enter {x}", "type the text {x}"]).format(x=x), *call(tool, text=x)
    if tool == "scroll":
        d = pick(["up", "down"])
        return pick(["scroll {d}", "swipe {d}", "page {d}", "go {d} a bit", "scroll the page {d}"]).format(d=d), *call(tool, direction=d)
    if tool == "go_back":
        return pick(["go back", "back", "press back", "previous screen", "take me back"]), *call(tool)
    if tool == "go_home":
        return pick(["go home", "home screen", "press home", "go to the home screen", "minimize everything"]), *call(tool)
    if tool == "open_recents":
        return pick(["show recent apps", "recent apps", "open recents", "app switcher", "show me open apps"]), *call(tool)
    if tool == "create_note":
        n = pick(NOTES)
        return pick(["take a note {n}", "note that {n}", "make a note: {n}", "write down {n}", "save a note {n}", "jot down {n}",
                     "note down {n}", "add a note saying {n}"]).format(n=n), *call(tool, text=n)
    if tool == "read_notes":
        return pick(["read my notes", "what are my notes", "show my notes", "read the last note", "what did i note"]), *call(tool)
    if tool == "create_reminder":
        task = pick(TASKS)
        k = r.random()
        if k < 0.35:
            n = pick([5, 10, 15, 20, 30, 45])
            when = now + dt.timedelta(minutes=n)
            ph = pick(["remind me to {x} in {n} minutes", "in {n} minutes remind me to {x}", "set a reminder to {x} in {n} minutes"]).format(x=task, n=n)
            return ph, *call(tool, text=task, time=when.strftime("%Y-%m-%dT%H:%M"))
        if k < 0.5:
            n = pick([1, 2, 3])
            when = now + dt.timedelta(hours=n)
            ph = pick(["remind me to {x} in {n} hour{s}", "in {n} hour{s} remind me to {x}"]).format(x=task, n=n, s="" if n == 1 else "s")
            return ph, *call(tool, text=task, time=when.strftime("%Y-%m-%dT%H:%M"))
        sp, h, m = spoken_time()
        day = now.date()
        tomorrow = r.random() < 0.4
        if tomorrow or (h, m) <= (now.hour, now.minute):
            day = day + dt.timedelta(days=1)
        when = dt.datetime.combine(day, dt.time(h, m))
        if tomorrow:
            ph = pick(["remind me to {x} tomorrow at {t}", "tomorrow at {t} remind me to {x}", "set a reminder for tomorrow {t} to {x}"]).format(x=task, t=sp)
        else:
            ph = pick(["remind me to {x} at {t}", "at {t} remind me to {x}", "reminder at {t} to {x}", "set a reminder to {x} at {t}"]).format(x=task, t=sp)
        if r.random() < 0.15:
            return ph.replace(" at ", " every day at ", 1), *call(tool, text=task, time=when.strftime("%Y-%m-%dT%H:%M"), repeat="daily")
        return ph, *call(tool, text=task, time=when.strftime("%Y-%m-%dT%H:%M"))
    if tool == "list_reminders":
        return pick(["what are my reminders", "show reminders", "do i have any reminders", "list my reminders", "any reminders today",
                     "what do i need to do today"]), *call(tool)
    if tool == "delete_reminder":
        if r.random() < 0.3:
            return pick(["delete all reminders", "clear my reminders", "remove all reminders"]), *call(tool, text="all")
        task = pick(TASKS)
        return pick(["delete the reminder to {x}", "cancel the {x} reminder", "remove reminder about {x}"]).format(x=task), *call(tool, text=task)
    if tool == "remember_fact":
        f = pick(FACTS)
        return pick(["remember that {f}", "remember {f}", "keep in mind that {f}", "don't forget that {f}", "please remember {f}"]).format(f=f), *call(tool, text=f)
    if tool == "forget_fact":
        if r.random() < 0.25:
            return pick(["forget everything about me", "forget all facts", "delete all memories"]), *call(tool, text="all")
        f = pick(FACTS)
        return pick(["forget that {f}", "forget {f}", "you don't need to remember that {f}"]).format(f=f), *call(tool, text=f)
    if tool == "recall_facts":
        return pick(["what do you remember", "what do you know about me", "tell me what you remember", "what facts do you have about me",
                     "list your memories", "what have you learned about me"]), *call(tool)
    if tool == "read_notifications":
        return pick(["read my notifications", "any notifications", "what notifications do i have", "check notifications",
                     "read the notifications", "show my notifications"]), *call(tool)
    if tool == "notification_summary":
        return pick(["what did i miss", "anything important", "summarize my notifications", "notification summary",
                     "give me a summary of what came in", "catch me up"]), *call(tool)
    if tool == "clear_notifications":
        return pick(["clear all notifications", "clear notifications", "dismiss my notifications", "remove all notifications"]), *call(tool)
    if tool == "weather":
        k = r.random()
        day = "tomorrow" if r.random() < 0.35 else None
        dw = " tomorrow" if day else pick(["", " today", " now"])
        if k < 0.5:
            return pick(["what's the weather in {c}{d}", "weather in {c}{d}", "how hot is it in {c}{d}", "is it raining in {c}{d}",
                         "{c} weather{d}"]).format(c=ct, d=dw), *call(tool, place=ct, day=day)
        return pick(["what's the weather{d}", "weather{d}", "will it rain{d}", "do i need an umbrella{d}", "how cold is it{d}",
                     "what's the temperature{d}", "is it going to rain{d}"]).format(d=dw), *call(tool, day=day)
    if tool == "news":
        if r.random() < 0.3:
            return pick(["news", "latest news", "what's in the news", "today's headlines", "tell me the news"]), *call(tool)
        return pick(["news about {t}", "latest {t} news", "what's happening in {t}", "headlines on {t}", "any news on {t}",
                     "give me {t} news"]).format(t=t), *call(tool, topic=t)
    if tool == "web_search":
        q = pick(SEARCHES)
        return pick(["search for {q}", "google {q}", "look up {q}", "search the web for {q}", "find {q} on the internet",
                     "search {q}"]).format(q=q), *call(tool, query=q)
    if tool == "read_page":
        u = pick(SITES)
        return pick(["read the page {u}", "read {u} for me", "what does {u} say", "read the article on {u}"]).format(u=u), *call(tool, url=u)
    if tool == "wiki_lookup":
        x = pick(PEOPLE + THINGS)
        return pick(["who is {x}", "tell me about {x}", "wikipedia {x}", "what is {x}", "look up {x} on wikipedia",
                     "who was {x}", "give me a summary of {x}"]).format(x=x), *call(tool, topic=x)
    raise KeyError(tool)


def tools_in_prompt(prefix):
    """[(name, [arg, ...])] in declaration order (= grammar order)."""
    out = []
    for name, body in re.findall(r"declaration:(\w+)\{(.*?)<end_function_declaration>", prefix):
        props = re.search(r"properties:\{(.*)\},required", body)
        args = re.findall(r"(?:^|\},)(\w+):\{description", props.group(1)) if props else []
        out.append((name, args))
    return out


def target(tool, args, order):
    body = ",".join(f"{k}:{ESC}{args[k]}{ESC}" for k in order if k in args)
    return f"<start_function_call>call:{tool}{{{body}}}<end_function_call>"


def context(now):
    line = f"time {now.date()} {now.time().replace(microsecond=0)}"
    if r.random() < 0.3:
        line += ", app " + pick(["com.whatsapp", "com.google.android.youtube", "com.android.chrome", "com.facebook.katana",
                                 "com.miui.home", "com.google.android.gm", "com.spotify.music"])
    return line


WAKE_PREFIXES = ("hey jimi", "jimi", "hey jimmy", "hi jimmy", "izumi", "hayzumi", "a")
FILLERS = ("um", "please", "can you", "okay")


def normalize(text):
    return " ".join(re.sub(r"[?!,:]|\.(?=\s|$)", "", text.lower()).split())


def holdout_key(text):
    text = normalize(text)
    while True:
        old = text
        for prefix in WAKE_PREFIXES + FILLERS:
            if text.startswith(prefix + " "):
                text = text[len(prefix) + 1:]
        for suffix in (" please", " now", " for me"):
            if text.endswith(suffix):
                text = text[:-len(suffix)]
        if old == text:
            return text


def augment_tool(text, tool, rng):
    """Change speech surface only; leave free-text arguments untouched."""
    text = normalize(text)
    if tool == "toggle":
        text = re.sub(r"\bflashlight\b", rng.choice(("flashlight", "flushlight", "flash lite")), text)
        if rng.random() < 0.5:
            text = re.sub(r"\bturn on\b", "down on", text)
        if rng.random() < 0.5:
            text = re.sub(r"\boff\b", "of", text)
    if tool == "set_volume" and rng.random() < 0.6:
        text = re.sub(r"\bvolume\b", "sound", text)
    # Only drop redundant leading words, never an action verb or payload.
    if rng.random() < 0.2:
        first, _, rest = text.partition(" ")
        if first in ("please", "can") or (tool == "toggle" and first in ("turn", "switch")):
            text = rest
    if rng.random() < 0.3:
        text = rng.choice(FILLERS) + " " + text
    if rng.random() < 0.4:
        text = rng.choice(WAKE_PREFIXES) + " " + text
    if rng.random() < 0.08:
        text += rng.choice((" please", " now", " for me"))
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-tool", type=int, default=60)
    ap.add_argument("--chat", type=int, default=600)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--valid", type=float, default=0.05)
    ap.add_argument("--out", type=pathlib.Path, default=OUT)
    ap.add_argument("--prefix", type=pathlib.Path, default=PREFIX)
    a = ap.parse_args()
    r.seed(a.seed)

    prefix = a.prefix.read_text()
    tools = tools_in_prompt(prefix)
    held_out = {holdout_key(json.loads(line)["text"])
                for path in (EVAL, EVAL.with_name("cases_v2.jsonl"))
                for line in path.read_text().splitlines() if line.strip()}
    rows, seen = [], set()

    def add(text, tool, args, now):
        if holdout_key(text) in held_out:
            return False
        text = augment_tool(text, tool, r) if tool else normalize(text)
        if holdout_key(text) in held_out or text in seen:
            return False
        seen.add(text)
        order = dict(tools)[tool] if tool else []
        unknown = set(args) - set(order)
        assert not unknown, (tool, unknown)
        prompt = prefix + f"<start_of_turn>user\n{text}\n({context(now)})<end_of_turn>\n<start_of_turn>model\n"
        comp = target(tool, args, order) if tool else f"{CHAT}<end_of_turn>"
        rows.append({"prompt": prompt, "completion": comp, "text": text, "tool": tool, "args": args})
        return True

    def rand_now():
        base = dt.datetime(2026, 1, 1) + dt.timedelta(days=r.randint(0, 700))
        return base.replace(hour=r.randint(6, 23), minute=r.choice(range(0, 60, 5)))

    for name, _ in tools:
        made = tries = 0
        while made < a.per_tool and tries < a.per_tool * 20:
            tries += 1
            now = rand_now()
            text, tool, args = gen_tool(name, now)
            made += add(text, tool, args, now)
        print(f"{name:22} {made}")
    made = 0
    chats = CHATS.copy()
    r.shuffle(chats)
    for text in chats:
        if made >= a.chat:
            break
        made += add(text, None, {}, rand_now())
    print(f"{'(chat)':22} {made}")

    counts = Counter(row["tool"] for row in rows)
    print(f"Total: {len(rows)}; chat share: {counts[None] / len(rows):.1%}")
    r.shuffle(rows)
    n_valid = max(20, int(len(rows) * a.valid))
    a.out.mkdir(parents=True, exist_ok=True)
    for name, part in (("valid", rows[:n_valid]), ("train", rows[n_valid:])):
        with open(a.out / f"{name}.jsonl", "w") as f:
            for row in part:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(f"{name}: {len(part)} rows → {a.out / (name + '.jsonl')}")


if __name__ == "__main__":
    main()

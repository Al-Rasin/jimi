#!/usr/bin/env bash
# Copies what Jimi saved during real use to the Mac for review:
# daily logs (with crashes), the database (chats, notifications, wake answers), wrong-answer reports,
# command STT texts, and the wake clip list. Audio is skipped unless WITH_AUDIO=1.
set -euo pipefail

: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the phone serial}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$repo_root/training/data/usage/$(date +%F_%H%M)"   # git-ignored
remote=/sdcard/Android/data/com.alrasin.jimi/files
adb_=(adb -s "$ANDROID_SERIAL")
mkdir -p "$dest"

"${adb_[@]}" pull "$remote/logs" "$dest/" >/dev/null 2>&1 || echo "no logs folder yet"
"${adb_[@]}" pull "$remote/reports" "$dest/" >/dev/null 2>&1 || true

# The database lives in app-private storage: copy it out with root (db + wal so recent rows are kept).
tmp=/data/local/tmp/jimi-db
"${adb_[@]}" shell "su -c 'rm -rf $tmp; mkdir -p $tmp; cp /data/data/com.alrasin.jimi/databases/jimi.db* $tmp/; chmod -R 755 $tmp'"
"${adb_[@]}" pull "$tmp" "$dest/db" >/dev/null
"${adb_[@]}" shell "su -c 'rm -rf $tmp'"

mkdir -p "$dest/commands"
if [[ "${WITH_AUDIO:-0}" == 1 ]]; then
    "${adb_[@]}" pull "$remote/commands" "$dest/" >/dev/null
    "${adb_[@]}" pull "$remote/wake" "$dest/" >/dev/null
else
    for f in $("${adb_[@]}" shell "ls $remote/commands/ 2>/dev/null | grep '\.txt$'" | tr -d '\r'); do
        "${adb_[@]}" pull "$remote/commands/$f" "$dest/commands/" >/dev/null
    done
    "${adb_[@]}" shell "ls $remote/wake/ 2>/dev/null" | tr -d '\r' > "$dest/wake_clips.txt"
fi

echo "Saved to: $dest"
du -sh "$dest"/* 2>/dev/null

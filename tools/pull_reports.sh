#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the phone serial}"
command -v adb >/dev/null || { echo "adb is required" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$repo_root/training/data/phone/$(date +%F)"
remote=/sdcard/Android/data/com.alrasin.jarvis/files
adb_command=(adb -s "$ANDROID_SERIAL")

# Expand globs on Android; missing files produce an empty, successful listing.
files="$("${adb_command[@]}" shell "for f in '$remote/reports/wrong_answers.jsonl' '$remote'/commands/*.wav '$remote'/commands/*.txt; do if [ -f \"\$f\" ]; then printf '%s\\n' \"\$f\"; fi; done")"
mkdir -p "$destination/commands"
while IFS= read -r file; do
    file="${file%$'\r'}"
    [[ -n "$file" ]] || continue
    case "$file" in
        "$remote/reports/wrong_answers.jsonl") target="$destination/wrong_answers.jsonl" ;;
        "$remote"/commands/*.wav|"$remote"/commands/*.txt) target="$destination/commands/${file##*/}" ;;
        *) echo "Unexpected remote path: $file" >&2; exit 1 ;;
    esac
    "${adb_command[@]}" pull "$file" "$target"
done <<< "$files"
printf 'Reports folder: %s\n' "$destination"

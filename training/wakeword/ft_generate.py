"""Makes synthetic clips for the hey_jimi fine-tune: wake phrase positives and look-alike negatives.

Output (git-ignored): models/wakeword/ft/{pos,neg}/*.wav
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_samples import generate_samples  # noqa: E402

ROOT = Path(__file__).resolve().parents[2] / "models/wakeword/ft"

POS = ["hey jimi", "hey jimmy", "hey jimi!", "hey, jimmy", "hey jimmy?"]
# Look-alikes plus everyday talk. Never put the wake phrase itself in here, even inside a longer line.
NEG = [
    "hey gimme", "gimme", "hey jamie", "hey jenny", "hey ginny", "hey timmy", "hey kimmy", "hey jim",
    "hey jane", "hey siri", "hey google", "hey jarvis", "hey mimi", "hey jimbo", "jimmy", "jimi",
    "gimme that", "hey, give me", "hey Jesse", "hey Jenna",
    "hey there", "hey you", "hey Jim, hi", "the gym is open", "hey jiminy", "hey chimney", "hey shimmy",
    "a gem in it", "hey, did you eat", "hey jenny me", "hey timi", "hey dimmy", "hey vinny", "hey minnie",
    "jimmy choo", "jammy", "hey Jamal", "hey Jimin", "hey Jamie, come here", "they gimme money",
    "what time is it", "let's go home now", "okay thank you", "she said hey", "turn it off please",
    "I will call you later", "where are you going", "give me the remote", "hey man how are you",
]

if __name__ == "__main__":
    n_pos = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
    n_neg = int(sys.argv[2]) if len(sys.argv) > 2 else 3000
    if n_pos:
        generate_samples(POS, ROOT / "pos", n_pos, length_scales=(0.8, 1.0, 1.2, 1.4))
    generate_samples(NEG, ROOT / "neg", n_neg, length_scales=(0.8, 1.0, 1.2, 1.4))
    print("done")

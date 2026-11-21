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

# Romanized Bangla talk read by English voices: most real false wakes on the phone were Bangla speech.
NEG_BN = [
    "ami jabo na", "tumi kemon acho", "ki koro", "eta ki", "jinish ta dao", "jemon khushi", "jibon", "jomi",
    "hey bhai", "ha ji", "e ji mi", "hai jami", "ji ami", "jhimjhim", "jhimi", "chini dao", "ki je bolo",
    "amar jama", "jamai", "jinish", "jete hobe", "eije shuno", "ei jimmi na", "hoy ni", "accha thik ache",
    "kal ke jabo", "bhat khabo", "pani dao", "ki holo", "amake dao", "jani na", "jodi jai", "aj ke",
    "shei jinish", "chhele ta", "meye ta", "ei je dekho", "aye jao", "ki khobor", "abar bolo",
    "Allah hafez", "inshallah", "mashallah", "assalamu alaikum", "jhamela", "gimmick", "chimni",
]

if __name__ == "__main__":
    n_pos = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
    n_neg = int(sys.argv[2]) if len(sys.argv) > 2 else 3000
    n_bn = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    if n_pos:
        generate_samples(POS, ROOT / "pos", n_pos, length_scales=(0.8, 1.0, 1.2, 1.4))
    if n_neg:
        generate_samples(NEG, ROOT / "neg", n_neg, length_scales=(0.8, 1.0, 1.2, 1.4))
    if n_bn:
        generate_samples(NEG_BN, ROOT / "neg_bn", n_bn, length_scales=(0.8, 1.0, 1.2, 1.4))
    print("done")

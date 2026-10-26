"""Kaggle-only (GPU T4, internet on): goals + two teacher labellings for ROADMAP #66 round 3.

Reads screens.jsonl / prompt.py / privacy.py from the private input dataset. Writes to /kaggle/working:
goals.jsonl, labels_qwen3-4b.jsonl, labels_qwen3-1.7b.jsonl, run_info.json (same row formats as label.py).
"""
import json
import random
import re
import subprocess
import sys
import time
from pathlib import Path

N_GOALS = 8           # natural goals per screen (round 3 had 3; the eval misses were natural goals)
CHUNK = 1500          # goals per vLLM call; rows are appended after each chunk
MAX_LEN = 6144


def sh_install():
    try:
        import vllm  # noqa: F401
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "vllm"], check=True)
    # vllm brings its own torch; the preinstalled torchaudio/torchvision then mismatch and break transformers/vllm
    subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "torchaudio", "torchvision"], check=False)
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "torchvision"], check=True)
    import torch
    print("torch", torch.__version__, "cuda", torch.version.cuda, "gpus", torch.cuda.device_count(), flush=True)


def strip_think(text):
    """Answer after </think>; if there is none (budget ended inside the reasoning), the reasoning text."""
    if "</think>" in text:
        after = text.split("</think>", 1)[1].strip()
        if after:
            return after
        return text.split("</think>", 1)[0].replace("<think>", "").strip()
    return text.replace("<think>", "").strip()


def generate(llm, tok, msgs_list, think, temperature, max_tokens):
    from vllm import SamplingParams
    prompts = [tok.apply_chat_template(m, tokenize=False, add_generation_prompt=True, enable_thinking=think)
               for m in msgs_list]
    sp = SamplingParams(temperature=temperature, max_tokens=max_tokens, seed=0)
    return [o.outputs[0].text for o in llm.generate(prompts, sp)]


def make_llm(model):
    from vllm import LLM
    return LLM(model=model, dtype="half", max_model_len=MAX_LEN, gpu_memory_utilization=0.88,
               enforce_eager=False, seed=0)


def main():
    if not Path("/kaggle/input").is_dir():
        raise SystemExit("Runs on Kaggle only (Mac memory is full).")
    stage = sys.argv[1] if len(sys.argv) > 1 else "all"
    if stage == "all":   # each model runs in its own process so the GPU is fully freed in between
        t_start = time.time()
        sh_install()
        for st in ("4b", "1.7b"):
            subprocess.run([sys.executable, __file__, st], check=True)
        info = json.loads(Path("/kaggle/working/run_info.json").read_text())
        info["timings_s"]["total"] = round(time.time() - t_start)
        Path("/kaggle/working/run_info.json").write_text(json.dumps(info, indent=2))
        print(json.dumps(info, indent=2))
        return
    run_stage(stage)


def run_stage(stage):
    inp = next(Path("/kaggle/input").rglob("screens.jsonl")).parent
    sys.path.insert(0, str(inp))
    from privacy import looks_private
    from prompt import messages, parse_action
    from transformers import AutoTokenizer

    out = Path("/kaggle/working")
    screens = [json.loads(l) for l in open(inp / "screens.jsonl") if l.strip()]
    elems = {s["screen"]: s["elements"] for s in screens}
    info_path = out / "run_info.json"
    info = (json.loads(info_path.read_text()) if info_path.is_file() else
            {"mode": json.loads((inp / "run_mode.json").read_text())["mode"], "screens": len(screens),
             "models": {"a": "Qwen/Qwen3-4B", "b": "Qwen/Qwen3-1.7B"}, "timings_s": {}})
    model = "Qwen/Qwen3-4B" if stage == "4b" else "Qwen/Qwen3-1.7B"
    tok = AutoTokenizer.from_pretrained(model)
    llm = make_llm(model)

    if stage == "4b":   # stage 1: natural goals, thinking off, temp 0.7
        t0 = time.time()
        gp = (f"Below is the UI of an Android screen. Write {N_GOALS} short, natural things a user might ask a voice "
              "assistant to do on this screen. Mix: open a page, change a setting (e.g. 'Change the ...', 'Turn on ...'), "
              "use a feature. Do not just repeat element names. One per line, no numbering, no extra text.\n\n")
        texts = generate(llm, tok, [[{"role": "user", "content": gp + s["elements"]}] for s in screens],
                         think=False, temperature=0.7, max_tokens=3000)
        rows, n_llm = [], 0
        for s, text in zip(screens, texts):
            text = strip_think(text)
            llm_g = [l.strip(" -*\t") for l in text.splitlines() if 3 < len(l.strip()) < 80 and not looks_private(l)][:N_GOALS]
            n_llm += len(llm_g)
            rows += [{"screen": s["screen"], "goal": g} for g in dict.fromkeys(s["template_goals"] + llm_g)]
        random.Random(0).shuffle(rows)
        with open(out / "goals.jsonl", "w") as f:
            f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
        info.update(goals=len(rows), llm_goals=n_llm)
        info["timings_s"]["stage1_goals"] = round(time.time() - t0)
        print("goals", len(rows), "llm", n_llm, flush=True)
        name, think, max_tokens, key = "qwen3-4b", True, 3000, "stage2_label_4b"   # stage 2
    else:               # stage 3
        rows = [json.loads(l) for l in open(out / "goals.jsonl") if l.strip()]
        name, think, max_tokens, key = "qwen3-1.7b", False, 512, "stage3_label_1.7b"

    t0 = time.time()
    n_act = 0
    with open(out / f"labels_{name}.jsonl", "w") as f:
        for i in range(0, len(rows), CHUNK):
            part = rows[i:i + CHUNK]
            texts = generate(llm, tok, [messages(g["goal"], elems[g["screen"]]) for g in part],
                             think=think, temperature=0, max_tokens=max_tokens)
            for g, text in zip(part, texts):
                text = strip_think(text)
                act = parse_action(text)
                n_act += act is not None
                f.write(json.dumps({**g, "raw": text[-300:], "action": act}, ensure_ascii=False) + "\n")
            f.flush()
            print(f"{name} {min(i + CHUNK, len(rows))}/{len(rows)} {time.time() - t0:.0f}s", flush=True)
    info[f"labels_{name}"] = len(rows)
    info[f"parsed_{name}"] = n_act
    info["timings_s"][key] = round(time.time() - t0)
    info_path.write_text(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()

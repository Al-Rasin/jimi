# #66 teacher labelling on a private Kaggle GPU (T4)

Same goals + labels as the local Mac pipeline (`make_goals.py`, `label.py`), but vLLM on Kaggle.
Only element TEXT is uploaded (no xml/png), to a PRIVATE dataset and a PRIVATE kernel. Never use `--public`.
Stages: 4B no-think goals (3 per screen) -> 4B think labels -> 1.7B labels (each model in its own process).
Credentials: the kaggle CLI reads `~/.kaggle` itself. CLI venv: `python3 -m venv /private/tmp/jarvis-kaggle-venv && /private/tmp/jarvis-kaggle-venv/bin/pip install kaggle`.

```bash
cd training/screen/kaggle
python3 prepare.py --owner USER --mode smoke --run-id s1     # 5 screens; or --mode real --run-id r1
python3 submit.py  --owner USER --mode smoke --run-id s1 --wait   # upload, push kernel, poll, download
python3 submit.py  --owner USER --run-id s1 --status
python3 submit.py  --owner USER --run-id s1 --fetch          # -> training/data/screen/kaggle-out-s1/
cd .. && python3 label.py --merge --labels-dir ../data/screen/kaggle-out-r1 \
    --goals ../data/screen/kaggle-out-r1/goals.jsonl --out ../data/screen/kaggle-out-r1/labelled.jsonl
```

Run ids must be new each time (nothing is overwritten). Outputs: `goals.jsonl`, `labels_qwen3-4b.jsonl`,
`labels_qwen3-1.7b.jsonl`, `run_info.json`, kernel log. `--fetch` drops goals that fail the local privacy check.
`kernel.py` is pushed unchanged; it must run on Kaggle (it refuses to run elsewhere).

"""Run openWakeWord's train.py on macOS.

macOS starts worker processes with "spawn", which cannot pickle the lambdas train.py gives to its
DataLoader ("Can't pickle <function <lambda>>"). Linux uses "fork", so force fork here.
Also: before ONNX export, the trained PyTorch model is saved next to the output (<name>.pt), so a failed
export never loses a 1-hour training run.
Usage: python run_oww_train.py <path/to/oww_train.py> <train.py args...>
"""
import multiprocessing
import runpy
import sys



def _save_before_export():
    import torch
    real_export = torch.onnx.export

    def export(model, args, f, *a, **kw):
        try:
            torch.save(model, str(f).rsplit(".", 1)[0] + ".pt")
        except Exception as e:  # backup only; never block the real export
            print("warning: could not save .pt backup:", e)
        return real_export(model, args, f, *a, **kw)

    torch.onnx.export = export


if __name__ == "__main__":
    multiprocessing.set_start_method("fork", force=True)
    _save_before_export()
    script = sys.argv[1]
    sys.argv = [script] + sys.argv[2:]
    runpy.run_path(script, run_name="__main__")

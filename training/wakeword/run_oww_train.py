"""Run openWakeWord's train.py on macOS.

macOS starts worker processes with "spawn", which cannot pickle the lambdas train.py gives to its
DataLoader ("Can't pickle <function <lambda>>"). Linux uses "fork", so force fork here.
Usage: python run_oww_train.py <path/to/oww_train.py> <train.py args...>
"""
import multiprocessing
import runpy
import sys

if __name__ == "__main__":
    multiprocessing.set_start_method("fork", force=True)
    script = sys.argv[1]
    sys.argv = [script] + sys.argv[2:]
    runpy.run_path(script, run_name="__main__")

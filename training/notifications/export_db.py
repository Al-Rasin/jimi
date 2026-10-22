"""Explicitly stop Jarvis, pull its Room DB with root, and retain a local snapshot.

Running this interrupts the assistant; reopen Jarvis afterwards. The snapshot
contains all Room tables, so keep it in the git-ignored training/data directory.
No adb operations run when this module is imported.
"""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import os
from pathlib import Path
import shlex
import sqlite3
import subprocess
import tempfile
import uuid


PACKAGE = "com.alrasin.jarvis"
REMOTE_DB = f"/data/data/{PACKAGE}/databases/jarvis.db"
DATA_DIR = Path(__file__).resolve().parents[1] / "data" / "notifications"


def export_database(output: Path, serial: str | None = None) -> Path:
    """Export into a new directory; force-stop makes DB and WAL copies coherent."""
    output = Path(output)
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite {output}")
    adb = ["adb"] + (["-s", serial] if serial else [])
    remote = f"/data/local/tmp/jarvis-notifications-{uuid.uuid4().hex}"

    def run(*args, check=True):
        return subprocess.run(adb + list(args), check=check, capture_output=True, text=True)

    def root(script, check=True):
        return run("shell", "su", "-c", shlex.quote(script), check=check)

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".notification-export-", dir=output.parent) as local:
        try:
            root(f"am force-stop {PACKAGE}")
            # Copy WAL alongside the database; SQLite replays it during backup.
            root(
                f"set -e; umask 077; mkdir {remote}; "
                f"cp {REMOTE_DB} {remote}/jarvis.db; "
                f"if [ -f {REMOTE_DB}-wal ]; then cp {REMOTE_DB}-wal {remote}/jarvis.db-wal; fi; "
                f"chown -R 2000:2000 {remote}; chmod 700 {remote}; chmod 600 {remote}/*"
            )
            snapshot = Path(local) / "snapshot"
            run("pull", remote, str(snapshot))
            source = snapshot / "jarvis.db"
            if not source.is_file():
                raise RuntimeError("adb pull did not produce jarvis.db")
            # Opening normally allows recovery of the copied WAL; never immutable=1.
            destination = Path(local) / "jarvis.db"
            with closing(sqlite3.connect(source)) as src, closing(sqlite3.connect(destination)) as dst:
                if src.execute("PRAGMA quick_check").fetchone() != ("ok",):
                    raise RuntimeError("Exported database failed SQLite quick_check")
                src.backup(dst)
            os.chmod(destination, 0o600)
            output.mkdir(mode=0o700)
            destination.rename(output / "jarvis.db")
        finally:
            cleanup = root(f"rm -rf {remote}", check=False)
            if cleanup.returncode:
                raise RuntimeError(f"Remote cleanup failed; remove root staging directory {remote}")
    return output / "jarvis.db"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial (required when multiple devices are attached)")
    parser.add_argument(
        "--output", type=Path,
        default=DATA_DIR / ("export-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")),
        help="new private export directory (default: git-ignored training/data/notifications/export-<UTC>)",
    )
    args = parser.parse_args()
    print("Stopping Jarvis for a consistent DB export. Reopen Jarvis manually afterwards.", flush=True)
    print(export_database(args.output, args.serial))


if __name__ == "__main__":
    main()

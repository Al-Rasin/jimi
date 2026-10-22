import os
from contextlib import closing
from pathlib import Path
import shlex
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from training.notifications.export_db import REMOTE_DB, export_database


class ExportDatabaseTest(unittest.TestCase):
    def test_mocked_adb_preserves_wal_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source.db"
            connection = sqlite3.connect(source)
            self.addCleanup(connection.close)
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("CREATE TABLE notification_log (title TEXT)")
            connection.execute("INSERT INTO notification_log VALUES ('synthetic WAL row')")
            connection.commit()
            commands = []

            def fake_run(command, **kwargs):
                commands.append(command)
                if "pull" in command:
                    target = Path(command[-1])
                    target.mkdir()
                    shutil.copyfile(source, target / "jarvis.db")
                    shutil.copyfile(str(source) + "-wal", target / "jarvis.db-wal")
                return subprocess.CompletedProcess(command, 0, "", "")

            with patch("training.notifications.export_db.subprocess.run", side_effect=fake_run):
                result = export_database(base / "export", serial="test-device")
            with closing(sqlite3.connect(result)) as database:
                self.assertEqual(database.execute("SELECT title FROM notification_log").fetchall(), [("synthetic WAL row",)])
            self.assertIn("am force-stop com.alrasin.jarvis", shlex.split(commands[0][-1])[0])
            self.assertIn(REMOTE_DB + "-wal", commands[1][-1])
            self.assertIn("rm -rf /data/local/tmp/jarvis-notifications-", commands[-1][-1])
            self.assertEqual(commands[0][:3], ["adb", "-s", "test-device"])
            self.assertEqual(os.stat(result).st_mode & 0o777, 0o600)
            self.assertEqual(os.stat(result.parent).st_mode & 0o777, 0o700)

    def test_cleanup_on_pull_failure(self):
        commands = []

        def fake_run(command, **kwargs):
            commands.append(command)
            if "pull" in command:
                raise subprocess.CalledProcessError(1, command)
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "export"
            with patch("training.notifications.export_db.subprocess.run", side_effect=fake_run):
                with self.assertRaises(subprocess.CalledProcessError):
                    export_database(output)
            self.assertFalse(output.exists())
            self.assertIn("rm -rf", commands[-1][-1])

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("training.notifications.export_db.subprocess.run") as run:
                with self.assertRaises(FileExistsError):
                    export_database(Path(directory))
                run.assert_not_called()

    def test_cleanup_failure_is_reported(self):
        def fake_run(command, **kwargs):
            if "rm -rf" in command[-1]:
                return subprocess.CompletedProcess(command, 1, "", "denied")
            raise subprocess.CalledProcessError(1, command)

        with tempfile.TemporaryDirectory() as directory:
            with patch("training.notifications.export_db.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(RuntimeError, "Remote cleanup failed"):
                    export_database(Path(directory) / "export")


if __name__ == "__main__":
    unittest.main()

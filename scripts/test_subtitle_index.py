import contextlib
import importlib.machinery
import importlib.util
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader(
    "subtitle_index", str(Path(__file__).with_name("subtitle-index")))
spec = importlib.util.spec_from_loader(loader.name, loader)
sidx = importlib.util.module_from_spec(spec)
loader.exec_module(sidx)


class RepairQueueTests(unittest.TestCase):
    def test_finished_attempts_do_not_reappear_as_unprocessed_files(self):
        for outcome in ("failed", "unfixable", "interrupted"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                first, second = root / "first.mkv", root / "second.mkv"
                for path in (first, second):
                    path.write_bytes(b"original")
                queue = root / "needs-fix.txt"
                queue.write_text(f"{first}\n{second}\n")

                def classify(path, probe_empty=False):
                    if Path(path) == second:
                        return sidx.FAST, "already indexed"
                    return sidx.NEEDS_FIX, "no subtitle index"

                def remux(command, **kwargs):
                    if outcome == "interrupted":
                        raise KeyboardInterrupt
                    Path(command[3]).write_bytes(b"remuxed")
                    return SimpleNamespace(
                        returncode=2 if outcome == "failed" else 0,
                        stderr="remux failed", stdout="")

                args = SimpleNamespace(list=str(queue), out_dir=tmp, dry_run=False)
                with patch.object(sidx.shutil, "which", return_value="mkvmerge"), \
                        patch.object(sidx, "classify", side_effect=classify), \
                        patch.object(sidx.subprocess, "run", side_effect=remux), \
                        contextlib.redirect_stdout(io.StringIO()):
                    result = sidx.cmd_fix(args)

                expected = {"failed": [str(first)], "unfixable": [],
                            "interrupted": [str(first), str(second)]}[outcome]
                self.assertEqual(queue.read_text().splitlines(), expected)
                self.assertEqual(result, 0 if outcome == "unfixable" else 1)
                self.assertEqual(first.read_bytes(), b"original")
                self.assertEqual(second.read_bytes(), b"original")
                self.assertEqual(list(root.glob(".subidx-*")), [])


if __name__ == "__main__":
    unittest.main()

import importlib.machinery
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import subprocess
import urllib.error
import io

WORKER = Path(__file__).parents[1] / "worker_v3.py"
loader = importlib.machinery.SourceFileLoader("worker_v3_under_test", str(WORKER))
spec = importlib.util.spec_from_loader("worker_v3_under_test", loader)
worker = importlib.util.module_from_spec(spec)
loader.exec_module(worker)


class WorkerTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        worker.HOME = self.root
        worker.LOG_FILE = self.root / "worker.log"

    def tearDown(self):
        self.temp.cleanup()

    def test_curl_fallback_uses_ipv4_http11_and_private_config(self):
        captured = {}

        def fake_run(args, **kwargs):
            captured["args"] = args
            config = Path(args[2]).read_text()
            captured["config"] = config
            body_path = Path(config.split('data-binary = "@', 1)[1].split('"', 1)[0])
            captured["body"] = body_path.read_bytes()
            captured["config_mode"] = Path(args[2]).stat().st_mode & 0o777
            captured["body_mode"] = body_path.stat().st_mode & 0o777
            return subprocess.CompletedProcess(args, 0, b'{"ok":true}200', b"")

        with mock.patch.object(worker.shutil, "which", return_value="/usr/bin/curl"), \
             mock.patch.object(worker.subprocess, "run", side_effect=fake_run):
            result = worker._curl_api_fallback("a" * 64, b'{"action":"heartbeat"}', 15)

        self.assertEqual(result, {"ok": True})
        self.assertIn("--ipv4", captured["args"])
        self.assertIn("--http1.1", captured["args"])
        self.assertIn("Authorization: Bearer " + "a" * 64, captured["config"])
        self.assertNotIn("a" * 64, " ".join(captured["args"]))
        self.assertEqual(captured["body"], b'{"action":"heartbeat"}')
        self.assertEqual(captured["config_mode"], 0o600)
        self.assertEqual(captured["body_mode"], 0o600)

    def test_api_falls_back_after_url_transport_error(self):
        token = self.root / "device-token"
        token.write_text("b" * 64)
        worker.TOKEN_FILE = token
        with mock.patch.object(worker.urllib.request, "urlopen",
                               side_effect=urllib.error.URLError("offline")), \
             mock.patch.object(worker, "_curl_api_fallback",
                               return_value={"ok": True, "device_id": "device"}) as fallback:
            result = worker.api({"action": "heartbeat"}, timeout=5)
        self.assertTrue(result["ok"])
        fallback.assert_called_once()
        self.assertTrue((self.root / ".termux/worker-v3.heartbeat").exists())

    def test_api_does_not_retry_permanent_unauthorized_response(self):
        token = self.root / "device-token"
        token.write_text("c" * 64)
        worker.TOKEN_FILE = token
        error = urllib.error.HTTPError(
            worker.BASE_URL, 401, "Unauthorized", {}, io.BytesIO(b'{"error":"unauthorized"}')
        )
        with mock.patch.object(worker.urllib.request, "urlopen", side_effect=error), \
             mock.patch.object(worker, "_curl_api_fallback") as fallback:
            with self.assertRaises(urllib.error.HTTPError):
                worker.api({"action": "heartbeat"}, timeout=5)
        fallback.assert_not_called()


if __name__ == "__main__":
    unittest.main()

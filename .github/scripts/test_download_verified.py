#!/usr/bin/env python3
"""Run the workflow downloader against an owned HTTP server, without public downloads."""
import argparse
from collections import Counter
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).with_name("download-verified.sh")
BODY = b"verified archive bytes\x00\xff"
DIGEST = hashlib.sha256(BODY).hexdigest()


@contextmanager
def source_server(responses):
    calls = []
    counts = Counter()
    release = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            calls.append(self.path)
            counts[self.path] += 1
            choices = responses[self.path]
            response = choices[min(counts[self.path] - 1, len(choices) - 1)]
            if response == "no-response":
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if response == "stalled":
                release.wait(10)
                return
            status, body, declared_length = response
            self.send_response(status)
            self.send_header("Content-Length", str(declared_length))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
            self.close_connection = True

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}", calls
        finally:
            release.set()
            server.shutdown()
            thread.join(timeout=5)
            assert not thread.is_alive(), "Owned HTTP server must finish on test exit"


def response(status=200, body=BODY, declared_length=None):
    return status, body, len(body) if declared_length is None else declared_length


class DownloadVerifiedTest(unittest.TestCase):
    def download(self, responses, expected_calls, success, expected_messages=(), options=()):
        with source_server(responses) as (base, calls), tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "archive with spaces"
            result = subprocess.run(
                ["bash", str(SCRIPT), *options, str(output), DIGEST,
                 base + "/primary", base + "/secondary"],
                text=True, capture_output=True, timeout=30,
            )
            self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)
            self.assertEqual(expected_calls, calls, result.stdout + result.stderr)
            if success:
                self.assertEqual(BODY, output.read_bytes())
            else:
                self.assertFalse(output.exists(), "An unverified archive must not be published")
            self.assertEqual([output.name] if success else [],
                             [path.name for path in Path(directory).iterdir()],
                             "Temporary downloads must be removed on every exit")
            for message in expected_messages:
                self.assertIn(message.format(base=base), result.stderr.splitlines())

    def test_first_source_success(self):
        self.download({"/primary": [response()]}, ["/primary"], True)

    def test_504_uses_second_source(self):
        self.download({"/primary": [response(504)], "/secondary": [response()]},
                      ["/primary"] * 3 + ["/secondary"], True,
                      ["Download failed: {base}/primary; HTTP 504; curl exit 22; attempt 3 of 3."])

    def test_wrong_digest_is_refused(self):
        wrong = b"different archive"
        actual = hashlib.sha256(wrong).hexdigest()
        self.download({"/primary": [response(body=wrong)], "/secondary": [response(body=wrong)]},
                      ["/primary", "/secondary"], False,
                      [f"Download refused: {{base}}/primary; HTTP 200; attempt 1 of 3; SHA-256 {actual}; expected {DIGEST}."])

    def test_early_close_is_refused(self):
        self.download({"/primary": [response(body=BODY[:2], declared_length=len(BODY))],
                       "/secondary": [response(body=BODY[:2], declared_length=len(BODY))]},
                      ["/primary", "/secondary"], False,
                      ["Download failed: {base}/primary; HTTP 200; curl exit 18; attempt 1 of 3."])

    def test_404_moves_on_without_retry(self):
        self.download({"/primary": [response(404)], "/secondary": [response()]},
                      ["/primary", "/secondary"], True,
                      ["Download failed: {base}/primary; HTTP 404; curl exit 22; attempt 1 of 3."])

    def test_429_retries_then_succeeds(self):
        self.download({"/primary": [response(429), response()]}, ["/primary"] * 2, True)

    def test_no_response_uses_second_source(self):
        self.download({"/primary": ["no-response"], "/secondary": [response()]},
                      ["/primary"] * 3 + ["/secondary"], True,
                      ["Download failed: {base}/primary; HTTP 000; curl exit 52; attempt 3 of 3."])

    def test_all_sources_stop_after_three_attempts(self):
        self.download({"/primary": [response(503)], "/secondary": [response(504)]},
                      ["/primary"] * 3 + ["/secondary"] * 3, False,
                      ["No source supplied an archive matching the pinned SHA-256 after 6 attempt(s)."])

    def test_wrong_digest_can_use_verified_second_source(self):
        self.download({"/primary": [response(body=b"different")], "/secondary": [response()]},
                      ["/primary", "/secondary"], True)

    def test_stalled_source_reaches_second_source(self):
        self.download({"/primary": ["stalled"], "/secondary": [response()]},
                      ["/primary"] * 3 + ["/secondary"], True,
                      ["Download failed: {base}/primary; HTTP 000; curl exit 28; attempt 3 of 3."],
                      options=("--max-time", "1"))


class XmlResult(unittest.TextTestResult):
    def startTest(self, test):
        super().startTest(test)
        self.case = ET.SubElement(self.suite, "testcase", name=test.id())
        self.started = time.monotonic()

    def stopTest(self, test):
        self.case.set("time", str(time.monotonic() - self.started))
        super().stopTest(test)

    def addFailure(self, test, err):
        super().addFailure(test, err)
        ET.SubElement(self.case, "failure").text = self._exc_info_to_string(err, test)

    def addError(self, test, err):
        super().addError(test, err)
        ET.SubElement(self.case, "error").text = self._exc_info_to_string(err, test)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    options, selectors = parser.parse_known_args()
    suite = unittest.defaultTestLoader.loadTestsFromNames(selectors, module=__import__(__name__)) if selectors else unittest.defaultTestLoader.loadTestsFromTestCase(DownloadVerifiedTest)
    report = ET.Element("testsuite", name="DownloadVerifiedTest")
    XmlResult.suite = report
    result = unittest.TextTestRunner(resultclass=XmlResult, verbosity=2).run(suite)
    report.set("tests", str(result.testsRun))
    report.set("failures", str(len(result.failures)))
    report.set("errors", str(len(result.errors)))
    options.log.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(report).write(options.log, encoding="utf-8", xml_declaration=True)
    raise SystemExit(not result.wasSuccessful())

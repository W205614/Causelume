"""Exercise the existing bounded readiness probe against a slow real HTTP listener."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time
import unittest

from verify_candidate import ready, wait_for


class ReadinessTest(unittest.TestCase):
    def test_read_timeout_remains_not_ready_until_the_listener_responds(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.server.slow:
                    time.sleep(4)
                self.send_response(200)
                self.end_headers()

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        server.slow = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/health"
            self.assertFalse(ready(url))
            server.slow = False
            self.assertTrue(wait_for("healthy listener", lambda: ready(url), timeout=5))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()

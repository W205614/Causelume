"""Serve the browser fixtures on loopback with the OS connection backlog."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket


class TestServer(ThreadingHTTPServer):
    # Concurrent module requests overflow Python 3.13's default backlog of five on Windows.
    request_queue_size = socket.SOMAXCONN


if __name__ == "__main__":
    handler = partial(SimpleHTTPRequestHandler, directory=Path(__file__).resolve().parents[1] / "public")
    with TestServer(("127.0.0.1", 17643), handler) as server:
        server.serve_forever()

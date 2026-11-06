"""Run inside the pinned Holmes image with --network none and no credentials."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

import yaml


def verify():
    model_requests = []

    class Model(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            assert self.path == "/v1/responses" and body["model"] == "gpt-6.1-sol"
            model_requests.append(body)
            if any(item.get("type") == "function_call_output" for item in body["input"]):
                output = [{"type": "message", "role": "assistant", "id": "msg_1", "status": "completed",
                           "content": [{"type": "output_text", "text": "contract complete", "annotations": []}]}]
            else:
                assert {t["name"] for t in body["tools"]} == {"checkout_metrics", "checkout_logs"}
                output = [{"type": "function_call", "id": f"fc_{index}", "call_id": f"call_{index}",
                           "name": name, "arguments": "{}", "status": "completed"}
                          for index, name in enumerate(("checkout_metrics", "checkout_logs"))]
            data = json.dumps({"id": f"resp_{len(model_requests)}", "object": "response", "created_at": 1,
                               "status": "completed", "model": "gpt-6.1-sol", "output": output}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    with ThreadingHTTPServer(("127.0.0.1", 0), Model) as model, tempfile.TemporaryDirectory() as folder:
        threading.Thread(target=model.serve_forever, daemon=True).start()
        config = yaml.safe_load(Path(__file__).with_name("holmes-snapshot.yaml").read_text())
        assert config["api_base"] == "https://api.openai.com/v1"
        config["api_base"] = f"http://127.0.0.1:{model.server_port}/v1"
        Path(folder, "config.yaml").write_text(yaml.safe_dump(config))
        env = {**os.environ, "HOLMES_CONFIGPATH_DIR": folder, "LITELLM_MODE": "PRODUCTION",
               "LITELLM_LOCAL_MODEL_COST_MAP": "True", "PYTHON_DOTENV_DISABLED": "1",
               "LITELLM_ROUTE_ALL_CHAT_OPENAI_TO_RESPONSES": "true", "HOLMES_API_KEY": "contract-only",
               "OPENAI_API_KEY": "contract-only", "OPENAI_BASE_URL": config["api_base"]}
        process = subprocess.Popen([sys.executable, "-u", "server.py"], cwd="/app", env=env)
        try:
            base = "http://127.0.0.1:5050"
            deadline = time.monotonic() + 50
            while True:
                try:
                    with urlopen(base + "/healthz", timeout=1):
                        break
                except URLError:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.3)

            info_req = Request(base + "/api/info?detail=full", headers={"Authorization": "Bearer contract-only"})
            with urlopen(info_req, timeout=8) as response:
                info = json.load(response)
            allowed = [item for item in info["toolsets"] if item["enabled"]]
            assert info["auth_enabled"] is True and len(allowed) == 1
            assert (allowed[0]["name"], allowed[0]["tool_count"], allowed[0]["status"]) == (
                "causelume_snapshot", 2, "enabled")

            def chat(metrics, logs):
                headers = {"Authorization": "Bearer contract-only", "Content-Type": "application/json",
                           "X-Causelume-Metrics": metrics, "X-Causelume-Logs": logs}
                req = Request(base + "/api/chat", data=b'{"ask":"Inspect this snapshot."}', headers=headers)
                with urlopen(req, timeout=55) as response:
                    return json.load(response)["tool_calls"]

            views = ['{"snapshotId":"sample-A","requests":3}', '{"snapshotId":"sample-A","latestError":null}']
            calls = chat(*(view.encode().hex() for view in views))
            recovered = {call["tool_name"]: call["result"]["data"] for call in calls}
            assert recovered == dict(zip(("checkout_metrics", "checkout_logs"), views)), recovered
            assert all(call["result"]["status"] == "success" for call in calls)
            marker = Path(folder, "unexpected-execution")
            injected = f"'; touch {marker}; echo '"
            calls = chat(injected, views[1].encode().hex())
            # Holmes checks every incoming context header before executing either command.
            assert len(calls) == 2
            assert {call["tool_name"]: call["result"]["status"] for call in calls} == {
                "checkout_metrics": "error", "checkout_logs": "error"}
            assert not marker.exists()
            print("Verified actual Holmes tool outputs, Responses loopback, and rejected injected header.", flush=True)
        finally:
            process.terminate()
            process.wait(timeout=20)
            model.shutdown()


if __name__ == "__main__":
    verify()

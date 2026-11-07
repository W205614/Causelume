"""Run inside the pinned Holmes image with --network none and no credentials."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import yaml


def verify():
    model_requests = []
    reject_provider = False

    class Model(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            assert body["max_output_tokens"] == 1200
            assert self.path == "/v1/responses" and body["model"] == "gpt-6.1-sol"
            model_requests.append(body)
            if reject_provider:
                data = b'{"error":{"message":"Synthetic provider unavailable","type":"server_error"}}'
                self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if any(item.get("type") == "function_call_output" for item in body["input"]):
                output = [{"type": "message", "role": "assistant", "id": "msg_1", "status": "completed",
                           "content": [{"type": "output_text", "text": "contract complete", "annotations": []}]}]
            else:
                assert {t["name"] for t in body["tools"]} == {"frozen_metrics", "frozen_logs"}
                output = [{"type": "function_call", "id": f"fc_{index}", "call_id": f"call_{index}",
                           "name": name, "arguments": "{}", "status": "completed"}
                          for index, name in enumerate(("frozen_metrics", "frozen_logs"))]
            data = json.dumps({"id": f"resp_{len(model_requests)}", "object": "response", "created_at": 1,
                               "status": "completed", "model": "gpt-6.1-sol", "output": output,
                               "usage": {"input_tokens": 100, "output_tokens": 12, "total_tokens": 112}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    with ThreadingHTTPServer(("127.0.0.1", 0), Model) as model, tempfile.TemporaryDirectory() as folder:
        threading.Thread(target=model.serve_forever, daemon=True).start()
        config = yaml.safe_load(Path(__file__).with_name("holmes-observations.yaml").read_text())
        assert config["api_base"] == "https://api.openai.com/v1"
        config["api_base"] = f"http://127.0.0.1:{model.server_port}/v1"
        Path(folder, "config.yaml").write_text(yaml.safe_dump(config))
        env = {**os.environ, "HOLMES_CONFIGPATH_DIR": folder, "LITELLM_MODE": "PRODUCTION",
               "OVERRIDE_MAX_OUTPUT_TOKEN":"1200", "OVERRIDE_MAX_CONTENT_SIZE":"16384",
               "ENABLE_CONVERSATION_HISTORY_COMPACTION":"false", "LITELLM_LOCAL_MODEL_COST_MAP": "True", "PYTHON_DOTENV_DISABLED": "1",
               "LITELLM_ROUTE_ALL_CHAT_OPENAI_TO_RESPONSES": "true", "HOLMES_API_KEY": "contract-only",
               "OPENAI_API_KEY": "contract-only", "OPENAI_BASE_URL": config["api_base"]}
        env.update(LLM_REQUEST_TIMEOUT="60", LLM_RATE_LIMIT_MAX_WAIT_SECONDS="0")
        process = subprocess.Popen([sys.executable, "-u", "server.py"], cwd="/app", env=env)
        try:
            base = "http://127.0.0.1:5050"
            deadline = time.monotonic() + 50
            while True:
                try:
                    with urlopen(base + "/healthz", timeout=1):
                        break
                except URLError:
                    if process.poll() is not None:
                        raise RuntimeError(f"Holmes exited during startup: {process.returncode}") from None
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Holmes /healthz did not become ready within 50 seconds") from None
                    time.sleep(0.3)

            info_req = Request(base + "/api/info?detail=full", headers={"Authorization": "Bearer contract-only"})
            with urlopen(info_req, timeout=8) as response:
                info = json.load(response)
            allowed = [item for item in info["toolsets"] if item["enabled"]]
            assert info["auth_enabled"] is True and len(allowed) == 1
            assert (allowed[0]["name"], allowed[0]["tool_count"], allowed[0]["status"]) == (
                "causelume_observations", 2, "enabled")

            def chat(metrics, logs):
                headers = {"Authorization": "Bearer contract-only", "Content-Type": "application/json",
                           "X-Causelume-Metrics": metrics, "X-Causelume-Logs": logs}
                req = Request(base + "/api/chat", data=b'{"ask":"Inspect this snapshot."}', headers=headers)
                with urlopen(req, timeout=55) as response:
                    return json.load(response)

            views = ['{"snapshotId":"sample-A","source":"prometheus","samples":[{"signal":"min_up","value":0}]}',
                     '{"snapshotId":"sample-A","source":"loki","samples":[{"event":"connection_refused"}]}']
            answer = chat(*(base64.urlsafe_b64encode(view.encode()).decode().rstrip("=") for view in views))
            costs = answer["metadata"].get("costs", {})
            assert (costs.get("prompt_tokens"), costs.get("completion_tokens")) == (200, 24)
            print("Holmes aggregated two local model turns: 200 input / 24 output tokens")
            calls = answer["tool_calls"]
            recovered = {call["tool_name"]: call["result"]["data"] for call in calls}
            assert recovered == dict(zip(("frozen_metrics", "frozen_logs"), views)), recovered
            assert all(call["result"]["status"] == "success" for call in calls)
            marker = Path(folder, "unexpected-execution")
            injected = f"'; touch {marker}; echo '"
            calls = chat(injected, base64.urlsafe_b64encode(views[1].encode()).decode().rstrip("="))["tool_calls"]
            # Holmes checks every incoming context header before executing either command.
            assert len(calls) == 2
            assert {call["tool_name"]: call["result"]["status"] for call in calls} == {
                "frozen_metrics": "error", "frozen_logs": "error"}
            assert not marker.exists()
            print("Verified actual Holmes tool outputs, Responses loopback, and rejected injected header.", flush=True)
            before = len(model_requests)
            reject_provider = True
            try:
                chat(*(base64.urlsafe_b64encode(view.encode()).decode().rstrip("=") for view in views))
            except HTTPError as error:
                assert error.code == 500, error.code
            else:
                raise AssertionError("Provider rejection was reported as a successful investigation")
            assert len(model_requests) == before + 1, "Provider failure was retried"
            print("Verified provider 503 fails after one request without an implicit retry.", flush=True)
        finally:
            process.terminate()
            process.wait(timeout=20)
            model.shutdown()


if __name__ == "__main__":
    verify()

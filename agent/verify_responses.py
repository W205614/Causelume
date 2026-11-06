"""Verify the pinned LiteLLM bridge against a loopback Responses endpoint."""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.metadata import version
from pathlib import Path


def loopback_only(event, args):
    if event == "socket.connect" and args[1][0] not in {"127.0.0.1", "::1"}:
        raise RuntimeError("Contract verification only permits loopback traffic")


def verify():
    sys.addaudithook(loopback_only)
    os.environ.update(LITELLM_MODE="PRODUCTION", PYTHON_DOTENV_DISABLED="1",
                      LITELLM_LOCAL_MODEL_COST_MAP="True", LITELLM_ROUTE_ALL_CHAT_OPENAI_TO_RESPONSES="true",
                      OPENAI_API_KEY="contract-test-only", NO_PROXY="127.0.0.1,localhost")
    import litellm
    import yaml

    assert version("litellm") == "1.103.5"
    config = yaml.safe_load(Path(__file__).with_name("holmes.yaml").read_text(encoding="utf-8"))
    assert config["api_base"] == "https://api.openai.com/v1"
    seen = []

    class Provider(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            seen.append((self.path, body))
            response = {"id": "resp_contract", "object": "response", "created_at": 1, "status": "completed",
                        "model": "gpt-6.1-sol", "output": [
                            {"type": "function_call", "id": "fc_01", "call_id": "call_01", "name": "checkout_metrics",
                             "arguments": "{}", "status": "completed"}]}
            data = json.dumps(response).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    with ThreadingHTTPServer(("127.0.0.1", 0), Provider) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = litellm.completion(
                model=config["model"], base_url=f"http://127.0.0.1:{server.server_port}/v1",
                api_key="contract-test-only", num_retries=0, timeout=5,
                messages=[{"role": "user", "content": "Inspect checkout metrics."}],
                tools=[{"type": "function", "function": {"name": "checkout_metrics",
                        "description": "Read counters", "parameters": {"type": "object", "properties": {}}}}])
        finally:
            server.shutdown()
            thread.join()
    assert len(seen) == 1
    path, request = seen[0]
    assert path == "/v1/responses" and request["model"] == "gpt-6.1-sol"
    assert request["tools"][0]["name"] == "checkout_metrics"
    assert result.choices[0].message.tool_calls[0].function.name == "checkout_metrics"
    print("LiteLLM 1.103.5 -> loopback POST /v1/responses -> function call verified")


if __name__ == "__main__":
    verify()

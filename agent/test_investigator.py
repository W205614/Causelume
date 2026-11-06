import json
import os
import threading
import time
import unittest
from contextlib import contextmanager
from unittest.mock import patch

from flask import Flask, request
from werkzeug.exceptions import GatewayTimeout
from werkzeug.serving import make_server

import checkout
import investigator


@contextmanager
def http_service(app):
    server = make_server("127.0.0.1", 0, app, threaded=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


def auth(token):
    return {"Authorization": f"Bearer {token}"}


class InvestigationTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.dict(os.environ, {
            "AGENT_TOKEN": "test-agent", "EVIDENCE_TOKEN": "test-reader",
            "DEMO_CONTROL_TOKEN": "test-controller", "AGENT_MODE": "offline"}))
        demo = checkout.create_app()
        self.demo = demo.test_client()
        os.environ["DEMO_URL"] = self.enterContext(http_service(demo))
        self.agent = investigator.create_app().test_client()

    def run_task(self):
        return self.agent.post("/investigate", json={"service": "checkout", "taskId": "incident-16"},
                               headers=auth("test-agent"))

    def toggle(self, enabled):
        response = self.demo.post("/control", json={"fault": enabled}, headers=auth("test-controller"))
        self.assertEqual(response.json, {"fault": enabled})

    def test_fault_observations_and_recovery(self):
        self.assertEqual(self.run_task().status_code, 422)
        self.toggle(True)
        failure = self.demo.get("/checkout")
        self.assertEqual(failure.status_code, 503)
        diagnosis = self.run_task()
        self.assertEqual(diagnosis.status_code, 200)
        self.assertEqual(diagnosis.json["mode"], "offline")
        self.assertEqual(diagnosis.json["taskId"], "incident-16")
        self.assertEqual([e["source"] for e in diagnosis.json["evidence"]],
                         ["checkout:/metrics", "checkout:/logs"])
        self.assertIn(failure.json["id"], diagnosis.json["evidence"][1]["detail"])
        self.toggle(False)
        self.assertEqual(self.demo.get("/checkout").status_code, 200)
        healthy = self.run_task().json
        self.assertEqual(len(healthy["evidence"]), 1)
        self.assertIn("未发现 5xx", healthy["possibleCause"])

    def test_no_write_privilege_and_input_validation(self):
        self.assertEqual(self.agent.post("/investigate").status_code, 401)
        self.assertEqual(self.demo.get("/metrics").status_code, 401)
        self.assertEqual(self.demo.post("/control", json={"fault": True},
                                        headers=auth("test-reader")).status_code, 401)
        for body, status in [([], 400), ({"taskId": "x", "service": "http://invalid"}, 422)]:
            self.assertEqual(self.agent.post("/investigate", json=body, headers=auth("test-agent")).status_code,
                             status)

    def test_bad_observability_credentials(self):
        with patch.dict(os.environ, {"EVIDENCE_TOKEN": "invalid-reader"}):
            self.agent = investigator.create_app().test_client()
        self.assertEqual(self.run_task().status_code, 502)

    def test_model_http_contract_and_failures(self):
        holmes = Flask("holmes-contract-double")
        enabled = [{"name": "causelume_checkout", "enabled": True, "status": "enabled", "tool_count": 2}]
        analysis = {key: f"test {key}" for key in investigator.FIELDS}
        model_status = 200
        chats = []

        @holmes.get("/api/info")
        def info():
            self.assertEqual(request.args["detail"], "full")
            self.assertEqual(request.headers["Authorization"], "Bearer test-holmes")
            return {"auth_enabled": True, "toolsets": enabled}

        @holmes.post("/api/chat")
        def chat():
            chats.append(request.json)
            return {"analysis": json.dumps(analysis)}, model_status

        with http_service(holmes) as url, patch.dict(os.environ, {
                "HOLMES_URL": url, "AGENT_MODE": "holmes", "HOLMES_API_KEY": "test-holmes"}):
            self.agent = investigator.create_app().test_client()
            self.demo.get("/checkout")
            result = self.run_task()
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.json["possibleCause"], analysis["possibleCause"])
            self.assertEqual(result.json["mode"], "holmes")
            self.assertEqual(chats[-1]["response_format"]["type"], "json_schema")
            model_status = 503
            self.assertEqual(self.run_task().status_code, 502)
            model_status = 200
            analysis.clear()
            self.assertEqual(self.run_task().status_code, 502)
            enabled.append({"name": "bash", "enabled": True})
            self.assertEqual(self.run_task().status_code, 502)
            self.assertEqual(len(chats), 3)

    def test_upstream_timeout(self):
        delayed = Flask("delayed-upstream")

        @delayed.get("/")
        def slow():
            time.sleep(0.15)
            return {}

        with http_service(delayed) as url, self.assertRaises(GatewayTimeout):
            investigator.read_json(url, "test-only", timeout=0.03)


if __name__ == "__main__":
    unittest.main()

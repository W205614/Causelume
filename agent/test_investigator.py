import base64
import json
import os
from concurrent.futures import ThreadPoolExecutor
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
        return self.agent.post("/investigate", json={"service": "checkout", "taskId": "incident-16", "allowModel": True},
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

    def test_internal_calls_bypass_proxy_and_drop_untrusted_demo_event(self):
        proxy, demo, holmes = (Flask(name) for name in ("capture-proxy", "changed-demo", "model"))
        proxy_calls, model_inputs = [], []
        secret = "example-password-in-log"

        @proxy.before_request
        def capture():
            proxy_calls.append(request.path)
            return {}, 403

        @demo.get("/snapshot")
        def snapshot():
            self.assertEqual(request.headers["Authorization"], "Bearer test-reader")
            stamp = "2026-01-01T00:00:00Z"
            return {"service": "checkout", "snapshotId": "a" * 32, "sampledAt": stamp, "requests": 1,
                    "errors": 1, "latestError": {"id": "b" * 32, "at": stamp, "event": secret, "status": 503}}

        @holmes.get("/api/info")
        def info():
            self.assertEqual(request.headers["Authorization"], "Bearer test-holmes")
            return {"auth_enabled": True, "toolsets": [
                {"name": "causelume_observations", "enabled": True, "status": "enabled", "tool_count": 2}]}

        @holmes.post("/api/chat")
        def chat():
            view = base64.urlsafe_b64decode(request.headers["X-Causelume-Logs"] + "===").decode()
            model_inputs.extend((request.json["ask"], view))
            return {"analysis": json.dumps(dict.fromkeys(investigator.FIELDS, "test")), "tool_calls": []}

        proxy_url = self.enterContext(http_service(proxy))
        with patch.dict(os.environ, {"http_proxy": proxy_url, "HTTP_PROXY": proxy_url,
                                     "no_proxy": "unused.invalid", "NO_PROXY": "unused.invalid",
                                     "AGENT_MODE": "holmes", "HOLMES_API_KEY": "test-holmes",
                                     "HOLMES_URL": self.enterContext(http_service(holmes)),
                                     "DEMO_URL": self.enterContext(http_service(demo))}):
            self.agent = investigator.create_app().test_client()
            result = self.run_task()
        self.assertEqual(result.status_code, 200, result.text)
        self.assertEqual(proxy_calls, [])
        self.assertEqual(len(model_inputs), 2)
        for observed in [result.text, *model_inputs]:
            self.assertNotIn(secret, observed)
            self.assertIn("unclassified", observed)

    def test_model_http_contract_and_failures(self):
        holmes = Flask("holmes-contract-double")
        enabled = [{"name": "causelume_observations", "enabled": True, "status": "enabled", "tool_count": 2}]
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
            return {"analysis": json.dumps(analysis), "tool_calls": []}, model_status

        with http_service(holmes) as url, patch.dict(os.environ, {
                "HOLMES_URL": url, "AGENT_MODE": "holmes", "HOLMES_API_KEY": "test-holmes"}):
            self.agent = investigator.create_app().test_client()
            for allow in ({}, {"allowModel": False}):
                blocked = self.agent.post("/investigate", json={"taskId": "old-job", "service": "checkout", **allow},
                                          headers=auth("test-agent"))
                self.assertEqual(blocked.status_code, 422)
            self.assertFalse(chats)
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
            analysis.update({key: "x" * 2001 for key in investigator.FIELDS})
            self.assertEqual(self.run_task().status_code, 502)
            enabled.append({"name": "bash", "enabled": True})
            self.assertEqual(self.run_task().status_code, 502)
            self.assertEqual(len(chats), 4)

    def test_two_inflight_samples_keep_their_own_evidence_after_recovery(self):
        holmes = Flask("concurrent-model-double")
        sampled = threading.Event()
        recovered = threading.Event()
        fault_view = []
        substitute_other_view = False

        @holmes.get("/api/info")
        def info():
            return {"auth_enabled": True, "toolsets": [
                {"name": "causelume_observations", "enabled": True, "status": "enabled", "tool_count": 2}]}

        @holmes.post("/api/chat")
        def chat():
            metrics = base64.urlsafe_b64decode(request.headers["X-Causelume-Metrics"]+"===").decode()
            logs = base64.urlsafe_b64decode(request.headers["X-Causelume-Logs"]+"===").decode()
            if json.loads(metrics)["errors"]:
                fault_view.append(logs)
                sampled.set()
                assert recovered.wait(4)
            if substitute_other_view:
                logs = fault_view[0]
            calls = [{"tool_call_id": "call-42", "tool_name": "frozen_logs",
                      "result": {"status": "success", "data": logs}}]
            return {"analysis": json.dumps(dict.fromkeys(investigator.FIELDS, "contract")), "tool_calls": calls}

        with http_service(holmes) as url, patch.dict(os.environ, {
                "HOLMES_URL": url, "AGENT_MODE": "holmes", "HOLMES_API_KEY": "test-holmes"}):
            app = investigator.create_app()

            def inspect(task_id):
                return app.test_client().post("/investigate", headers=auth("test-agent"),
                                              json={"taskId": task_id, "service": "checkout", "allowModel": True})

            self.toggle(True)
            failed_request = self.demo.get("/checkout").json["id"]
            with ThreadPoolExecutor(max_workers=1) as workers:
                first = workers.submit(inspect, "before-recovery")
                try:
                    self.assertTrue(sampled.wait(4))
                    self.toggle(False)
                    self.demo.get("/checkout")
                    second = inspect("after-recovery")
                finally:
                    recovered.set()
                first = first.result()
            self.assertEqual((first.status_code, second.status_code), (200, 200))
            self.assertNotEqual(first.json["snapshotId"], second.json["snapshotId"])
            self.assertIn(failed_request, first.json["evidence"][-1]["detail"])
            for result in (first.json, second.json):
                tool = result["evidence"][-1]
                view = json.loads(tool["detail"].split(" ", 1)[1])
                self.assertEqual(tool["source"], "holmes:frozen_logs")
                self.assertEqual((view["snapshotId"], view["sampledAt"]), (result["snapshotId"], result["sampledAt"]))
            self.assertNotIn(failed_request, str(second.json["evidence"]))
            substitute_other_view = True
            self.assertEqual(inspect("cross-task-leak").status_code, 502)

    def test_oversized_observation_body(self):
        oversized = Flask("oversized-observation")

        @oversized.get("/snapshot")
        def snapshot():
            return {"service": "checkout", "extra": "x" * 4096}

        with http_service(oversized) as url, patch.dict(os.environ, {"DEMO_URL": url}):
            self.agent = investigator.create_app().test_client()
            response = self.run_task()
        self.assertEqual(response.status_code, 502)
        self.assertIn("大小", response.json["error"])

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

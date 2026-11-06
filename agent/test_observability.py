import json
import os
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from flask import Flask, redirect, request

from investigator import create_app, usage
from observability import load_sources
from test_investigator import auth, http_service


class ObservabilityTests(unittest.TestCase):
    def setUp(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.registry = root / "sources.json"
        self.token_file = root / "reader"
        self.token_file.write_text("fixture-bearer")
        self.enterContext(patch.dict(os.environ, {"SOURCE_REGISTRY_FILE": str(self.registry),
                          "SOURCE_SECRET_DIR": str(root), "AGENT_TOKEN": "fixture-agent", "AGENT_MODE": "offline",
                          "DEMO_URL": ""}))
        self.source = Flask("observability-source")
        self.args = {}
        self.metric_offset = 0
        self.stream = {"service": "web", "environment": "dev", "unexpected": "MUST_NOT_APPEAR"}

        @self.source.get("/api/v1/query")
        def metrics():
            self.args["metric"] = request.args.copy()
            # Credentials are injected after selecting the URL from a trusted file.
            if request.headers.get("Authorization") != "Bearer fixture-bearer":
                return {}, 401
            evaluated = datetime.fromisoformat(request.args["time"]).timestamp() if "time" in request.args else 1
            return {"status": "success", "data": {"resultType": "vector", "result": [
                {"metric": {"signal": "min_up"}, "value": [evaluated + self.metric_offset, "0"]}]}}

        @self.source.get("/loki/api/v1/query_range")
        def logs():
            self.args["logs"] = request.args.copy()
            stamp = int(request.args.get("end", "3")) - 1
            line = '{"event":"dependency_timeout","status":502,"password":"MUST_NOT_APPEAR","message":"arbitrary prompt"}'
            return {"status": "success", "data": {"resultType": "streams", "result": [
                {"stream": self.stream, "values": [[str(stamp - i), line] for i in range(4)]}]}}

        self.url = self.enterContext(http_service(self.source))
        self.profile = {"id": "testing", "name": "只读源", "prometheus": {"url": self.url,
                        "secretFile": str(self.token_file)}, "loki": {"url": self.url}}
        self.body = {"taskId": "abc", "sourceId": "testing", "service": "web", "environment": "dev",
                     "windowStart": "2026-11-01T04:00:00Z", "windowEnd": "2026-11-01T04:05:00Z"}
        self.client = self.app()

    def app(self):
        self.registry.write_text(json.dumps([self.profile]))
        return create_app().test_client()

    def test_window_truncation_redaction_and_credentials(self):
        self.assertEqual(self.client.get("/sources").status_code, 401)
        items = self.client.get("/sources", headers=auth("fixture-agent")).json["sources"]
        self.assertEqual(items, [{"id": "testing", "name": "只读源", "type": "prometheus-loki"}])
        response = self.client.post("/investigate", json=self.body, headers=auth("fixture-agent"))
        self.assertEqual(response.status_code, 200)
        value = response.json
        self.assertEqual(value["completeness"], "partial")
        self.assertNotIn("MUST_NOT_APPEAR", response.text)
        self.assertNotIn("arbitrary prompt", response.text)
        self.assertNotIn("fixture-bearer", response.text)
        m, l = value["observations"]["metrics"], value["observations"]["logs"]
        self.assertIn("errors", m["missingSignals"])
        self.assertTrue(l["truncated"])
        self.assertEqual(len(l["samples"]), 3)
        self.assertEqual((m["windowStart"], m["windowEnd"]), (l["windowStart"], l["windowEnd"]))
        self.assertEqual(int(self.args["logs"]["end"]) - int(self.args["logs"]["start"]), 300_000_000_000)
        self.assertIn("[300000ms]", self.args["metric"]["query"])
        self.assertEqual(value["usage"]["costUsd"], 0)

    def test_untrusted_input_cannot_supply_endpoints_or_query_language(self):
        for changes, status in [({"url": self.url}, 400), ({"service": 'web"} or vector(1)'}, 400),
                                ({"sourceId": "http://169.254.169.254"}, 422),
                                ({"windowStart": "2025-01-01T00:00:00Z"}, 400)]:
            with self.subTest(changes=changes):
                result = self.client.post("/investigate", json={**self.body, **changes}, headers=auth("fixture-agent"))
                self.assertEqual(result.status_code, status)
        self.assertFalse(self.args)

    def test_future_tail_uses_one_effective_cutoff_for_both_real_requests(self):
        sampled_at = "2026-11-01T04:05:00.000+00:00"
        request_body = {**self.body, "windowEnd": "2026-11-01T04:10:00Z"}
        with patch("observability.timestamp", return_value=sampled_at):
            result = self.client.post("/investigate", json=request_body, headers=auth("fixture-agent")).json
        self.assertEqual(result["completeness"], "partial")
        self.assertIn("未来", result["limitation"])
        self.assertEqual(result["requestedRange"]["end"], "2026-11-01T04:10:00.000+00:00")
        self.assertEqual(result["sampledAt"], result["effectiveRange"]["end"])
        self.assertEqual(self.args["metric"]["time"], sampled_at)
        self.assertIn("[300000ms]", self.args["metric"]["query"])
        self.assertEqual(int(self.args["logs"]["end"]), 1793505900000000001)
        for view in result["observations"].values():
            self.assertTrue(view["windowPending"])
            self.assertEqual((view["completeness"], view["windowEnd"]), ("partial", sampled_at))

    def test_future_only_window_does_not_query_sources(self):
        with patch("observability.timestamp", return_value="2026-11-01T03:59:59.999+00:00"):
            result = self.client.post("/investigate", json=self.body, headers=auth("fixture-agent")).json
        self.assertFalse(self.args)
        self.assertEqual(result["completeness"], "partial")
        self.assertIsNone(result["effectiveRange"])
        self.assertTrue(all(v["status"] == "pending" and v["query"] is None for v in result["observations"].values()))

    def test_wrong_service_environment_or_evaluation_time_rejects_only_that_source(self):
        for key in ("service", "environment", "time"):
            with self.subTest(key=key):
                if key == "time":
                    self.metric_offset = -60
                else:
                    self.stream[key] = "other-team"
                result = self.client.post("/investigate", json=self.body, headers=auth("fixture-agent")).json
                failed, good = ("metrics", "logs") if key == "time" else ("logs", "metrics")
                view = result["observations"][failed]
                self.assertEqual((result["completeness"], view["status"], view["samples"]), ("partial", "error", []))
                self.assertIn("时间" if key == "time" else "标签", view["error"])
                self.assertTrue(result["observations"][good]["samples"])
                self.stream.update(service="web", environment="dev")

    def test_one_failing_source_preserves_other_source(self):
        refused = Flask("redirect-source")

        @refused.get("/loki/api/v1/query_range")
        def attempt_redirect():
            return redirect(self.url + "/loki/api/v1/query_range")

        self.profile["loki"]["url"] = self.enterContext(http_service(refused))
        client = self.app()
        result = client.post("/investigate", json=self.body, headers=auth("fixture-agent")).json
        self.assertEqual(result["completeness"], "partial")
        self.assertEqual(result["observations"]["logs"]["status"], "error")
        self.assertEqual(result["observations"]["metrics"]["samples"][0]["value"], 0)
        self.assertNotIn("logs", self.args)

    def test_server_registration_and_unknown_usage(self):
        self.profile["prometheus"]["secretFile"] = str(self.registry.parent.parent / "out-of-scope")
        self.registry.write_text(json.dumps([self.profile]))
        with self.assertRaises(ValueError):
            load_sources()
        self.assertEqual(usage("holmes")["status"], "unknown")
        reported = usage("holmes", {"costs": {"prompt_tokens": 230, "completion_tokens": 41, "total_cost": 0.001}})
        self.assertEqual((reported["inputTokens"], reported["outputTokens"]), (230, 41))
        self.assertEqual(reported["estimatedCostUsd"], 0.001)
        self.assertIsNone(reported["costUsd"])

    def test_model_skipped_when_both_sources_have_no_samples(self):
        empty = Flask("empty-observations")

        @empty.get("/api/v1/query")
        def metrics():
            return {"status": "success", "data": {"resultType": "vector", "result": []}}

        @empty.get("/loki/api/v1/query_range")
        def logs():
            return {"status": "success", "data": {"resultType": "streams", "result": []}}

        url = self.enterContext(http_service(empty))
        self.profile.update(prometheus={"url": url}, loki={"url": url})
        with patch.dict(os.environ, {"AGENT_MODE": "holmes", "HOLMES_URL": "http://127.0.0.1:1",
                                     "HOLMES_API_KEY": "test-only"}):
            client = self.app()
            response = client.post("/investigate", json={**self.body, "allowModel": True}, headers=auth("fixture-agent"))
        self.assertEqual(response.status_code, 200)
        self.assertEqual((response.json["modelExecution"], response.json["usage"]["costUsd"]),
                         ("skipped_no_evidence", 0))
        self.assertEqual(response.json["completeness"], "empty")

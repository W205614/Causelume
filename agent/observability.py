"""Read-only queries against operator-registered Prometheus and Loki profiles."""
import ipaddress
import json
import math
import os
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import uuid4

from flask import abort


DEMO = {"id": "checkout-demo", "name": "Checkout 演示窗口", "type": "demo"}
LABEL = re.compile(r"[A-Za-z0-9_.-]{1,80}", re.ASCII)
PROM, LOKI = "prometheus", "loki"
LOG_LIMIT = 3


class SourceError(Exception):
    """A publishable failure reason, without URLs, credentials or body contents."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_sources():
    registry = {DEMO["id"]: DEMO} if os.environ.get("DEMO_URL") else {}
    location = os.environ.get("SOURCE_REGISTRY_FILE")
    if not location:
        return registry
    profiles = json.loads(Path(location).read_text(encoding="utf-8"))
    if not isinstance(profiles, list) or len(profiles) > 64:
        raise ValueError("Expected a list of up to 64 registered source profiles")
    secret_dir = Path(os.environ.get("SOURCE_SECRET_DIR", "/run/secrets")).resolve()
    for p in profiles:
        if (set(p) != {"id", "name", PROM, LOKI} or not LABEL.fullmatch(p["id"])
                or p["id"] in registry or p["id"] == DEMO["id"]
                or not isinstance(p["name"], str) or not 1 <= len(p["name"]) <= 100):
            raise ValueError("Invalid or duplicate source profile")
        for kind in (PROM, LOKI):
            endpoint = p[kind]
            if not isinstance(endpoint, dict) or not {"url"} <= endpoint.keys() <= {"url", "secretFile"}:
                raise ValueError("Invalid source endpoint configuration")
            url = urlsplit(endpoint["url"])
            if (url.scheme not in {"http", "https"} or not url.hostname or url.username is not None
                    or url.password is not None or url.query or url.fragment or url.path not in ("", "/")
                    or url.hostname.lower() == "metadata.google.internal"):
                raise ValueError("Expected a registered HTTP origin without credentials, path or query")
            try:
                address = ipaddress.ip_address(url.hostname)
            except ValueError:
                address = None
            if address and (address.is_link_local or address.is_unspecified or address.is_multicast):
                raise ValueError("Link-local, multicast and unspecified source addresses are disallowed")
            endpoint["url"] = endpoint["url"].rstrip("/")
            if "secretFile" in endpoint:
                path = Path(endpoint["secretFile"]).resolve()
                if not path.is_relative_to(secret_dir):
                    raise ValueError("A source credential must be under SOURCE_SECRET_DIR")
                endpoint["secretFile"] = path
        registry[p["id"]] = {**p, "type": "prometheus-loki"}
    return registry


def get_json(endpoint, path, params):
    headers = {"Accept": "application/json"}
    if "secretFile" in endpoint:
        try:
            value = endpoint["secretFile"].read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError):
            raise SourceError("只读凭据不可用") from None
        if not re.fullmatch(r"[\x21-\x7e]{1,4096}", value):
            raise SourceError("只读凭据格式无效")
        headers["Authorization"] = "Bearer " + value
    req = Request(endpoint["url"] + path + "?" + urlencode(params), headers=headers)
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(req, timeout=4) as response:
            data = response.read(131073)
        if len(data) > 131072:
            raise SourceError("源响应超过 128 KiB")
        result = json.loads(data)
        if not isinstance(result, dict) or result.get("status") != "success":
            raise SourceError("数据源拒绝查询")
        return result
    except HTTPError as exc:
        raise SourceError(f"查询失败（HTTP {exc.code}）") from None
    except (URLError, OSError, HTTPException):
        raise SourceError("数据源不可达、超时或响应中断") from None
    except (ValueError, RecursionError):
        raise SourceError("数据源 JSON 无效") from None


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def target(service, environment):
    if not all(isinstance(v, str) and LABEL.fullmatch(v) for v in (service, environment)):
        abort(400, "service 和 environment 必须为 1–80 位 ASCII 标签")
    return f'service="{service}",environment="{environment}"'


def check_profile(profile, labels):
    def check(kind):
        if kind == PROM:
            path, params = "/api/v1/query", {"query": f"up{{{labels}}}", "limit": 1, "timeout": "2s"}
        else:
            path, params = "/loki/api/v1/query_range", {"query": "{" + labels + "}", "limit": 1, "since": "1m"}
        try:
            result = get_json(profile[kind], path, params)
            expected = "vector" if kind == PROM else "streams"
            if not isinstance(result.get("data"), dict) or result["data"].get("resultType") != expected:
                raise SourceError("查询返回结构无效")
            return {"source": kind, "status": "ok", "detail": "只读查询成功"}
        except SourceError as exc:
            return {"source": kind, "status": "error", "detail": str(exc)}

    with ThreadPoolExecutor(max_workers=2) as executor:
        return list(executor.map(check, (PROM, LOKI)))


def seconds(value):
    if not isinstance(value, str) or len(value) > 40:
        abort(400, "缺少带时区的采样时间")
    try:
        dt = datetime.fromisoformat(value)
    except ValueError:
        abort(400, "采样时间格式错误")
    if dt.tzinfo is None:
        abort(400, "采样时间必须包含时区")
    return dt.astimezone(timezone.utc)


def metric_query(labels, duration):
    # PromQL range selectors have an open left bound. Loki receives the same (start,end] window.
    def counter(metric, extra=""):
        return f'sum(increase({metric}{{{labels},uri!~"/actuator.*"{extra}}}[{duration}ms]))'
    count = counter("http_server_requests_seconds_count")
    elapsed = counter("http_server_requests_seconds_sum")
    signals = {
        "min_up": f"min(min_over_time(up{{{labels}}}[{duration}ms]))",
        "requests": count,
        "errors": counter("http_server_requests_seconds_count", ',status=~"5.."'),
        "mean_seconds": f"{elapsed} / ({count} > 0)",
    }
    return " or ".join(f'label_replace(({expr}), "signal", "{key}", "", "")' for key, expr in signals.items())


# Only known non-secret fields from structured application logs are retained. Free-form
# lines are classified locally: arbitrary log text can contain credentials in any format.
EVENTS = {
    "dependency_timeout", "connection_refused", "request_failed", "slow_request",
    "pool_exhausted", "checkout_ok", "upstream_unavailable", "health_ok",
}


def safe_log(line):
    try:
        obj = json.loads(line)
    except (ValueError, RecursionError):
        obj = None
    if isinstance(obj, dict):
        event = obj.get("event")
        safe = {"event": event if isinstance(event, str) and event in EVENTS else "unclassified"}
        level = obj.get("level")
        if isinstance(level, str) and level.upper() in {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"}:
            safe["level"] = level.upper()
        for key, maximum in (("status", 599), ("duration_ms", 86400000)):
            if type(obj.get(key)) is int and 0 <= obj[key] <= maximum:
                safe[key] = obj[key]
        return safe
    return {"event": "unclassified", "format": "unstructured"}


def read_view(kind, profile, labels, start, end):
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    start_ms, end_ms = ((instant - epoch) // timedelta(milliseconds=1) for instant in (start, end))
    query = metric_query(labels, end_ms - start_ms) if kind == PROM else "{" + labels + "}"
    view = {"source": kind, "query": query, "windowStart": start.isoformat(timespec="milliseconds"),
            "windowEnd": end.isoformat(timespec="milliseconds"), "samples": [], "truncated": False}
    try:
        if kind == PROM:
            reply = get_json(profile[kind], "/api/v1/query", {"query": query, "time": view["windowEnd"], "timeout": "3s"})
            data = reply["data"]
            if data["resultType"] != "vector" or not isinstance(data["result"], list) or len(data["result"]) > 4:
                raise SourceError("指标结果类型或数量异常")
            for row in data["result"]:
                name = row["metric"]["signal"]
                if name not in {"min_up", "requests", "errors", "mean_seconds"}:
                    raise SourceError("指标信号无效")
                number = float(row["value"][1])
                if not math.isfinite(number) or number < 0:
                    raise SourceError("指标样本数值无效")
                view["samples"].append({"signal": name, "value": number})
            view["missingSignals"] = sorted({"min_up", "requests", "errors", "mean_seconds"} -
                                            {row["signal"] for row in view["samples"]})
        else:
            reply = get_json(profile[kind], "/loki/api/v1/query_range", {
                "query": query, "start": start_ms * 1000000 + 1, "end": end_ms * 1000000 + 1,
                "direction": "backward", "limit": LOG_LIMIT + 1})
            data = reply["data"]
            if data["resultType"] != "streams" or not isinstance(data["result"], list):
                raise SourceError("日志结果类型无效")
            entries = []
            for stream in data["result"]:
                for stamp, line in stream["values"]:
                    ns = int(stamp)
                    if not (start_ms * 1000000 < ns <= end_ms * 1000000) or not isinstance(line, str):
                        raise SourceError("日志时间或内容无效")
                    entries.append((ns, line))
            view["truncated"] = len(entries) > LOG_LIMIT
            for ns, line in sorted(entries, key=lambda r: r[0], reverse=True)[:LOG_LIMIT]:
                view["samples"].append({"timestampNs": str(ns), **safe_log(line)})
        partial = view["truncated"] or bool(reply.get("warnings"))
        partial = partial or (bool(view["samples"]) and bool(view.get("missingSignals")))
        view.update(status="ok" if view["samples"] else "empty", completeness="partial" if partial else "complete")
    except (SourceError, KeyError, IndexError, TypeError, ValueError) as exc:
        view.update(status="error", completeness="partial", samples=[],
                    error=str(exc) if isinstance(exc, SourceError) else "采样数据结构无效")
    view["collectedAt"] = timestamp()
    return view


def observe(profile, body):
    labels = target(body.get("service"), body.get("environment"))
    start, end = seconds(body.get("windowStart")), seconds(body.get("windowEnd"))
    # Both APIs share millisecond resolution, including the evidence and recorded query.
    start = start.replace(microsecond=start.microsecond // 1000 * 1000)
    end = end.replace(microsecond=end.microsecond // 1000 * 1000)
    if not timedelta(0) < end - start <= timedelta(hours=24):
        abort(400, "采样窗口须大于零且不超过 24 小时")
    sampled_at = timestamp()
    effective_end = min(end, datetime.fromisoformat(sampled_at))
    pending = effective_end < end
    requested = {"start": start.isoformat(timespec="milliseconds"), "end": end.isoformat(timespec="milliseconds")}
    effective = None
    if start < effective_end:
        effective = {"start": requested["start"], "end": effective_end.isoformat(timespec="milliseconds")}
        with ThreadPoolExecutor(max_workers=2) as executor:
            jobs = [executor.submit(read_view, kind, profile, labels, start, effective_end) for kind in (PROM, LOKI)]
            metrics, logs = [job.result() for job in jobs]
        views = {"metrics": metrics, "logs": logs}
    else:
        # No elapsed interval exists. In particular, never send a zero-length range to Prometheus.
        views = {key: {"source": kind, "query": None, "windowStart": None, "windowEnd": None, "collectedAt": None,
                       "status": "pending", "samples": [], "truncated": False, "completeness": "partial"}
                 for key, kind in (("metrics", PROM), ("logs", LOKI))}
    for view in views.values():
        view["windowPending"] = pending
        if pending:
            view["completeness"] = "partial"
    if all(v["status"] == "empty" and v["completeness"] == "complete" for v in views.values()):
        state = "empty"
    else:
        state = "complete" if all(v["status"] == "ok" and v["completeness"] == "complete"
                                  for v in views.values()) else "partial"
    return {"snapshotId": uuid4().hex, "sampledAt": sampled_at, "service": body["service"],
            "environment": body["environment"], "sourceId": profile["id"], "requestedRange": requested,
            "effectiveRange": effective, "windowStart": views["metrics"]["windowStart"],
            "windowEnd": views["metrics"]["windowEnd"], "completeness": state, "observations": views,
            "limitation": "申请窗口含未来时段，只能分析采样时已发生的部分。" if pending else None,
            "evidence": [{"source": v["source"], "detail": json.dumps(
                {k: v[k] for k in ("status", "truncated", "samples", "error") if k in v},
                ensure_ascii=False, separators=(",", ":"))} for v in views.values()]}


def interpret(snapshot):
    views = snapshot["observations"]
    values = {row["signal"]: row["value"] for row in views["metrics"]["samples"]}
    events = {entry["event"] for entry in views["logs"]["samples"]}
    clues = []
    if values.get("min_up") == 0:
        clues.append("窗口内出现 Prometheus 抓取失败")
    if values.get("errors", 0) > 0:
        clues.append("HTTP 5xx 计数增加")
    if values.get("mean_seconds", 0) > 1:
        clues.append("窗口平均请求耗时超过 1 秒")
    if "dependency_timeout" in events or "connection_refused" in events:
        clues.append("日志记录了依赖超时或连接被拒绝")
    return ("；".join(clues) or "当前证据不足以定位故障。",
            "仅覆盖选定窗口、聚合指标与有限的脱敏日志，无法据此确认根因或恢复。",
            "人工核对目标可达性、依赖状态与对应时段请求，处理后重新采样。")

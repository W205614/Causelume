"""Verify two teams against a disposable application and shared observation stack."""
from datetime import datetime, timedelta, timezone
import http.cookiejar
import json
import os
import subprocess
import time
from urllib.request import Request, urlopen
from uuid import uuid4

from e2e import Client


def exercise(origin, admin, keys, enrolled, wait_for, browser_script):
    app = admin.base
    manager = Client(origin("alertmanager", 9093))
    teams = {}
    for key in ("alpha", "beta"):
        team = admin.call("/api/teams", {"slug": key, "name": key.title()})
        password = keys["APP_OPERATOR_PASSWORD"]
        account = admin.call("/api/users", {"username": key, "password": password, "role": "OPERATOR",
                             "teamId": team["id"], "email": key + "@example.invalid"})
        admin.call(f"/api/teams/{team['id']}/members", {"username": key, "role": "MANAGER"})
        actor = enrolled(app, key, password)
        payload = {"name": "Same service", "description": "Twin service used for isolation", "resource": "example",
                   "teamId": team["id"]}
        service = actor.call("/api/services", payload)
        settings = {k: payload[k] for k in ("name", "description", "resource")}
        settings.update({"ownerId": account["id"], "environment": "prod", "active": True,
                         "sourceId": "prom-loki", "serviceLabel": "team-twin", "version": 0})
        admin.call(f"/api/services/{service['id']}", settings)
        teams[key] = (team, actor, service, settings)

    def now():
        return datetime.now(timezone.utc)

    launch = now()
    start = launch - timedelta(seconds=15)
    events = []
    log_streams = []
    for key, status in (("alpha", 502), ("beta", 503)):
        labels = {"service": "team-twin", "environment": "prod", "team": key}
        events.append({"labels": {**labels, "alertname": "TeamContract"}, "startsAt": start.isoformat(),
                       "endsAt": (launch + timedelta(minutes=10)).isoformat(),
                       "annotations": {"summary": f"Team contract {key}"}})
        line = json.dumps({"event": "request_failed", "status": status})
        log_streams.append({"stream": labels, "values": [[str(time.time_ns()), line]]})
    with urlopen(Request(origin("loki", 3100) + "/loki/api/v1/push",
                         data=json.dumps({"streams": log_streams}).encode(),
                         headers={"Content-Type": "application/json"}), timeout=8) as response:
        assert response.status == 204
    manager.call("/api/v2/alerts", events)
    tickets = {}
    for key, (team, actor, service, settings) in teams.items():
        sid = service["id"]
        wait_for("observations available for " + key, lambda: actor.call(f"/api/services/{sid}/source-check"),
                 lambda checks: checks["available"] and all(c["sampleStatus"] == "present" for c in checks["checks"]))
        work = wait_for("routed alert in " + key, lambda: actor.call(f"/api/tickets/page?teamId={team['id']}"),
                        lambda page: page["total"] == 1)["items"][0]
        assert work["status"] == "ASSIGNED" and work["assigneeId"] == settings["ownerId"], work
        assert [s["id"] for s in actor.call("/api/services")] == [sid]
        summary = actor.call("/api/overview")
        assert summary["openTickets"] == 1 and summary["services"] == 1, summary
        ticket = work["id"]
        actor.call(f"/api/tickets/{ticket}/actions", {"version": work["version"], "action": "START", "note": "Observe current fault"})
        task = actor.call("/api/tasks", {"ticketId": ticket, "requestId": str(uuid4()),
                          "windowStart": start.isoformat(), "windowEnd": now().isoformat()})
        task = wait_for("finished team investigation " + key, lambda: actor.call(f"/api/tasks/{task['id']}"),
                        lambda item: item["status"] in ("SUCCEEDED", "FAILED"))
        assert task["status"] == "SUCCEEDED", task["error"]
        evidence = task["result"]["observations"]
        assert [record["status"] for record in evidence["logs"]["samples"]] == [502 if key == "alpha" else 503], evidence
        assert [record["value"] for record in evidence["metrics"]["samples"] if record["signal"] == "min_up"] == [1 if key == "alpha" else 0], evidence
        tickets[key] = (ticket, task["id"])
    print("PASS real Alertmanager, Prometheus and Loki isolate identically named services", flush=True)

    a, b = teams["alpha"][1], teams["beta"][1]
    ticket, task = tickets["alpha"]
    other_ticket, other_task = tickets["beta"]
    path = f"/api/tickets/{ticket}"
    b.call(path, expected=(404,))
    b.call(f"/api/tasks/{task}", expected=(404,))
    a.call(f"/api/tickets/{other_ticket}", expected=(404,))
    a.call(f"/api/tasks/{other_task}", expected=(404,))
    assert admin.call(path)["id"] == ticket and admin.call(f"/api/tickets/{other_ticket}")["id"] == other_ticket
    fake = {"status": "firing", "fingerprint": "cross-team-test", "startsAt": now().isoformat(), "labels": events[0]["labels"]}
    webhook = "/api/integrations/alertmanager/beta"
    admin.call(webhook, {"alerts": [fake]}, token=keys["ALERTMANAGER_ALPHA_TOKEN"], expected=(401,))
    admin.call(webhook, {"alerts": [fake]}, token=keys["ALERTMANAGER_BETA_TOKEN"], expected=(403,))
    fake["labels"] = {**fake["labels"], "team": "beta", "service": "unknown"}
    admin.call(webhook, {"alerts": [fake]}, token=keys["ALERTMANAGER_BETA_TOKEN"], expected=(404,))
    extra = admin.call("/api/services", {"name": "Ambiguous binding", "description": "Test duplicate labels",
                       "resource": "example", "teamId": teams["beta"][0]["id"]})
    admin.call(f"/api/services/{extra['id']}", {**teams["beta"][3], "name": extra["name"], "ownerId": None})
    fake["labels"]["service"] = "team-twin"
    admin.call(webhook, {"alerts": [fake]}, token=keys["ALERTMANAGER_BETA_TOKEN"], expected=(409,))
    Client(origin("agent", 8091)).call("/sources/prom-loki/check?service=team-twin&environment=prod",
                                      token=keys["AGENT_TOKEN"], expected=(422,))

    manager.call("/api/v2/alerts", [{**event, "endsAt": now().isoformat()} for event in events])
    for key, (_, actor, _, _) in teams.items():
        route = f"/api/tickets/{tickets[key][0]}"
        wait_for("recovered original alert " + key, lambda: actor.call(route),
                 lambda row: any(e["action"] == "ALERT_RESOLVED" for e in row["timeline"]))
        assert actor.call("/api/tickets/page")["total"] == 1
    sid = teams["alpha"][2]["id"]
    a.call(f"/api/services/{sid}/maintenance",
           {"until": (now() + timedelta(minutes=10)).isoformat(), "reason": "Keep collaboration email queued for revocation"})
    other = a.call("/api/tickets", {"serviceId": sid, "requestId": str(uuid4()), "title": "Unshared ticket"})
    invite = a.call(path + "/invites", {"username": "beta", "taskIds": [task]})["id"]
    assert b.call(path)["collaboration"] and b.call(f"/api/tasks/{task}")["result"]
    assert any(n["inviteId"] == invite and n["mailStatus"] == "PENDING" for n in b.call("/api/notifications")["items"])
    b.call(path + "/export", {}, expected=(403,))
    b.call(f"/api/tickets/{other['id']}", expected=(404,))
    b.call("/api/tasks", {"ticketId": ticket, "requestId": str(uuid4())}, expected=(403,))
    if browser_script:
        browser(browser_script, b, ticket, "granted")
    else:
        b.call(f"/api/collaborations/{ticket}/notes", {"note": "Team coordination only"})
    assert any(row["action"] == "COLLAB_NOTE" for row in a.call(path)["timeline"])
    a.call(path + f"/invites/{invite}/revoke", {})
    assert not any(n["inviteId"] == invite for n in b.call("/api/notifications")["items"])
    b.call(path, expected=(404,))
    b.call(f"/api/tasks/{task}", expected=(404,))
    if browser_script:
        browser(browser_script, b, ticket, "revoked")
    print("PASS real-account collaboration, shared evidence, revocation and queued notification permission", flush=True)
    alpha, _, _, alpha_settings = teams["alpha"]
    successor = admin.call("/api/users", {"username": "joiner", "password": keys["APP_OPERATOR_PASSWORD"],
                           "role": "OPERATOR", "teamId": alpha["id"]})["id"]
    item = a.call(path + "/improvements", {"title": "Follow up", "ownerId": alpha_settings["ownerId"],
                  "dueDate": now().date().isoformat()})
    admin.call(f"/api/teams/{alpha['id']}/members/{alpha_settings['ownerId']}/remove", {"successorId": successor})
    a.call(path, expected=(404,))
    reloaded = admin.call(path)
    assert reloaded["assigneeId"] == successor and reloaded["improvements"][0]["ownerId"] == successor
    admin.call(f"/api/users/{successor}/status", {"active": False})
    reloaded = admin.call(path)
    assert reloaded["assigneeId"] is None and reloaded["improvements"][0]["ownerId"] is None
    assert any(row["id"] == ticket for row in admin.call(f"/api/tickets/page?scope=unassigned&teamId={alpha['id']}")["items"])
    admin.call(f"/api/teams/{alpha['id']}/members", {"username": "operator", "role": "MEMBER"})
    assigned = admin.call(path + f"/improvements/{item['id']}/assign", {"ownerId": 2})
    assert assigned["ownerId"] == 2
    print("PASS member handover, account disable, unassigned queue and improvement reassignment", flush=True)
    return invite


def browser(script, actor, ticket, state):
    handler = next(h for h in actor.opener.handlers if hasattr(h, "cookiejar"))
    session = next(c for c in handler.cookiejar if c.name == "JSESSIONID")
    assert isinstance(session, http.cookiejar.Cookie)
    subprocess.run(["node", str(script)], check=True, timeout=50, env={**os.environ,
                   "CAUSELUME_URL": actor.base, "CAUSELUME_TEST_SESSION": session.value,
                   "CAUSELUME_TEST_TICKET": str(ticket), "CAUSELUME_TEST_ACCESS": state})

from collections import deque
from datetime import datetime, timezone
from threading import BoundedSemaphore, Lock
from uuid import uuid4

from flask import abort, request

from common import application, authenticate, required, run


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def create_app():
    app = application(__name__)
    reader, controller = required("EVIDENCE_TOKEN"), required("DEMO_CONTROL_TOKEN")
    if reader == controller:
        raise ValueError("The control and evidence credentials must differ")
    connection = BoundedSemaphore(1)
    lock = Lock()
    fault = False
    entries = deque(maxlen=50)

    @app.post("/control")
    def control():
        nonlocal fault
        authenticate(controller)
        body = request.get_json()
        if not isinstance(body, dict) or type(body.get("fault")) is not bool:
            abort(400, "fault 必须为布尔值")
        with lock:
            if fault != body["fault"]:
                if body["fault"]:
                    connection.acquire()
                else:
                    connection.release()
                fault = body["fault"]
                entries.clear()
            return {"fault": fault}

    @app.get("/checkout")
    def checkout():
        with lock:
            available = connection.acquire(blocking=False)
            if available:
                connection.release()
            record = {"id": uuid4().hex, "at": timestamp(),
                      "status": 200 if available else 503,
                      "event": "checkout_ok" if available else "pool_exhausted"}
            entries.append(record)
            return record, record["status"]

    @app.get("/metrics")
    def metrics():
        authenticate(reader)
        with lock:
            return {"service": "checkout", "observedAt": timestamp(),
                    "requests": len(entries), "errors": sum(row["status"] >= 500 for row in entries)}

    @app.get("/logs")
    def logs():
        authenticate(reader)
        with lock:
            return {"service": "checkout", "entries": list(entries)}

    return app


if __name__ == "__main__":
    run(create_app(), 8092)

import hmac
import os

from flask import Flask, abort, request
from waitress import serve
from werkzeug.exceptions import HTTPException


def required(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} must be configured")
    return value


def application(name):
    app = Flask(name, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = 4096
    app.json.ensure_ascii = False

    @app.errorhandler(HTTPException)
    def error(exc):
        return {"error": exc.description}, exc.code

    @app.get("/health")
    def health():
        return {"status": "up"}

    return app


def authenticate(token):
    actual = request.headers.get("Authorization", "").encode()
    if not hmac.compare_digest(actual, f"Bearer {token}".encode()):
        abort(401, "内部凭据无效")


def run(app, default_port):
    serve(app, host=os.environ.get("BIND_HOST", "127.0.0.1"),
          port=int(os.environ.get("PORT", default_port)), max_request_body_size=4096)

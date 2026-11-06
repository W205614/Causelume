"""Entrypoint for the two parameterless Holmes tools."""
import os
import sys
from urllib.request import HTTPRedirectHandler, Request, build_opener


class FixedTarget(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


if __name__ == "__main__":
    endpoint = {"metrics": "metrics", "logs": "logs"}[sys.argv[1]]
    query = Request(f"http://checkout:8092/{endpoint}", headers={
        "Authorization": "Bearer " + os.environ["EVIDENCE_TOKEN"]})
    with build_opener(FixedTarget()).open(query, timeout=3) as response:
        sys.stdout.buffer.write(response.read())

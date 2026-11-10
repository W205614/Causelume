"""Read image content identity from docker-save archives, independent of Engine IDs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile


def archive_identity(archive, image):
    with tarfile.open(archive) as source:
        entries = json.load(source.extractfile("manifest.json"))
        matches = [entry for entry in entries if image in (entry.get("RepoTags") or [])]
        if len(matches) != 1:
            raise ValueError("Expected exactly one saved image tagged " + image)
        raw = source.extractfile(matches[0]["Config"]).read()
    config = json.loads(raw)
    diff_ids = config["rootfs"]["diff_ids"]
    if (config["rootfs"]["type"] != "layers" or not isinstance(diff_ids, list)
            or not all(isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in diff_ids)
            or not isinstance(config["os"], str) or not config["os"]
            or not isinstance(config["architecture"], str) or not config["architecture"]):
        raise ValueError("Invalid image platform or uncompressed layer identity")
    return {"configDigest": "sha256:" + hashlib.sha256(raw).hexdigest(), "diffIds": diff_ids,
            "os": config["os"], "architecture": config["architecture"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("image", help="exact RepoTag stored by docker save")
    parser.add_argument("--expected-manifest", type=Path)
    parser.add_argument("--component", choices=("app", "agent"))
    args = parser.parse_args()
    if bool(args.expected_manifest) != bool(args.component):
        parser.error("--expected-manifest and --component must be supplied together")
    identity = archive_identity(args.archive, args.image)
    if args.expected_manifest:
        expected = json.loads(args.expected_manifest.read_text(encoding="utf-8"))["imageContent"][args.component]
        if identity != expected:
            raise SystemExit("Refusing image content different from the validated candidate: " + args.component)
    print(json.dumps(identity, sort_keys=True))


if __name__ == "__main__":
    main()

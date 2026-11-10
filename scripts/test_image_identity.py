"""Check docker-save content selection and immutable-candidate comparison."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

from image_identity import archive_identity


IMAGE = "ghcr.io/w205614/causelume-app:fixture"
SCRIPT = Path(__file__).with_name("image_identity.py")


class ImageIdentityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def archive(self, name, *, image=IMAGE, created="2026-11-10T00:00:00Z", reverse=False, compression_time=0, extra=False):
        layers = [b"first uncompressed layer", b"second uncompressed layer"]
        diff_ids = ["sha256:" + hashlib.sha256(layer).hexdigest() for layer in layers]
        if reverse:
            diff_ids.reverse()
        config = json.dumps({"created": created, "os": "linux", "architecture": "amd64",
                             "rootfs": {"type": "layers", "diff_ids": diff_ids}}, separators=(",", ":")).encode()
        members = {"blobs/sha256/config": config}
        layer_names = []
        for i, layer in enumerate(layers):
            compressed = gzip.compress(layer, mtime=compression_time)
            path = "blobs/sha256/" + hashlib.sha256(compressed).hexdigest()
            members[path] = compressed
            layer_names.append(path)
        manifest = [{"Config": "blobs/sha256/config", "RepoTags": [image], "Layers": layer_names}]
        if extra:
            members["other.json"] = json.dumps({"os": "linux", "architecture": "arm64", "rootfs": {"type": "layers", "diff_ids": []}}).encode()
            manifest.insert(0, {"Config": "other.json", "RepoTags": ["other:fixture"], "Layers": []})
        members["manifest.json"] = json.dumps(manifest).encode()
        # Docker 29 may expose an OCI index digest as its Engine ID. The config
        # must still be read from the manifest-selected member, not this index.
        members["index.json"] = b'{"schemaVersion":2,"manifests":[]}'
        archive = self.root / name
        with tarfile.open(archive, "w") as output:
            for path, raw in members.items():
                entry = tarfile.TarInfo(path)
                entry.size = len(raw)
                output.addfile(entry, io.BytesIO(raw))
        return archive, {"configDigest": "sha256:" + hashlib.sha256(config).hexdigest(),
                         "diffIds": diff_ids, "os": "linux", "architecture": "amd64"}

    def test_selects_exact_tag_and_config_bytes_from_multiple_images(self):
        archive, expected = self.archive("multi.tar", extra=True)
        self.assertEqual(archive_identity(archive, IMAGE), expected)
        with self.assertRaisesRegex(ValueError, "exactly one"):
            archive_identity(archive, "missing:fixture")

    def test_recompressed_layers_keep_content_identity(self):
        first, expected = self.archive("first.tar", compression_time=0)
        second, _ = self.archive("recompressed.tar", compression_time=1)
        self.assertNotEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(archive_identity(first, IMAGE), expected)
        self.assertEqual(archive_identity(second, IMAGE), expected)

    def test_preserves_uncompressed_layer_order(self):
        original, _ = self.archive("original.tar")
        reordered, expected = self.archive("reordered.tar", reverse=True)
        self.assertEqual(archive_identity(reordered, IMAGE), expected)
        self.assertNotEqual(archive_identity(original, IMAGE), expected)

    def test_comparison_accepts_same_content_and_rejects_changed_config(self):
        candidate, expected = self.archive("candidate.tar")
        changed, _ = self.archive("changed.tar", created="2026-11-11T00:00:00Z")
        manifest = self.root / "source-manifest.json"
        manifest.write_text(json.dumps({"imageContent": {"app": expected}}), encoding="utf-8")
        command = [sys.executable, str(SCRIPT), str(candidate), IMAGE,
                   "--expected-manifest", str(manifest), "--component", "app"]
        same = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(same.returncode, 0, same.stderr)
        self.assertEqual(json.loads(same.stdout), expected)
        command[2] = str(changed)
        different = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(different.returncode, 0)
        self.assertIn("Refusing image content different", different.stderr)


if __name__ == "__main__":
    unittest.main()

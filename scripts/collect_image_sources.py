"""Collect the exact runtime sources and upstream build recipes for the two release images.

Run after building the final images. Downloads use official distribution indexes and
their checksums; any missing source stops packaging instead of producing a partial bundle.
"""
import argparse
import hashlib
from http.client import HTTPException
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import zipfile

from image_identity import archive_identity


def docker(image, *command):
    return subprocess.check_output(["docker", "run", "--rm", "--network", "none", "--entrypoint", command[0], image, *command[1:]])


def paragraphs(text):
    for block in text.strip().split("\n\n"):
        fields, key = {}, None
        for line in block.splitlines():
            if line.startswith(" "):
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        yield fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True)
    parser.add_argument("--agent", required=True)
    parser.add_argument("--app-archive", type=Path, required=True)
    parser.add_argument("--agent-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    downloads = []

    def fetch(url, relative, expected=None, algorithm="sha256", record=True):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or (expected and hashlib.new(algorithm, path.read_bytes()).hexdigest() != expected):
            # Large source archives have suffered truncated HTTPS transfers in
            # release checks. Retry network failures only, at most three times;
            # HTTP failures and checksum mismatches remain immediate failures.
            for attempt in range(1, 4):
                print(f"Downloading ({attempt}/3) " + url, flush=True)
                try:
                    with urlopen(Request(url, headers={"User-Agent": "Causelume-source-bundle"}), timeout=120) as response:
                        data = response.read()
                    break
                except HTTPError:
                    raise
                except (URLError, OSError, HTTPException) as failure:
                    if attempt == 3:
                        raise RuntimeError("Source download failed after three network attempts: " + url) from failure
            if expected and hashlib.new(algorithm, data).hexdigest() != expected:
                raise RuntimeError("Source checksum mismatch: " + url)
            path.write_bytes(data)
        data = path.read_bytes()
        if record:
            downloads.append({"path": relative, "url": url, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                              "upstreamChecksum": expected, "upstreamChecksumAlgorithm": algorithm if expected else None})
        print("Collected " + relative, flush=True)
        return data

    image_info = dict(zip(("app", "agent"), json.loads(subprocess.check_output(
        ["docker", "image", "inspect", args.app, args.agent]))))
    metadata = {"version": args.version, "images": {name: info["Id"] for name, info in image_info.items()},
                "imageLabels": {name: info["Config"]["Labels"] for name, info in image_info.items()},
                "imageContent": {"app": archive_identity(args.app_archive, args.app),
                    "agent": archive_identity(args.agent_archive, args.agent)}}
    metadata["alpinePackages"] = {}
    metadata["alpineReleases"] = {}
    recipes = set()
    for name, image in (("app", args.app), ("agent", args.agent)):
        release = docker(image, "cat", "/etc/alpine-release").decode().strip()
        if not release.startswith("3.24."):
            raise ValueError("The source collector requires the declared Alpine 3.24 runtime: " + name)
        metadata["alpineReleases"][name] = release
        apk = list(paragraphs(docker(image, "cat", "/lib/apk/db/installed").decode()))
        metadata["alpinePackages"][name] = [{key: row.get(key) for key in ("P", "V", "L", "o", "c")} for row in apk]
        recipes.update((row["o"], row["c"]) for row in apk if row.get("o"))
    for origin, commit in sorted(recipes):
        # Every installed package in this pinned Alpine base comes from main.
        # Alpine's official GitHub mirror serves automated downloads without
        # GitLab's runner-blocking HTTP 418 challenge. Keep the installed commit.
        recipe_url = f"https://codeload.github.com/alpinelinux/aports/tar.gz/{commit}"
        snapshot = fetch(recipe_url, f"cache/aports-{commit}.tar.gz", record=False)
        prefix = f"aports-{commit}/main/{origin}/"
        filtered = io.BytesIO()
        with tarfile.open(fileobj=io.BytesIO(snapshot)) as source, tarfile.open(fileobj=filtered, mode="w:gz") as output:
            for member in source.getmembers():
                if member.name.startswith(prefix):
                    output.addfile(member, source.extractfile(member) if member.isfile() else None)
        raw = filtered.getvalue()
        relative = f"alpine/recipes/{origin}-{commit}.tar.gz"
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        downloads.append({"path": relative, "kind": "filtered-archive", "bytes": len(raw),
                          "sha256": hashlib.sha256(raw).hexdigest(), "retainedPrefix": prefix,
                          "derivedFrom": {"url": recipe_url, "sha256": hashlib.sha256(snapshot).hexdigest(),
                                          "bytes": len(snapshot), "commit": commit}})
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            files = {Path(member.name).name: archive.extractfile(member).read() for member in archive.getmembers() if member.isfile()}
        recipe = files["APKBUILD"].decode()
        checksums = re.search(r'sha512sums="(.*?)"', recipe, re.S)
        if not checksums:
            if re.search(r"^source=", recipe, re.M):
                raise RuntimeError("Missing Alpine source checksums: " + origin)
            # Meta packages such as alpine-release are generated entirely by
            # their preserved APKBUILD and have no external source archive.
            continue
        for digest, filename in re.findall(r"([a-f0-9]{128})\s+(\S+)", checksums.group(1)):
            if filename in files:
                if hashlib.sha512(files[filename]).hexdigest() != digest:
                    raise RuntimeError("Alpine recipe file checksum mismatch: " + filename)
            else:
                fetch(f"https://distfiles.alpinelinux.org/distfiles/v3.24/{filename}", f"alpine/distfiles/{filename}", digest, "sha512")

    release = docker(args.app, "cat", "/opt/java/openjdk/release").decode()
    metadata["javaRelease"] = release
    source_commit = re.search(r'^SOURCE="\.:git:([^" ]+)', release, re.M).group(1)
    build_commit = re.search(r'^BUILD_SOURCE="git:([^" ]+)', release, re.M).group(1)
    fetch(f"https://codeload.github.com/adoptium/jdk21u/tar.gz/{source_commit}", f"runtimes/temurin-jdk21u-{source_commit}.tar.gz")
    fetch(f"https://codeload.github.com/adoptium/temurin-build/tar.gz/{build_commit}", f"runtimes/temurin-build-{build_commit}.tar.gz")
    python_version = docker(args.agent, "python", "-c", "import platform; print(platform.python_version())").decode().strip()
    python_digest = next(value.split("=", 1)[1] for value in image_info["agent"]["Config"]["Env"] if value.startswith("PYTHON_SHA256="))
    fetch(f"https://www.python.org/ftp/python/{python_version}/Python-{python_version}.tar.xz", f"runtimes/Python-{python_version}.tar.xz", python_digest)

    packages = json.loads(docker(args.agent, "python", "-c", "import importlib.metadata as m,json; print(json.dumps(sorted((d.metadata['Name'],d.version) for d in m.distributions(path=['/opt/dependencies']))))"))
    metadata["pythonPackages"] = packages
    for name, version in packages:
        info = json.loads(fetch(f"https://pypi.org/pypi/{name}/{version}/json", f"indexes/pypi-{name}-{version}.json"))
        sdist = next(row for row in info["urls"] if row["packagetype"] == "sdist")
        fetch(sdist["url"], "python/" + sdist["filename"], sdist["digests"]["sha256"])

    # Extract only the candidate's embedded libraries, without starting its application.
    container = subprocess.check_output(["docker", "create", args.app]).decode().strip()
    try:
        subprocess.run(["docker", "cp", container + ":/app/server.jar", str(root / "candidate.jar")], check=True, stdout=subprocess.DEVNULL)
    finally:
        subprocess.run(["docker", "rm", container], check=True, stdout=subprocess.DEVNULL)
    metadata["javaPackages"] = []
    dependencies = docker(args.app, "cat", "/usr/share/causelume/licenses/java-dependencies.txt").decode()
    coordinates = {}
    for group, artifact, version in re.findall(r"^\s*([\w.-]+):([\w.-]+):jar:([^:\s]+):(?:compile|runtime)", dependencies, re.M):
        coordinates[f"{artifact}-{version}.jar"] = {"groupId": group, "artifactId": artifact, "version": version}
    with zipfile.ZipFile(root / "candidate.jar") as outer:
        for entry in outer.namelist():
            if not entry.startswith("BOOT-INF/lib/") or not entry.endswith(".jar"):
                continue
            fields = coordinates.get(Path(entry).name)
            # Repackaging injects this Boot loader tool outside the dependency graph.
            if fields is None and (match := re.fullmatch(r"spring-boot-jarmode-tools-(.+)\.jar", Path(entry).name)):
                fields = {"groupId": "org.springframework.boot", "artifactId": "spring-boot-jarmode-tools", "version": match.group(1)}
            if fields is None:
                raise RuntimeError("Missing resolved Maven coordinates: " + entry)
            group, artifact, version = (fields[key] for key in ("groupId", "artifactId", "version"))
            metadata["javaPackages"].append(fields)
            filename = f"{artifact}-{version}-sources.jar"
            fetch(f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{filename}", f"java/{group}/{filename}")
    metadata["downloads"] = sorted({row["path"]: row for row in downloads}.values(), key=lambda row: row["path"])
    manifest = root / "manifest.json"
    manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    bundle = root.parent / f"causelume-v{args.version}-third-party-sources.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.write(manifest, "manifest.json")
        for row in metadata["downloads"]:
            archive.write(root / row["path"], row["path"])
    with zipfile.ZipFile(bundle) as archive:
        if invalid := archive.testzip():
            raise RuntimeError("Source bundle contains a corrupt member: " + invalid)
    print(json.dumps({"bundle": str(bundle), "bytes": bundle.stat().st_size, "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(), "images": metadata["images"]}), flush=True)


if __name__ == "__main__":
    main()

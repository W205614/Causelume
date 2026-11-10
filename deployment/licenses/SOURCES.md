# Image licenses and corresponding sources

Causelume source is MIT licensed. This does not relicense the runtime, operating
system, or dependencies. No third-party source is modified by Causelume. The
Dockerfiles remove build/package-management files and apply the documented
Alpine zlib package updates; the upstream package patches and build recipes are
included in the source bundle.

The release matching the image's `org.opencontainers.image.version` label provides
`causelume-v<VERSION>-third-party-sources.zip` alongside the application source ZIP:

https://github.com/W205614/Causelume/releases

For this v1.0.3 image release, the corresponding source bundle is:

https://github.com/W205614/Causelume/releases/download/v1.0.3/causelume-v1.0.3-third-party-sources.zip

The bundle's `manifest.json` binds the collected sources to the actual app and
agent image IDs and records each download URL, size, SHA-256, and available
upstream checksum. It includes Alpine recipes and patches at each image's installed package
source commit plus checksum-verified distfiles, the Temurin OpenJDK
source and build scripts at their recorded commits, CPython source, and the
Java/Python application dependencies' source archives. `SHA256SUMS` in the same
release checks both source ZIPs. The archive is provided with the binary release
so access does not depend on the future availability of an upstream URL.

Alpine recipes come from the distribution's official GitHub mirror at
https://github.com/alpinelinux/aports using each installed package's exact source
commit. Only `main/<package>/` is retained from each Git snapshot, preserving its
archive metadata and file modes. The manifest records the original snapshot URL,
commit and SHA-256 separately from the filtered recipe archive's SHA-256; the
unrelated package directories and download cache are not bundled.

License and copyright locations inside the images:

- Project: `/usr/share/causelume/licenses/LICENSE` and `THIRD_PARTY_NOTICES.md`.
- Temurin: `/opt/java/openjdk/legal/`, `/opt/java/openjdk/NOTICE`, and
  `/opt/java/openjdk/release`. OpenJDK uses GPL-2.0 with the Classpath Exception
  (and the notices for its third-party components).
- Java libraries: `META-INF/LICENSE*`, `NOTICE*` and license directories inside
  the nested dependency JARs in `/app/server.jar`; corresponding sources and
  original notices are also in the source bundle. For dual-licensed libraries,
  Causelume distributes Logback under EPL-2.0, H2 under MPL-2.0, and Jakarta
  Annotation/Mail and Angus Mail under EPL-2.0; per-file EDL terms remain intact.
- Alpine: exact package name/version, license expression and source commit are
  recorded in `alpine-packages.txt`. The source bundle contains the original
  licenses with each package's full source and distribution build recipe.
- CPython: `/usr/local/lib/python3.13/LICENSE.txt`.
- Python dependencies: `/opt/dependencies/<distribution>.dist-info/` license
  files; installed versions are in `python-packages.txt` beside this file.

Reproduce the bundle after building the final Linux images, using Python 3.13
and Docker (network access to the official distributions is required). Use the
full commit tag from the Release for both the save and collector commands, so
the archive RepoTags select the intended image:

```sh
docker save <APP_IMAGE> -o app.tar
docker save <AGENT_IMAGE> -o agent.tar
python scripts/collect_image_sources.py --app <APP_IMAGE> --agent <AGENT_IMAGE> \
  --app-archive app.tar --agent-archive agent.tar --version <VERSION> --output <LOCAL_OUTPUT_DIRECTORY>
```

Source collection must complete before publishing either image. A missing exact
version or checksum mismatch is a release failure; do not publish a partial bundle.
This source collection does not make the Causelume application subject to the
licenses of independently distributed operating-system tools or runtimes.

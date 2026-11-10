FROM python:3.13.18-alpine3.24@sha256:8bcfa47b04b853a17aaf909362b847b2112fd6ec4105ea553ef9a43bafbb5916 AS python
FROM python AS dependencies
COPY agent/requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --target /opt/dependencies -r /tmp/requirements.txt

FROM python AS runtime
RUN apk add --no-cache --upgrade zlib=1.4-r0
# Dependencies are installed during the build, so the runtime can omit pip.
RUN python -m pip uninstall --yes pip && rm -r /usr/local/lib/python3.13/ensurepip
ENV PYTHONPATH=/opt/dependencies PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 BIND_HOST=0.0.0.0
WORKDIR /app
COPY --from=dependencies /opt/dependencies /opt/dependencies
COPY agent/common.py agent/observability.py agent/investigator.py agent/checkout.py ./
COPY LICENSE THIRD_PARTY_NOTICES.md deployment/licenses/SOURCES.md /usr/share/causelume/licenses/
RUN cp /lib/apk/db/installed /usr/share/causelume/licenses/alpine-packages.txt \
 && python -c "import importlib.metadata as m; print('\n'.join(sorted(d.metadata['Name']+'=='+d.version for d in m.distributions(path=['/opt/dependencies']))))" > /usr/share/causelume/licenses/python-packages.txt
LABEL org.opencontainers.image.source="https://github.com/W205614/Causelume"
USER 65532:65532
CMD ["python", "investigator.py"]

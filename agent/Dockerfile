FROM python:3.13.18-alpine3.24@sha256:8bcfa47b04b853a17aaf909362b847b2112fd6ec4105ea553ef9a43bafbb5916 AS python
FROM python AS dependencies
COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --target /opt/dependencies -r /tmp/requirements.txt

FROM python AS runtime
# Dependencies are installed during the build, so the runtime can omit pip.
RUN python -m pip uninstall --yes pip && rm -r /usr/local/lib/python3.13/ensurepip
ENV PYTHONPATH=/opt/dependencies PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 BIND_HOST=0.0.0.0
WORKDIR /app
COPY --from=dependencies /opt/dependencies /opt/dependencies
COPY common.py observability.py investigator.py checkout.py ./
USER 65532:65532
CMD ["python", "investigator.py"]

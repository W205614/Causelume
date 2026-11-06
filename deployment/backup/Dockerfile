FROM restic/restic:0.19.1@sha256:136600b6ff6843d61d355f7f71f460a166429f35de6fd11b568fece3c9a4d510 AS restic
FROM postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5
COPY --from=restic /usr/bin/restic /usr/local/bin/restic
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
COPY backup.sh /usr/local/bin/backup
COPY restore.sh /usr/local/bin/restore-check
RUN chmod 755 /usr/local/bin/backup /usr/local/bin/restore-check
ENTRYPOINT ["/usr/local/bin/backup"]

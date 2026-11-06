"""Generate a local offline-demo configuration without overwriting existing values."""
import secrets
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    template = (root / ".env.example").read_text(encoding="utf-8")
    for marker in ("ADMIN_PASSWORD", "OPERATOR_PASSWORD", "AGENT_TOKEN", "CONTROL_TOKEN",
                   "EVIDENCE_TOKEN", "HOLMES_TOKEN"):
        template = template.replace(f"<GENERATE_{marker}>", secrets.token_urlsafe(32))
    destination = root / ".env"
    with destination.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(template)
    print("Created .env for the local offline demo. Read login credentials in that file.")


if __name__ == "__main__":
    main()

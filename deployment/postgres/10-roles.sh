#!/bin/sh
set -eu
export APP_PASSWORD="$(cat /run/secrets/APP_DB_PASSWORD)"
export MIGRATOR_PASSWORD="$(cat /run/secrets/APP_MIGRATOR_PASSWORD)"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_password APP_PASSWORD
\getenv migrator_password MIGRATOR_PASSWORD
CREATE ROLE causelume_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE causelume_app LOGIN PASSWORD :'app_password';
GRANT CREATE ON DATABASE causelume TO causelume_migrator;
ALTER SCHEMA public OWNER TO causelume_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO causelume_app;
ALTER DEFAULT PRIVILEGES FOR ROLE causelume_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO causelume_app;
ALTER DEFAULT PRIVILEGES FOR ROLE causelume_migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO causelume_app;
SQL

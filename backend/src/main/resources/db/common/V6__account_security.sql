ALTER TABLE accounts ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE accounts ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN mfa_seed VARCHAR(256);
ALTER TABLE accounts ADD COLUMN last_totp_step BIGINT NOT NULL DEFAULT -1;
CREATE TABLE recovery_codes (
 account_id BIGINT NOT NULL REFERENCES accounts,
 code_hash VARCHAR(64) NOT NULL, PRIMARY KEY(account_id,code_hash)
);
ALTER TABLE tasks ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX tasks_by_expiry ON tasks(status,deadline_at);
ALTER TABLE accounts ADD COLUMN email VARCHAR(254);

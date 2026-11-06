ALTER TABLE tasks ADD COLUMN source_id VARCHAR(100);
ALTER TABLE tasks ADD COLUMN service_label VARCHAR(100);
ALTER TABLE tasks ADD COLUMN environment VARCHAR(100);
ALTER TABLE tasks ADD COLUMN window_start TIMESTAMP WITH TIME ZONE;
ALTER TABLE tasks ADD COLUMN window_end TIMESTAMP WITH TIME ZONE;
ALTER TABLE tasks ADD COLUMN feedback_rating VARCHAR(20);
CREATE TABLE model_budget_lock (id INTEGER PRIMARY KEY CHECK(id=1));
INSERT INTO model_budget_lock(id) VALUES(1);
CREATE TABLE model_reservations (
 task_id BIGINT PRIMARY KEY REFERENCES tasks,
 reserved_usd NUMERIC(14,6) NOT NULL,
 actual_usd NUMERIC(14,6),
 state VARCHAR(16) NOT NULL CHECK(state IN ('HELD','UNKNOWN','SETTLED')),
 budget_date DATE NOT NULL,
 reason VARCHAR(2000),
 created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX reservations_by_date ON model_reservations(budget_date,state);

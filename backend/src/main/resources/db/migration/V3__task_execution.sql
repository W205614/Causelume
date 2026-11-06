ALTER TABLE tasks ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tasks ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tasks ADD COLUMN error_code VARCHAR(40);
CREATE INDEX tasks_by_deadline ON tasks(status,started_at);

ALTER TABLE tasks ADD COLUMN summary VARCHAR(240);
CREATE INDEX tickets_by_status_id ON tickets(status,id);
CREATE INDEX tickets_by_service_id ON tickets(service_id,id);
CREATE INDEX tickets_by_assignee_id ON tickets(assignee_id,id);
CREATE INDEX alerts_by_severity_id ON alerts(severity,id);
CREATE INDEX alerts_by_service_id ON alerts(service_id,id);
CREATE INDEX tasks_by_ticket_id ON tasks(ticket_id,id);

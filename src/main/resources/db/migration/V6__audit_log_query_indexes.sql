CREATE INDEX idx_audit_log_target_account_id ON audit_log (target_account_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at);

CREATE TABLE audit_log (
    id uuid PRIMARY KEY,
    actor_account_id uuid NOT NULL REFERENCES accounts (id),
    target_account_id uuid NOT NULL REFERENCES accounts (id),
    action text NOT NULL,
    result text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- AD-20 / addendum ("se protege a nivel de rol de PostgreSQL, no solo de
-- código"): un REVOKE UPDATE/DELETE clásico NO basta aquí porque el rol
-- de aplicación (DB_USER, ver docker-compose/.env.example) es también el
-- ÚNICO rol de PostgreSQL del proyecto — el mismo que ejecuta esta
-- migración y por tanto el DUEÑO de la tabla. En PostgreSQL el dueño de
-- una tabla conserva sus privilegios DML implícitos pese a cualquier
-- REVOKE explícito sobre su propio rol (no hay forma de que un rol se
-- quite privilegios a sí mismo sobre algo que posee). Un trigger que
-- rechaza incondicionalmente UPDATE/DELETE sí es una garantía real
-- dentro de esta topología de un solo rol — es la alternativa elegida
-- para esta historia. Separar un segundo rol de aplicación de bajo
-- privilegio (dueño ≠ rol de runtime) sería la solución "de manual" con
-- más de un rol de PostgreSQL, pero requiere tocar docker-compose/README
-- (aprovisionamiento de roles) — fuera de alcance de esta historia,
-- ver deferred-work.md.
CREATE OR REPLACE FUNCTION reject_audit_log_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log es append-only: % no está permitido', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();

-- PostgreSQL nunca dispara triggers FOR EACH ROW para TRUNCATE (solo
-- INSERT/UPDATE/DELETE por fila) — sin este segundo trigger, TRUNCATE
-- audit_log borraría todo el historial pese a la garantía "append-only"
-- de arriba. TRUNCATE solo admite triggers FOR EACH STATEMENT.
CREATE TRIGGER audit_log_append_only_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_log_mutation();

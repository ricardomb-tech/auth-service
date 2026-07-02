CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE,
    password_hash text,
    status text NOT NULL,
    failed_attempts int NOT NULL DEFAULT 0,
    locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE account_roles (
    account_id uuid NOT NULL REFERENCES accounts (id),
    role text NOT NULL,
    PRIMARY KEY (account_id, role)
);

CREATE TABLE verification_tokens (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    token_hash text NOT NULL UNIQUE,
    purpose text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

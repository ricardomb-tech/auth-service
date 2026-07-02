-- Habilita gen_random_uuid(), necesaria para la generación de UUID de las
-- entidades que introduce la Story 1.2 en adelante (AD-14).
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

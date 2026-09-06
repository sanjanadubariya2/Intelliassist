-- Runs once, on first initialisation of an empty data volume.
--
-- Alembic also issues CREATE EXTENSION in the first migration; doing it here as
-- well means a bare `docker compose up` gives you a database that is already
-- vector-capable, which makes failures easier to attribute.
CREATE EXTENSION IF NOT EXISTS vector;

-- The Spring Boot backend's database. Same server, separate database: the AI
-- service owns chunks and embeddings, the backend owns tenants and
-- conversations, and neither reads the other's tables. Sharing a server is a
-- development convenience; sharing a schema would be a design mistake.
--
-- Runs only on first initialisation of an empty volume, like the extension
-- above. Flyway creates the tables inside it when the backend starts.
CREATE DATABASE intelliassist_backend OWNER intelliassist;

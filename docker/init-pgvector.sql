-- Runs once, on first initialisation of an empty data volume.
--
-- Alembic also issues CREATE EXTENSION in the first migration; doing it here as
-- well means a bare `docker compose up` gives you a database that is already
-- vector-capable, which makes failures easier to attribute.
CREATE EXTENSION IF NOT EXISTS vector;

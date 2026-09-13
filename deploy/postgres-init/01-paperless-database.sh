#!/bin/sh
# Runs once when the PostgreSQL data directory is initialized: creates the separate Paperless role and database.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE paperless WITH LOGIN PASSWORD '${PAPERLESS_DB_PASSWORD}';
CREATE DATABASE paperless OWNER paperless;
EOSQL

-- V2: indexes for case-insensitive store search on name OR area (ILIKE '%q%').
-- A plain B-tree cannot serve a leading-wildcard match; trigram GIN indexes can,
-- and they work on Devanagari text too. pg_trgm is a trusted extension, so the
-- database owner can enable it without superuser rights.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP INDEX IF EXISTS ix_stores_name_area;

CREATE INDEX ix_stores_name_trgm ON stores USING gin (name gin_trgm_ops);
CREATE INDEX ix_stores_area_trgm ON stores USING gin (area gin_trgm_ops);

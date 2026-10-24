-- Concurrency revision is independent from diagramas.version (JSON format version).
-- Existing rows start at revision 0; no document content is changed.
ALTER TABLE public.diagramas
    ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0;

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS knowledge_ingestion_batch (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id varchar(128) NOT NULL,
    knowledge_base_id varchar(128) NOT NULL,
    content_version varchar(128) NOT NULL,
    manifest_hash char(64) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    checkpoint_document_id varchar(256),
    attempted_count integer NOT NULL DEFAULT 0,
    succeeded_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    error_summary text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, knowledge_base_id, content_version, manifest_hash)
);

CREATE TABLE IF NOT EXISTS knowledge_document (
    tenant_id varchar(128) NOT NULL,
    knowledge_base_id varchar(128) NOT NULL,
    document_id varchar(256) NOT NULL,
    content_version varchar(128) NOT NULL,
    content_hash char(64) NOT NULL,
    source text NOT NULL,
    deleted_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, knowledge_base_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_knowledge_document_version
    ON knowledge_document (tenant_id, knowledge_base_id, content_version)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS tool_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id varchar(128) NOT NULL,
    principal_id varchar(256) NOT NULL,
    request_id varchar(128) NOT NULL,
    tool_name varchar(128) NOT NULL,
    idempotency_key varchar(256),
    outcome varchar(32) NOT NULL,
    duration_ms bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (tenant_id, tool_name, idempotency_key)
);

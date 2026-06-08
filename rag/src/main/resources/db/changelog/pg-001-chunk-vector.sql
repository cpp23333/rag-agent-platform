CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunk_vector (
    chunk_id    VARCHAR(36) PRIMARY KEY,
    kb_id       VARCHAR(36) NOT NULL,
    workspace_id BIGINT NOT NULL,
    embedding   vector(1536),        -- 维度由 KB 配置决定，1536 是 text-embedding-3-small
    model_version VARCHAR(64),
    created_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cv_kb ON chunk_vector (kb_id);
-- HNSW index（pgvector 0.5+），创建后无法修改 ef_construction，按需调整
CREATE INDEX IF NOT EXISTS idx_cv_hnsw ON chunk_vector
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

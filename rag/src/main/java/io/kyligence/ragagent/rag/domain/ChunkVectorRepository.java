package io.kyligence.ragagent.rag.domain;

import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ChunkVectorRepository {

    @Qualifier("pgJdbcTemplate")
    private final JdbcTemplate pgJdbcTemplate;

    @PostConstruct
    void ensureSchema() {
        pgJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        pgJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chunk_vector (
                chunk_id     VARCHAR(36) PRIMARY KEY,
                kb_id        VARCHAR(36) NOT NULL,
                workspace_id BIGINT NOT NULL,
                embedding    vector(1536),
                model_version VARCHAR(64),
                created_at   TIMESTAMP DEFAULT NOW()
            )""");
        pgJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_cv_kb ON chunk_vector (kb_id)");
        // HNSW index — idempotent via IF NOT EXISTS (pgvector 0.5+)
        try {
            pgJdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_cv_hnsw ON chunk_vector
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)""");
        } catch (Exception e) {
            log.warn("HNSW index creation skipped (may already exist): {}", e.getMessage());
        }
    }

    public void upsert(String chunkId, String kbId, long workspaceId,
                       float[] embedding, String modelVersion) {
        pgJdbcTemplate.update("""
            INSERT INTO chunk_vector (chunk_id, kb_id, workspace_id, embedding, model_version)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (chunk_id) DO UPDATE SET
                embedding = EXCLUDED.embedding,
                model_version = EXCLUDED.model_version
            """,
            chunkId, kbId, workspaceId,
            new PGvector(embedding), modelVersion);
    }

    public void deleteByDocId(List<String> chunkIds) {
        if (chunkIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        pgJdbcTemplate.update(
            "DELETE FROM chunk_vector WHERE chunk_id IN (" + placeholders + ")",
            chunkIds.toArray());
    }
}

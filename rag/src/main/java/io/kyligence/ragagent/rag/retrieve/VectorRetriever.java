package io.kyligence.ragagent.rag.retrieve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import io.kyligence.ragagent.core.model.EmbeddingRequest;
import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.rag.domain.RagChunk;
import io.kyligence.ragagent.rag.domain.RagChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever implements Retriever {

    private final ModelGateway modelGateway;
    @Qualifier("pgJdbcTemplate")
    private final JdbcTemplate pgJdbcTemplate;
    private final RagChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        float[] queryVec = modelGateway.embed(
                new EmbeddingRequest(null, List.of(query.text())))
                .embeddings().get(0);

        int limit = query.options().vectorTopK();
        List<Map<String, Object>> rows = pgJdbcTemplate.queryForList(
                "SELECT chunk_id, 1 - (embedding <=> ?) AS score " +
                "FROM chunk_vector WHERE kb_id = ? " +
                "ORDER BY embedding <=> ? LIMIT ?",
                new PGvector(queryVec), query.kbId(),
                new PGvector(queryVec), limit);

        List<RetrievalResult> results = new ArrayList<>();
        for (var row : rows) {
            String chunkId = (String) row.get("chunk_id");
            double score = ((Number) row.get("score")).doubleValue();
            RagChunk chunk = chunkMapper.selectById(chunkId);
            if (chunk == null) continue;
            Map<String, Object> meta = parseMetadata(chunk.getMetadataJson());
            results.add(new RetrievalResult(
                    chunkId, chunk.getDocId(), chunk.getKbId(),
                    chunk.getText(), score, meta));
        }
        return results;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}

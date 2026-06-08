package io.kyligence.ragagent.rag.retrieve;

import java.util.Map;

public record RetrievalResult(
        String chunkId,
        String docId,
        String kbId,
        String text,
        double score,
        Map<String, Object> metadata
) {}

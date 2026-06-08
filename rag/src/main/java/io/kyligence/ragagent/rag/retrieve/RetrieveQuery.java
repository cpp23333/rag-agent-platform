package io.kyligence.ragagent.rag.retrieve;

import java.util.Map;

public record RetrieveQuery(
        String kbId,
        String text,
        int topK,
        Map<String, Object> filters,
        RetrieveOptions options
) {
    public RetrieveQuery(String kbId, String text, int topK) {
        this(kbId, text, topK, Map.of(), RetrieveOptions.defaults());
    }
}

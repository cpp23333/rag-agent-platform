package io.kyligence.ragagent.rag.ingest;

import java.util.Map;

public record RawChunk(String text, int position, String modality, Map<String, Object> metadata) {}

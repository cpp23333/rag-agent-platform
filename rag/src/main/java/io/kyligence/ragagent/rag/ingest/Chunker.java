package io.kyligence.ragagent.rag.ingest;

import java.util.List;

public interface Chunker {
    List<RawChunk> chunk(String text, String modality, java.util.Map<String, Object> metadata);
}

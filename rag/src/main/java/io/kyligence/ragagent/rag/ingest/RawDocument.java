package io.kyligence.ragagent.rag.ingest;

import java.util.List;

/** Output of a Loader: extracted text segments before chunking. */
public record RawDocument(
    String source,
    String contentHash,
    List<RawSegment> segments
) {
    public record RawSegment(String text, String modality, java.util.Map<String, Object> metadata) {}
}

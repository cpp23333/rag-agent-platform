package io.kyligence.ragagent.rag.ingest;

public interface DocumentLoader {
    boolean supports(String sourceType);
    RawDocument load(IngestRequest request);
}

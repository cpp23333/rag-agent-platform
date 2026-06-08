package io.kyligence.ragagent.rag.ingest;

import java.util.List;

public interface IngestService {

    IngestResult ingest(Long workspaceId, IngestRequest request);

    IngestJobStatus getJobStatus(String jobId);

    void deleteDoc(String docId, Long workspaceId);

    record IngestJobStatus(String jobId, String status, int progress, int totalDocs, String error) {}
}

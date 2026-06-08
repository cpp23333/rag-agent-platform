package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.rag.domain.KnowledgeBase;
import io.kyligence.ragagent.rag.ingest.IngestRequest;
import io.kyligence.ragagent.rag.ingest.IngestResult;

import java.util.List;

public interface KnowledgeBaseService {

    KnowledgeBase create(Long workspaceId, CreateKbCommand cmd);

    KnowledgeBase get(String kbId);

    List<KnowledgeBase> list(Long workspaceId);

    void delete(String kbId);

    IngestResult ingest(Long workspaceId, IngestRequest request);

    List<RetrievalResult> retrieve(RetrieveQuery query);

    record CreateKbCommand(String name, String description,
                           String embedModel, int vectorDim) {}
}

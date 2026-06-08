package io.kyligence.ragagent.rag.retrieve;

import java.util.List;

public interface Retriever {
    List<RetrievalResult> retrieve(RetrieveQuery query);
}

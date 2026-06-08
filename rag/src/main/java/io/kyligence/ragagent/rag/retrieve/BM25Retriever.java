package io.kyligence.ragagent.rag.retrieve;

import org.springframework.stereotype.Component;
import java.util.List;

/** BM25 hybrid retrieval — not implemented in phase 1. */
@Component
public class BM25Retriever implements Retriever {

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        return List.of(); // no-op: BM25 disabled in phase 1
    }
}

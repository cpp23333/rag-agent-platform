package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.core.model.RerankRequest;
import io.kyligence.ragagent.core.model.RerankResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankingRetriever implements Retriever {

    private final VectorRetriever vectorRetriever;
    private final ModelGateway modelGateway;

    @Override
    public List<RetrievalResult> retrieve(RetrieveQuery query) {
        List<RetrievalResult> candidates = vectorRetriever.retrieve(query);
        if (candidates.isEmpty()) return candidates;

        RetrieveOptions opts = query.options();
        if (!opts.rerank()) {
            return candidates.subList(0, Math.min(query.topK(), candidates.size()));
        }

        try {
            List<String> texts = candidates.stream().map(RetrievalResult::text).toList();
            RerankResponse reranked = modelGateway.rerank(new RerankRequest(
                    opts.rerankModel(), query.text(), texts, query.topK()));

            List<RetrievalResult> result = new ArrayList<>();
            for (RerankResponse.ScoredDocument sd : reranked.results()) {
                RetrievalResult original = candidates.get(sd.index());
                result.add(new RetrievalResult(
                        original.chunkId(), original.docId(), original.kbId(),
                        original.text(), sd.score(), original.metadata()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Rerank failed, falling back to vector results: {}", e.getMessage());
            return candidates.subList(0, Math.min(query.topK(), candidates.size()));
        }
    }
}

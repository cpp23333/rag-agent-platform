package io.kyligence.ragagent.rag.retrieve;

import io.kyligence.ragagent.core.model.ModelGateway;
import io.kyligence.ragagent.core.model.RerankResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RerankingRetrieverTest {

    @Mock VectorRetriever vectorRetriever;
    @Mock ModelGateway modelGateway;

    @Test
    void rerankReordersResults() {
        List<RetrievalResult> candidates = List.of(
                result("c1", "first text", 0.9),
                result("c2", "second text", 0.8),
                result("c3", "third text", 0.7));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);
        when(modelGateway.rerank(any())).thenReturn(new RerankResponse("bge",
                List.of(
                        new RerankResponse.ScoredDocument(2, 0.95, "third text"),
                        new RerankResponse.ScoredDocument(0, 0.85, "first text"))));

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        RetrieveQuery query = new RetrieveQuery("kb1", "query", 2);
        List<RetrievalResult> results = retriever.retrieve(query);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo("c3");
        assertThat(results.get(0).score()).isEqualTo(0.95);
    }

    @Test
    void rerankDisabledReturnsTopKFromVector() {
        List<RetrievalResult> candidates = List.of(
                result("c1", "t1", 0.9), result("c2", "t2", 0.8), result("c3", "t3", 0.7));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        RetrieveQuery query = new RetrieveQuery("kb1", "q", 2, Map.of(),
                new RetrieveOptions(false, null, 20));
        List<RetrievalResult> results = retriever.retrieve(query);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo("c1");
        verifyNoInteractions(modelGateway);
    }

    @Test
    void rerankFailureFallsBackToVectorResults() {
        List<RetrievalResult> candidates = List.of(result("c1", "t1", 0.9));
        when(vectorRetriever.retrieve(any())).thenReturn(candidates);
        when(modelGateway.rerank(any())).thenThrow(new RuntimeException("rerank unavailable"));

        RerankingRetriever retriever = new RerankingRetriever(vectorRetriever, modelGateway);
        List<RetrievalResult> results = retriever.retrieve(
                new RetrieveQuery("kb1", "q", 5));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkId()).isEqualTo("c1");
    }

    private RetrievalResult result(String id, String text, double score) {
        return new RetrievalResult(id, "doc1", "kb1", text, score, Map.of());
    }
}

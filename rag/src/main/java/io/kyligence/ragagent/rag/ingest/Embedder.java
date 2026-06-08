package io.kyligence.ragagent.rag.ingest;

import io.kyligence.ragagent.core.model.EmbeddingRequest;
import io.kyligence.ragagent.core.model.ModelGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class Embedder {

    private final ModelGateway modelGateway;

    /** Batch embed texts. Returns float[] per input, same order. */
    public List<float[]> embed(List<String> texts, String model) {
        return modelGateway.embed(new EmbeddingRequest(model, texts)).embeddings();
    }
}

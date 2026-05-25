package io.kyligence.ragagent.core.model;

import reactor.core.publisher.Flux;

public interface ModelGateway {

    ChatResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);

    EmbeddingResponse embed(EmbeddingRequest request);

    RerankResponse rerank(RerankRequest request);
}

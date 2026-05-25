package io.kyligence.ragagent.core.model;

import reactor.core.publisher.Flux;

public interface ProviderClient {

    ChatResponse chat(String baseUrl, String apiKey, ChatRequest request);

    Flux<String> chatStream(String baseUrl, String apiKey, ChatRequest request);

    EmbeddingResponse embed(String baseUrl, String apiKey, EmbeddingRequest request);

    RerankResponse rerank(String baseUrl, String apiKey, RerankRequest request);
}

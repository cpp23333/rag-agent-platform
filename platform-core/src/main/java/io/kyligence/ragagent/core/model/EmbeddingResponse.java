package io.kyligence.ragagent.core.model;

import java.util.List;

public record EmbeddingResponse(String model, List<float[]> embeddings, int totalTokens) {}

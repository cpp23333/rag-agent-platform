package io.kyligence.ragagent.core.model;

import java.util.List;

public record EmbeddingRequest(String model, List<String> inputs) {}

package io.kyligence.ragagent.core.model;

import java.util.List;

public record RerankRequest(String model, String query, List<String> documents, int topN) {}

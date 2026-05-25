package io.kyligence.ragagent.core.model;

import java.util.List;

public record RerankResponse(String model, List<ScoredDocument> results) {
    public record ScoredDocument(int index, double score, String text) {}
}

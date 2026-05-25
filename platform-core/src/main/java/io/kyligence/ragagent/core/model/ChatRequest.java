package io.kyligence.ragagent.core.model;

import java.util.List;
import java.util.Map;

public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<Map<String, Object>> tools,
        Map<String, Object> extraParams) {

    public ChatRequest(String model, List<ChatMessage> messages) {
        this(model, messages, null, null, null, null);
    }
}

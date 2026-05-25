package io.kyligence.ragagent.core.model;

import java.util.List;

public record ChatResponse(
        String id,
        String model,
        String content,
        String finishReason,
        List<ToolCall> toolCalls,
        Usage usage) {

    public record ToolCall(String id, String name, String arguments) {}

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}

package io.kyligence.ragagent.core.tool;

import java.util.Map;

public record ToolOutput(Object value, Map<String, Object> metadata) {
    public static ToolOutput of(Object value) { return new ToolOutput(value, Map.of()); }
}

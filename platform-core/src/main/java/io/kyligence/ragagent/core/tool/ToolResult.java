package io.kyligence.ragagent.core.tool;

public record ToolResult(boolean success, ToolOutput output, String errorMessage) {

    public static ToolResult ok(Object value) {
        return new ToolResult(true, ToolOutput.of(value), null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }
}

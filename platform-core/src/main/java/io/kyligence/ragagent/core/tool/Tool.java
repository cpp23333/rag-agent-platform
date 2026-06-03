package io.kyligence.ragagent.core.tool;

public interface Tool {

    String name();

    String description();

    /** JSON Schema string describing the input parameters (for LLM function-calling). */
    String inputSchemaJson();

    ToolResult invoke(ToolInput input, ToolContext ctx);
}

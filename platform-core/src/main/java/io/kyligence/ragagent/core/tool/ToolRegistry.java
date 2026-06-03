package io.kyligence.ragagent.core.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    /** Register a tool (idempotent by name). */
    void register(Tool tool);

    /** Find tool by name. */
    Optional<Tool> find(String name);

    /** Invoke tool by name, throwing if not found. */
    ToolResult invoke(String name, ToolInput input, ToolContext ctx);

    /** List all registered tool names and descriptions. */
    List<ToolSummary> list();

    record ToolSummary(String name, String description, String inputSchemaJson) {}
}

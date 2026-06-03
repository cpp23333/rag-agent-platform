package io.kyligence.ragagent.core.tool;

/**
 * Marker base class for Spring-managed Tool beans.
 * Subclasses are auto-registered into ToolRegistry on startup.
 */
public abstract class JavaBeanTool implements Tool {
    // intentionally empty — serves as type marker for registry scanning
}

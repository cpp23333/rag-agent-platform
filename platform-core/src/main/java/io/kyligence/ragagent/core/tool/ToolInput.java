package io.kyligence.ragagent.core.tool;

import java.util.Map;

public record ToolInput(Map<String, Object> params) {
    public Object get(String key) { return params.get(key); }
    public String getString(String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }
}

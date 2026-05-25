package io.kyligence.ragagent.core.memory;

import java.util.List;

public interface ShortTermMemory {
    void append(String runId, MessageRole role, String content, String name);
    List<ShortTermMessage> getWindow(String runId, int windowSize);
    List<ShortTermMessage> getAll(String runId);
    int count(String runId);
    void clear(String runId);
}

package io.kyligence.ragagent.core.memory;

import java.util.Map;
import java.util.Optional;

/**
 * Working Memory - 工作记忆
 * 存储 Agent 运行期间的临时状态和中间结果
 */
public interface WorkingMemory {
    /**
     * 存储键值对（upsert 语义）
     */
    void put(String runId, String key, String value);

    /**
     * 获取指定 key 的值
     */
    Optional<String> get(String runId, String key);

    /**
     * 获取所有键值对
     */
    Map<String, String> getAll(String runId);

    /**
     * 删除指定 key
     */
    void remove(String runId, String key);

    /**
     * 清空所有条目
     */
    void clear(String runId);
}

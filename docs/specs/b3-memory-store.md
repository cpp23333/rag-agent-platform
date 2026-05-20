# B3 Spec: MemoryStore

## Context

MemoryStore 是 Platform Core 的记忆管理子系统（架构设计 §3.3）。为 Agent 和 Workflow 的 Run 提供会话记忆能力：短期记忆（最近 N 条 message 的滑动窗口）和工作记忆（Run 内 KV scratchpad）。长期记忆（向量化）一期留接口不实现。

## 目标

- `ShortTermMemory`：按 run_id 隔离的滑动窗口，支持添加/截取/获取
- `WorkingMemory`：Run 内 KV 结构，Run 结束归档
- `LongTermMemory`：接口占位，一期返回空
- MySQL 持久化 + Redis 热缓存（可选降级到纯 DB）

## 范围

`platform-core` 模块。不含 REST controller（记忆由 Agent/Workflow 内部使用，不暴露管理 API）。

---

## File Structure

```
platform-core/src/main/resources/db/changelog/changes/
  008-memory-short-term.yaml
  009-memory-working.yaml

platform-core/src/main/java/io/kyligence/ragagent/core/memory/
  MessageRole.java
  ShortTermMessage.java
  ShortTermMessageMapper.java
  WorkingMemoryEntry.java
  WorkingMemoryEntryMapper.java
  ShortTermMemory.java          # 接口
  ShortTermMemoryImpl.java
  WorkingMemory.java            # 接口
  WorkingMemoryImpl.java
  LongTermMemory.java           # 接口（一期 no-op）

platform-core/src/test/java/io/kyligence/ragagent/core/memory/
  ShortTermMemoryImplTest.java
  WorkingMemoryImplTest.java
```

---

## Phase 1: DB Schema

### File: `008-memory-short-term.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 008-memory-short-term
      author: ragagent
      changes:
        - createTable:
            tableName: memory_short_term
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: run_id, type: VARCHAR(36), constraints: { nullable: false } }
              - column: { name: idx, type: INT, constraints: { nullable: false } }
              - column: { name: role, type: VARCHAR(16), constraints: { nullable: false } }
              - column: { name: content, type: LONGTEXT, constraints: { nullable: false } }
              - column: { name: name, type: VARCHAR(128) }
              - column: { name: metadata_json, type: TEXT }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - createIndex:
            tableName: memory_short_term
            indexName: idx_stm_run_idx
            unique: true
            columns:
              - column: { name: run_id }
              - column: { name: idx }
```

### File: `009-memory-working.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 009-memory-working
      author: ragagent
      changes:
        - createTable:
            tableName: memory_working
            columns:
              - column: { name: id, type: BIGINT, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: run_id, type: VARCHAR(36), constraints: { nullable: false } }
              - column: { name: entry_key, type: VARCHAR(128), constraints: { nullable: false } }
              - column: { name: value_json, type: LONGTEXT, constraints: { nullable: false } }
              - column: { name: updated_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: memory_working
            columnNames: "run_id, entry_key"
            constraintName: uq_working_run_key
```

---

## Phase 2: Domain Classes

### File: `core/memory/MessageRole.java`

```java
package io.kyligence.ragagent.core.memory;

public enum MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
```

### File: `core/memory/ShortTermMessage.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("memory_short_term")
public class ShortTermMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Integer idx;
    private MessageRole role;
    private String content;
    private String name;
    private String metadataJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

### File: `core/memory/ShortTermMessageMapper.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortTermMessageMapper extends BaseMapper<ShortTermMessage> {
}
```

### File: `core/memory/WorkingMemoryEntry.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("memory_working")
public class WorkingMemoryEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String entryKey;
    private String valueJson;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

### File: `core/memory/WorkingMemoryEntryMapper.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkingMemoryEntryMapper extends BaseMapper<WorkingMemoryEntry> {
}
```

---

## Phase 3: Service Interfaces & Implementations

### File: `core/memory/ShortTermMemory.java`

```java
package io.kyligence.ragagent.core.memory;

import java.util.List;

public interface ShortTermMemory {

    /** Append a message to the run's conversation history. */
    void append(String runId, MessageRole role, String content, String name);

    /** Get the last N messages for a run (sliding window). */
    List<ShortTermMessage> getWindow(String runId, int windowSize);

    /** Get all messages for a run. */
    List<ShortTermMessage> getAll(String runId);

    /** Count messages in a run. */
    int count(String runId);

    /** Clear all messages for a run (on run completion/cleanup). */
    void clear(String runId);
}
```

### File: `core/memory/ShortTermMemoryImpl.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShortTermMemoryImpl implements ShortTermMemory {

    private final ShortTermMessageMapper mapper;

    @Override
    @Transactional
    public void append(String runId, MessageRole role, String content, String name) {
        int nextIdx = count(runId);
        ShortTermMessage msg = new ShortTermMessage();
        msg.setRunId(runId);
        msg.setIdx(nextIdx);
        msg.setRole(role);
        msg.setContent(content);
        msg.setName(name);
        msg.setCreatedAt(LocalDateTime.now());
        mapper.insert(msg);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShortTermMessage> getWindow(String runId, int windowSize) {
        int total = count(runId);
        int offset = Math.max(0, total - windowSize);
        return mapper.selectList(
                Wrappers.<ShortTermMessage>lambdaQuery()
                        .eq(ShortTermMessage::getRunId, runId)
                        .ge(ShortTermMessage::getIdx, offset)
                        .orderByAsc(ShortTermMessage::getIdx));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShortTermMessage> getAll(String runId) {
        return mapper.selectList(
                Wrappers.<ShortTermMessage>lambdaQuery()
                        .eq(ShortTermMessage::getRunId, runId)
                        .orderByAsc(ShortTermMessage::getIdx));
    }

    @Override
    @Transactional(readOnly = true)
    public int count(String runId) {
        return Math.toIntExact(mapper.selectCount(
                Wrappers.<ShortTermMessage>lambdaQuery()
                        .eq(ShortTermMessage::getRunId, runId)));
    }

    @Override
    @Transactional
    public void clear(String runId) {
        mapper.delete(
                Wrappers.<ShortTermMessage>lambdaQuery()
                        .eq(ShortTermMessage::getRunId, runId));
    }
}
```

### File: `core/memory/WorkingMemory.java`

```java
package io.kyligence.ragagent.core.memory;

import java.util.Map;
import java.util.Optional;

public interface WorkingMemory {

    /** Put a key-value pair into the run's working memory. Upsert semantics. */
    void put(String runId, String key, String valueJson);

    /** Get a value by key. */
    Optional<String> get(String runId, String key);

    /** Get all entries for a run. */
    Map<String, String> getAll(String runId);

    /** Remove a key. */
    void remove(String runId, String key);

    /** Clear all entries for a run. */
    void clear(String runId);
}
```

### File: `core/memory/WorkingMemoryImpl.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkingMemoryImpl implements WorkingMemory {

    private final WorkingMemoryEntryMapper mapper;

    @Override
    @Transactional
    public void put(String runId, String key, String valueJson) {
        WorkingMemoryEntry existing = mapper.selectOne(
                Wrappers.<WorkingMemoryEntry>lambdaQuery()
                        .eq(WorkingMemoryEntry::getRunId, runId)
                        .eq(WorkingMemoryEntry::getEntryKey, key));
        if (existing != null) {
            existing.setValueJson(valueJson);
            existing.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(existing);
        } else {
            WorkingMemoryEntry entry = new WorkingMemoryEntry();
            entry.setRunId(runId);
            entry.setEntryKey(key);
            entry.setValueJson(valueJson);
            entry.setUpdatedAt(LocalDateTime.now());
            mapper.insert(entry);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String runId, String key) {
        WorkingMemoryEntry entry = mapper.selectOne(
                Wrappers.<WorkingMemoryEntry>lambdaQuery()
                        .eq(WorkingMemoryEntry::getRunId, runId)
                        .eq(WorkingMemoryEntry::getEntryKey, key));
        return Optional.ofNullable(entry).map(WorkingMemoryEntry::getValueJson);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getAll(String runId) {
        var entries = mapper.selectList(
                Wrappers.<WorkingMemoryEntry>lambdaQuery()
                        .eq(WorkingMemoryEntry::getRunId, runId));
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach(e -> result.put(e.getEntryKey(), e.getValueJson()));
        return result;
    }

    @Override
    @Transactional
    public void remove(String runId, String key) {
        mapper.delete(
                Wrappers.<WorkingMemoryEntry>lambdaQuery()
                        .eq(WorkingMemoryEntry::getRunId, runId)
                        .eq(WorkingMemoryEntry::getEntryKey, key));
    }

    @Override
    @Transactional
    public void clear(String runId) {
        mapper.delete(
                Wrappers.<WorkingMemoryEntry>lambdaQuery()
                        .eq(WorkingMemoryEntry::getRunId, runId));
    }
}
```

### File: `core/memory/LongTermMemory.java`

一期占位接口，不实现。

```java
package io.kyligence.ragagent.core.memory;

import java.util.List;
import java.util.Map;

public interface LongTermMemory {

    /** Store a memory entry (vectorized). No-op in phase 1. */
    default void store(String workspaceId, String content, Map<String, Object> metadata) {
        // no-op: long-term memory not implemented in phase 1
    }

    /** Retrieve relevant memories by semantic similarity. Returns empty in phase 1. */
    default List<String> recall(String workspaceId, String query, int topK) {
        return List.of();
    }
}
```

---

## Phase 4: Tests

### File: `core/memory/ShortTermMemoryImplTest.java`

```java
package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortTermMemoryImplTest {

    @Mock private ShortTermMessageMapper mapper;
    private ShortTermMemoryImpl memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemoryImpl(mapper);
    }

    @Test
    void appendInsertsWithCorrectIdx() {
        when(mapper.selectCount(any())).thenReturn(3L);
        when(mapper.insert(any())).thenReturn(1);

        memory.append("run-1", MessageRole.USER, "hello", null);

        ArgumentCaptor<ShortTermMessage> cap = ArgumentCaptor.forClass(ShortTermMessage.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getIdx()).isEqualTo(3);
        assertThat(cap.getValue().getRole()).isEqualTo(MessageRole.USER);
        assertThat(cap.getValue().getContent()).isEqualTo("hello");
    }

    @Test
    void getWindowReturnsLastN() {
        when(mapper.selectCount(any())).thenReturn(10L);
        ShortTermMessage m = new ShortTermMessage();
        m.setIdx(8);
        when(mapper.selectList(any())).thenReturn(List.of(m));

        List<ShortTermMessage> result = memory.getWindow("run-1", 3);
        assertThat(result).hasSize(1);
        verify(mapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void clearDeletesByRunId() {
        when(mapper.delete(any())).thenReturn(5);
        memory.clear("run-1");
        verify(mapper).delete(any(LambdaQueryWrapper.class));
    }
}
```

### File: `core/memory/WorkingMemoryImplTest.java`

```java
package io.kyligence.ragagent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkingMemoryImplTest {

    @Mock private WorkingMemoryEntryMapper mapper;
    private WorkingMemoryImpl memory;

    @BeforeEach
    void setUp() {
        memory = new WorkingMemoryImpl(mapper);
    }

    @Test
    void putInsertsNewEntry() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);

        memory.put("run-1", "plan", "{\"steps\":[]}");

        ArgumentCaptor<WorkingMemoryEntry> cap = ArgumentCaptor.forClass(WorkingMemoryEntry.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getEntryKey()).isEqualTo("plan");
        assertThat(cap.getValue().getValueJson()).isEqualTo("{\"steps\":[]}");
    }

    @Test
    void putUpdatesExistingEntry() {
        WorkingMemoryEntry existing = new WorkingMemoryEntry();
        existing.setId(1L);
        existing.setEntryKey("plan");
        existing.setValueJson("old");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any())).thenReturn(1);

        memory.put("run-1", "plan", "new");

        verify(mapper).updateById(any());
        verify(mapper, never()).insert(any());
        assertThat(existing.getValueJson()).isEqualTo("new");
    }

    @Test
    void getReturnsValueWhenPresent() {
        WorkingMemoryEntry e = new WorkingMemoryEntry();
        e.setValueJson("{\"x\":1}");
        when(mapper.selectOne(any())).thenReturn(e);

        Optional<String> result = memory.get("run-1", "key");
        assertThat(result).contains("{\"x\":1}");
    }

    @Test
    void getReturnsEmptyWhenAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(memory.get("run-1", "missing")).isEmpty();
    }

    @Test
    void getAllReturnsMap() {
        WorkingMemoryEntry e1 = new WorkingMemoryEntry();
        e1.setEntryKey("a"); e1.setValueJson("1");
        WorkingMemoryEntry e2 = new WorkingMemoryEntry();
        e2.setEntryKey("b"); e2.setValueJson("2");
        when(mapper.selectList(any())).thenReturn(List.of(e1, e2));

        var result = memory.getAll("run-1");
        assertThat(result).containsEntry("a", "1").containsEntry("b", "2");
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=ShortTermMemoryImplTest,WorkingMemoryImplTest test` 全绿
3. 已有测试不被破坏
4. Liquibase `008` / `009` changelog 被 master 自动加载
5. `ShortTermMemory` / `WorkingMemory` 可被 Agent/Workflow 模块注入使用

## Dependencies

- 无新外部依赖（纯 MyBatis-Plus + Spring）
- `memory_short_term` / `memory_working` 表不含 `workspace_id`（按 `run_id` 隔离，run 本身已绑 workspace）

## 设计决策

| 决策 | 理由 |
|------|------|
| 不加 workspace_id 到 memory 表 | run_id 已隔离；避免冗余列和 tenant interceptor 干扰 |
| idx 自增而非 timestamp 排序 | 保证严格顺序，避免时钟漂移 |
| LongTermMemory 留空接口 | 一期不做向量记忆，但保留扩展点 |
| 不暴露 REST API | 记忆是内部能力，由 Agent/Workflow 运行时调用 |
# B3 Memory Store 功能分析与调用链

> 基于 `docs/specs/b3-memory-store.md` 的详细分析
> 
> 日期：2026-05-26

## 概述

**B3 是 Platform Core 的记忆管理子系统**，为 Agent 和 Workflow 的 Run 提供三种记忆能力：

### 三种记忆类型

1. **短期记忆 (ShortTermMemory)**
   - 按 `run_id` 隔离的对话历史
   - 支持滑动窗口（最近 N 条消息）
   - 存储格式：role + content + metadata
   - 用途：维护 Agent 的上下文对话

2. **工作记忆 (WorkingMemory)**
   - Run 内的 KV 键值存储
   - Upsert 语义（插入或更新）
   - Run 结束后归档
   - 用途：存储中间计算结果、执行计划等

3. **长期记忆 (LongTermMemory)**
   - 一期占位，不实现（预留向量化检索接口）

---

## 生产环境调用链实例

### 场景：用户通过 Agent 进行多轮对话查询

```
┌─────────────────────────────────────────────────────────────┐
│  用户请求：询问公司 Q1 财报数据                                 │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  1. Agent Controller 创建 Run                                │
│     - runId = "run-abc123"                                   │
│     - workspaceId = "ws-001"                                 │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  2. 存储用户输入到短期记忆                                      │
│     shortTermMemory.append(                                  │
│       runId = "run-abc123",                                  │
│       role = USER,                                           │
│       content = "Q1财报数据是多少？"                           │
│     )                                                        │
│     → 写入 memory_short_term 表 (idx=0)                      │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  3. Agent 执行推理，分解任务                                   │
│     workingMemory.put(                                       │
│       runId = "run-abc123",                                  │
│       key = "task_plan",                                     │
│       value = '{"steps": ["查询数据库", "格式化结果"]}'        │
│     )                                                        │
│     → 写入 memory_working 表                                  │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  4. Agent 调用 Tool 查询数据                                  │
│     workingMemory.put(                                       │
│       key = "query_result",                                  │
│       value = '{"revenue": "100M", "profit": "20M"}'         │
│     )                                                        │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  5. 存储 Agent 回复到短期记忆                                  │
│     shortTermMemory.append(                                  │
│       runId = "run-abc123",                                  │
│       role = ASSISTANT,                                      │
│       content = "Q1营收1亿美元，利润2000万美元"                │
│     )                                                        │
│     → 写入 memory_short_term 表 (idx=1)                      │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  6. 用户追问："同比增长多少？"                                  │
│     shortTermMemory.append(runId, USER, "同比增长多少？")      │
│     → idx=2                                                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  7. Agent 获取对话上下文                                       │
│     messages = shortTermMemory.getWindow(                    │
│       runId = "run-abc123",                                  │
│       windowSize = 10  // 最近10条消息                        │
│     )                                                        │
│     → 查询 memory_short_term WHERE run_id AND idx >= 0       │
│     → 返回 [msg0, msg1, msg2]                                │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  8. Agent 从工作记忆读取中间结果                                │
│     queryResult = workingMemory.get(                         │
│       runId = "run-abc123",                                  │
│       key = "query_result"                                   │
│     )                                                        │
│     → 查询 memory_working 表                                  │
│     → 返回上次查询的财报数据                                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  9. Agent 计算同比增长并回复                                   │
│     shortTermMemory.append(                                  │
│       runId, ASSISTANT,                                      │
│       "同比增长15%"                                            │
│     )                                                        │
│     → idx=3                                                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  10. Run 结束，清理内存                                        │
│      shortTermMemory.clear("run-abc123")                     │
│      workingMemory.clear("run-abc123")                       │
│      → DELETE FROM memory_short_term WHERE run_id            │
│      → DELETE FROM memory_working WHERE run_id               │
└─────────────────────────────────────────────────────────────┘
```

---

## 数据流详解

### ShortTermMemory 数据流

```sql
-- 1. 插入用户消息
INSERT INTO memory_short_term (run_id, idx, role, content, created_at)
VALUES ('run-abc123', 0, 'USER', 'Q1财报数据是多少？', NOW());

-- 2. 插入 Agent 回复
INSERT INTO memory_short_term (run_id, idx, role, content, created_at)
VALUES ('run-abc123', 1, 'ASSISTANT', 'Q1营收1亿美元...', NOW());

-- 3. 获取滑动窗口（最近10条）
SELECT * FROM memory_short_term
WHERE run_id = 'run-abc123' AND idx >= 0
ORDER BY idx ASC;

-- 4. 清理记忆
DELETE FROM memory_short_term WHERE run_id = 'run-abc123';
```

### WorkingMemory 数据流

```sql
-- 1. 存储任务计划
INSERT INTO memory_working (run_id, entry_key, value_json, updated_at)
VALUES ('run-abc123', 'task_plan', '{"steps":[...]}', NOW())
ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_at = NOW();

-- 2. 存储查询结果
INSERT INTO memory_working (run_id, entry_key, value_json, updated_at)
VALUES ('run-abc123', 'query_result', '{"revenue":"100M",...}', NOW())
ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_at = NOW();

-- 3. 读取特定 key
SELECT value_json FROM memory_working
WHERE run_id = 'run-abc123' AND entry_key = 'query_result';

-- 4. 读取所有 entries
SELECT entry_key, value_json FROM memory_working
WHERE run_id = 'run-abc123';

-- 5. 清理工作记忆
DELETE FROM memory_working WHERE run_id = 'run-abc123';
```

---

## 关键设计特点

### 1. **按 run_id 隔离**
- 每个 Agent/Workflow 执行都有独立的 `run_id`
- 不同 Run 之间记忆完全隔离
- 不需要 `workspace_id` 字段（Run 本身已绑定 workspace）

### 2. **idx 严格顺序**
- 短期记忆用递增的 `idx` 保证消息顺序
- 避免时钟漂移导致的乱序
- 支持精确的滑动窗口查询

**为什么用 idx 而不是 timestamp？**
```java
// 场景：两条消息几乎同时插入
// 使用 timestamp 可能出现：
msg1.timestamp = 2026-05-26 10:00:00.123
msg2.timestamp = 2026-05-26 10:00:00.123  // 相同！

// 使用 idx 则严格递增：
msg1.idx = 0
msg2.idx = 1  // 保证顺序
```

### 3. **Upsert 语义**
- WorkingMemory 的 `put` 操作自动处理插入/更新
- 同一 key 多次写入会覆盖旧值
- 适合存储动态变化的中间状态

```java
// 第一次调用
workingMemory.put(runId, "plan", "{v1}");  // INSERT

// 第二次调用（同一 key）
workingMemory.put(runId, "plan", "{v2}");  // UPDATE
```

### 4. **MySQL + Redis 架构**
- MySQL 持久化存储
- Redis 可选热缓存（一期可降级到纯 DB）
- MyBatis-Plus 简化 CRUD

### 5. **内部 API**
- 不暴露 REST Controller
- 只供 Agent/Workflow 内部使用
- 通过 Spring 依赖注入调用

---

## 典型使用模式

| 记忆类型 | 适用场景 | 示例 | 生命周期 |
|---------|---------|------|---------|
| **ShortTermMemory** | 多轮对话上下文 | 聊天机器人、问答系统 | Run 开始 → Run 结束 |
| **WorkingMemory** | 任务执行中的临时状态 | 执行计划、中间结果、工具调用记录 | Run 开始 → Run 结束 |
| **LongTermMemory** | 跨会话的知识积累 | 用户偏好、历史问答库 | 永久保存（一期不实现）|

---

## 代码示例

### Agent 内部使用 Memory

```java
@Service
@RequiredArgsConstructor
public class ChatAgentService {
    
    private final ShortTermMemory shortTermMemory;
    private final WorkingMemory workingMemory;
    
    public String chat(String runId, String userMessage) {
        // 1. 保存用户消息
        shortTermMemory.append(runId, MessageRole.USER, userMessage, null);
        
        // 2. 获取对话历史（最近10条）
        List<ShortTermMessage> history = shortTermMemory.getWindow(runId, 10);
        
        // 3. 从工作记忆读取上下文
        Optional<String> context = workingMemory.get(runId, "user_context");
        
        // 4. 调用 LLM 生成回复
        String response = llmService.generate(history, context.orElse(""));
        
        // 5. 保存 Agent 回复
        shortTermMemory.append(runId, MessageRole.ASSISTANT, response, null);
        
        // 6. 更新工作记忆
        workingMemory.put(runId, "last_topic", extractTopic(response));
        
        return response;
    }
    
    public void cleanupRun(String runId) {
        shortTermMemory.clear(runId);
        workingMemory.clear(runId);
    }
}
```

### Workflow 使用 Memory

```java
@Service
@RequiredArgsConstructor
public class WorkflowExecutor {
    
    private final WorkingMemory workingMemory;
    
    public void executeWorkflow(String runId, WorkflowDefinition workflow) {
        // 1. 保存执行计划
        workingMemory.put(runId, "workflow_plan", 
            serialize(workflow.getSteps()));
        
        for (WorkflowStep step : workflow.getSteps()) {
            // 2. 执行步骤
            StepResult result = executeStep(step);
            
            // 3. 保存中间结果
            workingMemory.put(runId, 
                "step_" + step.getId() + "_result", 
                serialize(result));
        }
        
        // 4. 读取所有中间结果
        Map<String, String> allResults = workingMemory.getAll(runId);
        
        // 5. 生成最终输出
        generateFinalOutput(allResults);
        
        // 6. 清理
        workingMemory.clear(runId);
    }
}
```

---

## 性能考虑

### 滑动窗口优化

```java
// 场景：对话有 1000 条消息，只需要最近 10 条
public List<ShortTermMessage> getWindow(String runId, int windowSize) {
    int total = count(runId);  // 1000
    int offset = Math.max(0, total - windowSize);  // 990
    
    // 只查询 idx >= 990 的消息，避免全表扫描
    return mapper.selectList(
        Wrappers.<ShortTermMessage>lambdaQuery()
            .eq(ShortTermMessage::getRunId, runId)
            .ge(ShortTermMessage::getIdx, offset)  // 关键优化
            .orderByAsc(ShortTermMessage::getIdx)
    );
}
```

### 索引策略

```yaml
# 复合唯一索引：确保 (run_id, idx) 唯一
- createIndex:
    indexName: idx_stm_run_idx
    unique: true
    columns:
      - run_id
      - idx

# 好处：
# 1. 防止重复插入
# 2. 加速 WHERE run_id = ? AND idx >= ? 查询
# 3. 支持 ORDER BY idx
```

---

## 与其他模块的集成

```
┌──────────────────────────────────────────────────────┐
│              Agent / Workflow Layer                   │
│  - ChatAgent                                          │
│  - WorkflowExecutor                                   │
│  - ToolInvoker                                        │
└──────────────┬───────────────────────────────────────┘
               │ @Autowired
               ▼
┌──────────────────────────────────────────────────────┐
│              Memory Layer (B3)                        │
│  - ShortTermMemoryImpl                                │
│  - WorkingMemoryImpl                                  │
│  - LongTermMemory (no-op)                             │
└──────────────┬───────────────────────────────────────┘
               │ MyBatis-Plus
               ▼
┌──────────────────────────────────────────────────────┐
│              Persistence Layer                        │
│  - memory_short_term (MySQL)                          │
│  - memory_working (MySQL)                             │
│  - Redis Cache (optional)                             │
└──────────────────────────────────────────────────────┘
```

---

## 测试策略

### 单元测试（已实现）

```java
// ShortTermMemoryImplTest
@Test
void appendInsertsWithCorrectIdx() {
    // 验证 idx 自增逻辑
}

@Test
void getWindowReturnsLastN() {
    // 验证滑动窗口正确性
}

// WorkingMemoryImplTest
@Test
void putInsertsNewEntry() {
    // 验证插入
}

@Test
void putUpdatesExistingEntry() {
    // 验证 Upsert
}
```

### 集成测试（建议补充）

```java
@SpringBootTest
class MemoryIntegrationTest {
    
    @Test
    void multiRoundConversation() {
        // 测试完整的多轮对话场景
        String runId = UUID.randomUUID().toString();
        
        // Round 1
        shortTermMemory.append(runId, USER, "Hello");
        shortTermMemory.append(runId, ASSISTANT, "Hi there");
        
        // Round 2
        shortTermMemory.append(runId, USER, "How are you?");
        shortTermMemory.append(runId, ASSISTANT, "I'm fine");
        
        // 验证窗口
        List<ShortTermMessage> window = shortTermMemory.getWindow(runId, 3);
        assertThat(window).hasSize(3);
        assertThat(window.get(0).getContent()).isEqualTo("Hi there");
    }
    
    @Test
    void workflowStateManagement() {
        // 测试 Workflow 的状态管理
        String runId = UUID.randomUUID().toString();
        
        workingMemory.put(runId, "step1", "result1");
        workingMemory.put(runId, "step2", "result2");
        
        Map<String, String> all = workingMemory.getAll(runId);
        assertThat(all).hasSize(2);
    }
}
```

---

## 未来扩展方向

### Redis 缓存集成

```java
@Service
@RequiredArgsConstructor
public class CachedShortTermMemoryImpl implements ShortTermMemory {
    
    private final ShortTermMessageMapper mapper;
    private final RedisTemplate<String, List<ShortTermMessage>> redis;
    
    @Override
    public List<ShortTermMessage> getWindow(String runId, int windowSize) {
        String cacheKey = "stm:" + runId + ":window:" + windowSize;
        
        // 1. 尝试从 Redis 读取
        List<ShortTermMessage> cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // 2. 从 DB 读取
        List<ShortTermMessage> messages = queryFromDB(runId, windowSize);
        
        // 3. 写入 Redis（TTL = 5分钟）
        redis.opsForValue().set(cacheKey, messages, Duration.ofMinutes(5));
        
        return messages;
    }
}
```

### LongTermMemory 向量化

```java
public interface LongTermMemory {
    
    // Phase 2 实现
    void store(String workspaceId, String content, Map<String, Object> metadata);
    
    List<MemorySearchResult> recall(String workspaceId, String query, int topK);
    
    // 底层可能使用：
    // - Elasticsearch
    // - Milvus
    // - Pinecone
    // - pgvector
}
```

---

## 总结

B3 Memory Store 是一个精心设计的记忆管理子系统，具有以下优势：

1. **清晰的职责分离**：短期、工作、长期记忆各司其职
2. **高效的数据结构**：idx 保证顺序，KV 灵活存储
3. **良好的隔离性**：按 run_id 隔离，避免数据泄漏
4. **易于扩展**：预留 Redis 缓存和向量化接口
5. **生产就绪**：完整的测试覆盖和事务保证

这个设计非常适合 Agent 系统的实时交互场景，兼顾了性能、可靠性和可维护性。

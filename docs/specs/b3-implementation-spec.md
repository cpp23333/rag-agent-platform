# B3 MemoryStore 实施规范

## 目标
实施 B3 MemoryStore 子系统，为 Agent 和 Workflow 提供记忆管理能力。

## 当前状态
已完成：
- 数据库迁移文件：008-memory-short-term.yaml, 009-memory-working.yaml
- 实体类：MessageRole.java, ShortTermMessage.java, WorkingMemoryEntry.java
- Mapper接口：ShortTermMessageMapper.java, WorkingMemoryEntryMapper.java
- 空实现：ShortTermMemory.java (接口), ShortTermMemoryImpl.java (抛出异常)
- 测试文件：ShortTermMemoryImplTest.java (已创建)

## 待实施任务

### 1. 修复现有编译错误
在实施B3之前，需要修复 WorkspaceFilterInterceptorTest.java 中的编译错误：
- 找不到方法 setWorkspaceId(String)
- MappedStatement mock 类型推断问题

### 2. TDD实施 ShortTermMemory
按照TDD流程：
1. **RED**: 运行 ShortTermMemoryImplTest，确认测试失败
2. **GREEN**: 实现 ShortTermMemoryImpl 中的方法，让测试通过
3. **REFACTOR**: 如需要，重构代码

实现要求：
- 使用 MyBatis-Plus 的 Wrappers 进行查询
- 使用 @Transactional 注解
- append() 方法：计算 nextIdx，插入消息
- getWindow() 方法：返回最后 N 条消息
- getAll() 方法：返回所有消息
- count() 方法：返回消息数量
- clear() 方法：删除所有消息

### 3. TDD实施 WorkingMemory
创建测试文件 WorkingMemoryImplTest.java，然后按照TDD流程实施：
1. **RED**: 写测试，看它失败
2. **GREEN**: 实现代码，让测试通过
3. **REFACTOR**: 重构

实现要求：
- put() 方法：upsert 语义（存在则更新，不存在则插入）
- get() 方法：返回 Optional<String>
- getAll() 方法：返回 Map<String, String>
- remove() 方法：删除指定 key
- clear() 方法：删除所有条目

### 4. 创建 LongTermMemory 接口
创建占位接口，一期不实现，方法返回空或no-op。

### 5. 验证
运行以下命令验证：
```bash
mvn -pl platform-core -am compile
mvn -pl platform-core -Dtest=ShortTermMemoryImplTest,WorkingMemoryImplTest test
```

## 验收标准
1. 所有编译错误已修复
2. ShortTermMemoryImplTest 所有测试通过
3. WorkingMemoryImplTest 所有测试通过
4. 现有测试不被破坏
5. 代码遵循项目规范（使用 Lombok, MyBatis-Plus, Spring事务）

## 参考文件
- 规范文档：docs/specs/b3-memory-store.md
- 现有实现示例：platform-core/src/main/java/io/kyligence/ragagent/core/tracing/TracingServiceImpl.java

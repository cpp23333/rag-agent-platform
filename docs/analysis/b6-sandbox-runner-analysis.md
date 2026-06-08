# SandboxRunner 功能分析与生产调用链

> 对应 spec：`docs/specs/b6-sandbox-runner.md`
> 写于 2026-06-08

## 一、定位与职责

SandboxRunner 是 Platform Core 的代码沙箱，用于安全执行 Workflow 中的 `code` 节点。基于 Janino（Java 子集编译器），通过白名单机制和硬超时限制，防止用户脚本执行危险操作（IO、反射、网络、无限循环），为 Workflow 编排提供可信的脚本运行环境。

## 二、核心能力

1. **编译并执行 Janino Java 子集脚本** — 动态编译用户代码，执行返回结果
2. **白名单安全校验** — 允许 `java.lang.*` / `java.util.*` / `java.time.*` / `java.math.*`，禁止 IO、反射、自定义类加载
3. **编译缓存** — 按 source SHA-256 缓存已编译脚本，避免重复编译
4. **硬超时中断** — 单次执行默认 5s 超时，超时强制取消线程
5. **输入输出封装** — 接收 `Map<String, Object>` 输入，返回 `Object` 结果

## 三、对外接口与数据契约

### Java 接口

```java
@Component
public class SandboxRunner {
    // 默认 5s 超时
    Object run(String source, Map<String, Object> input);
    
    // 自定义超时
    Object run(String source, Map<String, Object> input, Duration timeout);
}
```

### 输入参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `source` | String | Janino Java 代码片段，必须 return Object |
| `input` | Map<String, Object> | 脚本内可通过隐式变量 `input` 访问 |
| `timeout` | Duration | 超时时长，超过则强制中断 |

### 脚本示例

```java
// 用户脚本（Janino 格式）
return (Integer) input.get("x") + (Integer) input.get("y");

// 调用方式
Object result = sandboxRunner.run(script, Map.of("x", 3, "y", 4));
// result = 7
```

### 安全约束

**允许的包：**
- `java.lang.*`（除 Runtime / ProcessBuilder / ClassLoader / Thread）
- `java.util.*`
- `java.time.*`
- `java.math.*`

**禁止的操作：**
- IO：`java.io.*` / `java.nio.*`
- 网络：`java.net.*`
- 反射：`Class.forName` / `getDeclaredMethod` / `invoke`
- 系统调用：`System.exit` / `Runtime.exec`

## 四、生产环境真实调用链

### 场景：Workflow code 节点执行数据转换脚本

```
┌──────────────────────────────────────────────────────────────┐
│ Workflow Executor: code 节点触发                              │
│   Node config: {                                             │
│     type: "code",                                            │
│     source: "return input.get('amount') * 1.17;",           │
│     input: { amount: 100 }                                   │
│   }                                                          │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ① WorkflowService.executeStep()                              │
│    → 识别 step.type == CODE                                   │
│    → sandboxRunner.run(source, input)                        │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ② SandboxSecurityManager.validate(source)                    │
│    → Pattern matching 扫描代码                                │
│    → 检查禁止的 import（java.io / java.net / reflect）        │
│    → 检查危险调用（Class.forName / System.exit）              │
│    → 通过校验                                                 │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ③ CompiledScriptCache.get(source, factory)                   │
│    → key = sha256(source)                                    │
│         = "a3f9b2..."                                        │
│    → cache.get(key) → MISS（首次调用）                        │
│    → factory.compile(source):                                │
│       ScriptEvaluator se = new ScriptEvaluator();            │
│       se.setParameters(["input"], [Map.class]);              │
│       se.setReturnType(Object.class);                        │
│       se.cook(source);  ← Janino 编译为字节码                 │
│    → cache.put(key, se)                                      │
│    → return se                                               │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ④ ExecutorService.submit(() -> execute(source, input))       │
│    → 提交到 daemon thread pool                                │
│    → Future<Object> future                                   │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑤ 沙箱线程执行                                                │
│    ScriptEvaluator.evaluate([input])                         │
│      → 执行字节码：                                           │
│         input.get("amount") → 100                            │
│         100 * 1.17 → 117.0                                   │
│      → return 117.0                                          │
│    执行耗时：12ms                                             │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑥ Future.get(5000, MILLISECONDS)                             │
│    → 等待沙箱线程返回（12ms < 5s）                            │
│    → 返回结果 117.0                                           │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑦ WorkflowService 处理返回值                                  │
│    → 存入 workingMemory("code_output", 117.0)                │
│    → 流转到下一个节点                                          │
└──────────────────────────────────────────────────────────────┘
```

### 失败场景：超时中断

```
┌──────────────────────────────────────────────────────────────┐
│ 用户脚本包含无限循环：                                         │
│   "while(true) {} return null;"                              │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ① validate() 通过（未检测到语法违规）                          │
│ ② cache.get() 编译成功（Janino 允许 while）                   │
│ ③ ExecutorService.submit() 提交执行                           │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ④ 沙箱线程进入无限循环                                         │
│    while(true) { /* 死循环 */ }                              │
│    耗时累计：5000ms...                                        │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑤ Future.get(5000, MILLISECONDS) 超时                         │
│    → 抛出 TimeoutException                                   │
│    → future.cancel(true)  ← 中断沙箱线程                      │
│    → throw PlatformException(SANDBOX_TIMEOUT,                │
│         "Script execution timed out after 5s")               │
└───────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑥ WorkflowService 捕获异常                                    │
│    → 标记 step 为 FAILED                                      │
│    → 记录错误日志                                             │
│    → Workflow 进入 failure handler                           │
└──────────────────────────────────────────────────────────────┘
```

## 五、与其他子系统的协作

| 依赖方 | 用途 | 集成点 |
|--------|------|--------|
| **Workflow 模块** | code 节点执行 | WorkflowExecutor → SandboxRunner.run() |
| **Tracing** | 记录执行耗时和失败 | TracingService.addStep(type=CODE, latency, error) |
| **ErrorCode** | 统一异常码 | SANDBOX_SECURITY_VIOLATION / SANDBOX_TIMEOUT |

```
┌─────────────────┐
│ Workflow Module │  (业务编排层)
└────────┬────────┘
         │ @Autowired
         ▼
┌─────────────────┐
│ SandboxRunner   │  (沙箱执行层)
└────────┬────────┘
         │ Janino
         ▼
┌─────────────────┐
│ JVM Bytecode    │  (字节码运行时)
└─────────────────┘
```

## 六、关键设计权衡

1. **AST 扫描用 Pattern Matching 而非完整解析** — 威胁模型是可信用户误操作（非对抗性攻击），简单正则足够，避免引入重量级 AST 解析器。

2. **ExecutorService 而非 VirtualThread** — Java 17 虚拟线程未正式 GA，daemon thread pool 更稳定，且沙箱执行并发度低（Workflow 步骤串行）。

3. **ScriptEvaluator 按 SHA-256 缓存** — 相同脚本多次调用无需重编译（Workflow 模板复用率高），缓存击中率预计 >70%。

4. **不限制 java.lang.* 整体** — Janino 编译器隐式依赖 java.lang 核心类（String / Integer / Exception），只屏蔽危险子集（Runtime / ClassLoader）。

5. **超时用 Future.cancel(true)** — 强制中断沙箱线程，阻塞操作可被 InterruptedException 打断，无限循环靠线程 interrupt 信号退出（非 100% 保证但足够）。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **沙箱隔离** | 编译期 | SandboxSecurityManager 白名单校验 |
| **超时控制** | 执行期 | ExecutorService + Future.get(timeout) |
| **异常处理** | 全链路 | PlatformException 统一封装 |
| **追踪** | Workflow 层 | TracingService 记录 code 节点耗时（SandboxRunner 本身不打 trace） |

**注：** SandboxRunner 不直接调用 Tracing，由调用方（Workflow）负责记录执行步骤。

## 八、未来扩展点

1. **更细粒度的白名单** — 支持配置化允许特定类（如 `java.net.URL` 但禁止 `URLConnection.openConnection`）。

2. **沙箱资源配额** — 限制脚本内存占用（通过自定义 ClassLoader + Instrumentation）。

3. **多语言沙箱** — 支持 JavaScript (GraalVM.js) / Python (Jython) 脚本，统一 Sandbox 接口。

4. **编译优化** — Janino 编译耗时约 20-50ms，对热路径脚本可预编译并序列化字节码。

5. **审计日志** — 记录所有沙箱执行的 source code SHA-256、输入参数范围（不记录敏感值）、结果类型。

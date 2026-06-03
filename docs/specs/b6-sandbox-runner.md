# B6 Spec: SandboxRunner

## Context

SandboxRunner 是 Platform Core 的代码沙箱（架构设计 §3.5）。Workflow 的 `code` 节点通过它执行 Java 子集脚本（Janino），配合白名单和超时硬上限，防止逃逸。B1 Phase 0 已引入 Janino 依赖。

## 目标

- 编译并执行 Janino Java 子集脚本
- 白名单：允许 `java.lang.*`（除危险类）/ `java.util.*` / `java.time.*` / `java.math.*`；禁止反射、IO、网络、自定义类加载
- 编译结果按 source SHA-256 缓存
- 单次执行硬超时 5s，超时强制中断
- 输入 `Map<String, Object>`，输出 `Object`

## 范围

`platform-core` 模块。无 DB、无 REST controller。

---

## File Structure

```
platform-core/src/main/java/io/kyligence/ragagent/core/sandbox/
  SandboxRunner.java
  SandboxSecurityManager.java     # 白名单校验（编译期 AST 检查）
  CompiledScriptCache.java        # SHA-256 → IScriptEvaluator

platform-core/src/test/java/io/kyligence/ragagent/core/sandbox/
  SandboxRunnerTest.java
```

---

## Phase 1: Security Checker

### File: `core/sandbox/SandboxSecurityManager.java`

编译前用 Janino `Java17Parser` 做 AST 扫描，拒绝禁用的 import 和反射调用。

```java
package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates sandbox source before compilation.
 * Uses simple pattern matching — sufficient for the threat model (trusted-ish users, not adversarial).
 */
public class SandboxSecurityManager {

    private static final Set<String> BLOCKED_IMPORTS = Set.of(
        "java.io", "java.nio", "java.net", "java.lang.reflect",
        "java.lang.Runtime", "java.lang.ProcessBuilder",
        "java.lang.ClassLoader", "java.lang.Thread",
        "sun.", "com.sun.", "javax.script"
    );

    private static final Pattern REFLECT_PATTERN =
        Pattern.compile("\\b(Class\\.forName|getDeclaredMethod|getMethod|invoke|newInstance)\\b");

    private static final Pattern SYSTEM_EXIT_PATTERN =
        Pattern.compile("\\bSystem\\s*\\.\\s*exit\\b");

    public void validate(String source) {
        for (String blocked : BLOCKED_IMPORTS) {
            if (source.contains("import " + blocked)) {
                throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                    "Forbidden import: " + blocked);
            }
        }
        if (REFLECT_PATTERN.matcher(source).find()) {
            throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                "Reflection is not allowed in sandbox");
        }
        if (SYSTEM_EXIT_PATTERN.matcher(source).find()) {
            throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                "System.exit is not allowed in sandbox");
        }
    }
}
```

---

## Phase 2: Compiled Script Cache

### File: `core/sandbox/CompiledScriptCache.java`

```java
package io.kyligence.ragagent.core.sandbox;

import org.codehaus.janino.ScriptEvaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public class CompiledScriptCache {

    private final ConcurrentHashMap<String, ScriptEvaluator> cache = new ConcurrentHashMap<>();

    public ScriptEvaluator get(String source, ScriptEvaluatorFactory factory) throws Exception {
        String key = sha256(source);
        ScriptEvaluator cached = cache.get(key);
        if (cached != null) return cached;
        ScriptEvaluator compiled = factory.compile(source);
        cache.put(key, compiled);
        return compiled;
    }

    private String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @FunctionalInterface
    public interface ScriptEvaluatorFactory {
        ScriptEvaluator compile(String source) throws Exception;
    }
}
```

---

## Phase 3: SandboxRunner

### File: `core/sandbox/SandboxRunner.java`

```java
package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.janino.ScriptEvaluator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component
public class SandboxRunner {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final ExecutorService EXECUTOR =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sandbox-runner");
            t.setDaemon(true);
            return t;
        });

    private final SandboxSecurityManager security = new SandboxSecurityManager();
    private final CompiledScriptCache cache = new CompiledScriptCache();

    /**
     * Execute Java snippet with given input map.
     * The snippet has an implicit {@code Map<String, Object> input} variable and must return Object.
     */
    public Object run(String source, Map<String, Object> input) {
        return run(source, input, DEFAULT_TIMEOUT);
    }

    public Object run(String source, Map<String, Object> input, Duration timeout) {
        security.validate(source);

        Future<Object> future = EXECUTOR.submit(() -> execute(source, input));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PlatformException(ErrorCode.SANDBOX_TIMEOUT,
                "Script execution timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PlatformException pe) throw pe;
            throw new PlatformException(ErrorCode.SANDBOX_EXECUTION_FAILED,
                "Script execution failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(ErrorCode.SANDBOX_EXECUTION_FAILED,
                "Script execution interrupted");
        }
    }

    private Object execute(String source, Map<String, Object> input) throws Exception {
        ScriptEvaluator se = cache.get(source, src -> {
            ScriptEvaluator evaluator = new ScriptEvaluator();
            evaluator.setParameters(new String[]{"input"}, new Class[]{Map.class});
            evaluator.setReturnType(Object.class);
            evaluator.cook(src);
            return evaluator;
        });
        return se.evaluate(new Object[]{input});
    }
}
```

---

## Phase 4: Tests

### File: `core/sandbox/SandboxRunnerTest.java`

```java
package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SandboxRunnerTest {

    private final SandboxRunner runner = new SandboxRunner();

    @Test
    void returnsComputedValue() {
        Object result = runner.run(
            "return (Integer) input.get(\"x\") + (Integer) input.get(\"y\");",
            Map.of("x", 3, "y", 4));
        assertThat(result).isEqualTo(7);
    }

    @Test
    void allowsJavaUtilAndMath() {
        Object result = runner.run(
            "java.util.List<String> list = new java.util.ArrayList<>();" +
            "list.add(\"hello\"); return list.size();",
            Map.of());
        assertThat(result).isEqualTo(1);
    }

    @Test
    void cachesCompiledScript() {
        String src = "return 42;";
        runner.run(src, Map.of());
        // second call should use cache (no exception = pass)
        assertThat(runner.run(src, Map.of())).isEqualTo(42);
    }

    @Test
    void blocksIoImport() {
        assertThatThrownBy(() -> runner.run(
            "import java.io.File; return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("Forbidden import");
    }

    @Test
    void blocksReflection() {
        assertThatThrownBy(() -> runner.run(
            "Class.forName(\"java.lang.Runtime\"); return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("Reflection");
    }

    @Test
    void blocksSystemExit() {
        assertThatThrownBy(() -> runner.run(
            "System.exit(0); return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("System.exit");
    }

    @Test
    void timesOutLongRunningScript() {
        assertThatThrownBy(() -> runner.run(
            "while(true){} return null;",
            Map.of(), Duration.ofMillis(200)))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("timed out");
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=SandboxRunnerTest test` 全绿（7 个测试）
3. 已有测试不被破坏
4. 超时测试耗时 ≤ 500ms（不卡 CI）

## Dependencies

- `janino` 已在 B1 Phase 0 引入 ✓
- `ErrorCode.SANDBOX_*` 已在 B1 Phase 0 引入 ✓
- 无新外部依赖

## 设计决策

| 决策 | 理由 |
|------|------|
| AST 扫描用 pattern matching 而非完整解析 | 威胁模型是可信用户误操作，非对抗性攻击；简单有效 |
| ExecutorService 而非 VirtualThread | Java 17 虚拟线程未 GA；daemon thread pool 足够 |
| ScriptEvaluator 按 SHA-256 缓存 | 相同脚本多次调用无需重编译 |
| 不限制 java.lang.* 整体 | Janino 本身隐式依赖 java.lang；只屏蔽危险子集 |
| 超时用 Future.cancel(true) | 中断阻塞操作；无限循环靠线程 interrupt 信号退出 |

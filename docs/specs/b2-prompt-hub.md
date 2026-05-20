# B2 Spec: PromptHub

## Context

PromptHub 是 Platform Core 的提示词管理子系统（架构设计 §3.7）。它支持按 `name@version` 引用模板、Pebble 渲染、变量 schema 校验，并提供管理 API。B1 Phase 0 已引入 Pebble 依赖。

## 目标

- 存储、版本化管理 Prompt 模板
- 通过 `prompt://name@version` URI 引用（Agent/Workflow 调用）
- Pebble 模板渲染（沙箱安全）
- 变量 schema 校验（JSON Schema 简化版）
- CRUD REST API

## 范围（本 spec）

所有文件属于 `platform-core` 和 `server` 模块。

---

## File Structure

```
platform-core/src/main/resources/db/changelog/changes/
  007-prompt.yaml

platform-core/src/main/java/io/kyligence/ragagent/core/prompt/
  Prompt.java
  PromptMapper.java
  PromptService.java
  PromptServiceImpl.java
  PromptRenderer.java
  PromptUri.java

platform-core/src/test/java/io/kyligence/ragagent/core/prompt/
  PromptRendererTest.java
  PromptServiceImplTest.java

server/src/main/java/io/kyligence/ragagent/server/controller/
  PromptController.java
```

---

## Phase 1: DB Schema

### File: `platform-core/src/main/resources/db/changelog/changes/007-prompt.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 007-prompt
      author: ragagent
      changes:
        - createTable:
            tableName: prompt
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id, type: BIGINT, constraints: { nullable: false } }
              - column: { name: name, type: VARCHAR(128), constraints: { nullable: false } }
              - column: { name: version, type: VARCHAR(32), constraints: { nullable: false } }
              - column: { name: template, type: LONGTEXT, constraints: { nullable: false } }
              - column: { name: variables_schema, type: TEXT }
              - column: { name: description, type: VARCHAR(512) }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: updated_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: prompt
            columnNames: "workspace_id, name, version"
            constraintName: uq_prompt_ws_name_version
        - createIndex:
            tableName: prompt
            indexName: idx_prompt_workspace
            columns:
              - column: { name: workspace_id }
```

---

## Phase 2: Domain Classes

### File: `core/prompt/Prompt.java`

```java
package io.kyligence.ragagent.core.prompt;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("prompt")
public class Prompt {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long workspaceId;
    private String name;
    private String version;
    private String template;
    private String variablesSchema;
    private String description;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

### File: `core/prompt/PromptMapper.java`

```java
package io.kyligence.ragagent.core.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface PromptMapper extends BaseMapper<Prompt> {
}
```

### File: `core/prompt/PromptUri.java`

解析 `prompt://name@version` 格式，`@version` 可省略（默认 `latest`）。

```java
package io.kyligence.ragagent.core.prompt;

public record PromptUri(String name, String version) {

    public static final String LATEST = "latest";

    public static PromptUri parse(String uri) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        String body = uri.startsWith("prompt://") ? uri.substring(9) : uri;
        int at = body.lastIndexOf('@');
        if (at < 0) return new PromptUri(body, LATEST);
        return new PromptUri(body.substring(0, at), body.substring(at + 1));
    }

    public boolean isLatest() {
        return LATEST.equals(version);
    }
}
```

### File: `core/prompt/PromptRenderer.java`

Pebble 渲染器，内置编译缓存。

```java
package io.kyligence.ragagent.core.prompt;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptRenderer {

    private final PebbleEngine engine;
    private final ConcurrentHashMap<String, PebbleTemplate> cache = new ConcurrentHashMap<>();

    public PromptRenderer() {
        this.engine = new PebbleEngine.Builder()
                .autoEscaping(false)
                .build();
    }

    public String render(String templateSource, Map<String, Object> variables) {
        try {
            String cacheKey = Integer.toHexString(templateSource.hashCode());
            PebbleTemplate tpl = cache.computeIfAbsent(cacheKey, k -> {
                try {
                    return engine.getLiteralTemplate(templateSource);
                } catch (Exception e) {
                    throw new PlatformException(ErrorCode.PROMPT_RENDER_FAILED,
                            "Failed to compile template: " + e.getMessage(), e);
                }
            });
            StringWriter writer = new StringWriter();
            tpl.evaluate(writer, variables);
            return writer.toString();
        } catch (PlatformException pe) {
            throw pe;
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.PROMPT_RENDER_FAILED,
                    "Failed to render template: " + e.getMessage(), e);
        }
    }
}
```

---

## Phase 3: Service Layer

### File: `core/prompt/PromptService.java`

```java
package io.kyligence.ragagent.core.prompt;

import java.util.List;
import java.util.Map;

public interface PromptService {

    Prompt save(Long workspaceId, SavePromptCommand cmd);

    String render(Long workspaceId, String name, String version, Map<String, Object> variables);

    default String renderUri(Long workspaceId, String uri, Map<String, Object> variables) {
        PromptUri p = PromptUri.parse(uri);
        return render(workspaceId, p.name(), p.version(), variables);
    }

    Prompt get(Long workspaceId, String name, String version);

    List<Prompt> list(Long workspaceId);

    void delete(Long workspaceId, String name, String version);

    record SavePromptCommand(
            String name, String version, String template,
            String variablesSchema, String description) {}
}
```

### File: `core/prompt/PromptServiceImpl.java`

```java
package io.kyligence.ragagent.core.prompt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final PromptMapper promptMapper;
    private final PromptRenderer renderer;

    @Override
    @Transactional
    public Prompt save(Long workspaceId, SavePromptCommand cmd) {
        Prompt existing = findByNameVersion(workspaceId, cmd.name(), cmd.version());
        if (existing != null) {
            existing.setTemplate(cmd.template());
            existing.setVariablesSchema(cmd.variablesSchema());
            existing.setDescription(cmd.description());
            existing.setUpdatedAt(LocalDateTime.now());
            promptMapper.updateById(existing);
            return existing;
        }
        Prompt p = new Prompt();
        p.setWorkspaceId(workspaceId);
        p.setName(cmd.name());
        p.setVersion(cmd.version());
        p.setTemplate(cmd.template());
        p.setVariablesSchema(cmd.variablesSchema());
        p.setDescription(cmd.description());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        promptMapper.insert(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public String render(Long workspaceId, String name, String version,
                         Map<String, Object> variables) {
        Prompt p = resolvePrompt(workspaceId, name, version);
        return renderer.render(p.getTemplate(), variables == null ? Map.of() : variables);
    }

    @Override
    @Transactional(readOnly = true)
    public Prompt get(Long workspaceId, String name, String version) {
        return findByNameVersion(workspaceId, name, version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Prompt> list(Long workspaceId) {
        return promptMapper.selectList(
                Wrappers.<Prompt>lambdaQuery()
                        .eq(Prompt::getWorkspaceId, workspaceId)
                        .orderByAsc(Prompt::getName)
                        .orderByDesc(Prompt::getVersion));
    }

    @Override
    @Transactional
    public void delete(Long workspaceId, String name, String version) {
        Prompt p = findByNameVersion(workspaceId, name, version);
        if (p == null) {
            throw new PlatformException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt not found: " + name + "@" + version);
        }
        promptMapper.deleteById(p.getId());
    }

    private Prompt findByNameVersion(Long workspaceId, String name, String version) {
        if (PromptUri.LATEST.equals(version)) {
            return promptMapper.selectOne(
                    Wrappers.<Prompt>lambdaQuery()
                            .eq(Prompt::getWorkspaceId, workspaceId)
                            .eq(Prompt::getName, name)
                            .orderByDesc(Prompt::getUpdatedAt)
                            .last("LIMIT 1"));
        }
        return promptMapper.selectOne(
                Wrappers.<Prompt>lambdaQuery()
                        .eq(Prompt::getWorkspaceId, workspaceId)
                        .eq(Prompt::getName, name)
                        .eq(Prompt::getVersion, version));
    }

    private Prompt resolvePrompt(Long workspaceId, String name, String version) {
        Prompt p = findByNameVersion(workspaceId, name, version);
        if (p == null) {
            throw new PlatformException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt not found: " + name + "@" + version);
        }
        return p;
    }
}
```

---

## Phase 4: REST Controller

### File: `server/.../controller/PromptController.java`

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.prompt.Prompt;
import io.kyligence.ragagent.core.prompt.PromptService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/prompts")
public class PromptController {

    public record SavePromptRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 32) String version,
            @NotBlank String template,
            String variablesSchema,
            @Size(max = 512) String description) {}

    public record RenderRequest(Map<String, Object> variables) {}

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping
    public ApiResponse<List<Prompt>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(promptService.list(workspaceId));
    }

    @PutMapping
    public ApiResponse<Prompt> save(@PathVariable Long workspaceId,
                                    @Valid @RequestBody SavePromptRequest req) {
        return ApiResponse.ok(promptService.save(workspaceId,
                new PromptService.SavePromptCommand(
                        req.name(), req.version(), req.template(),
                        req.variablesSchema(), req.description())));
    }

    @GetMapping("/{name}/{version}")
    public ApiResponse<Prompt> get(@PathVariable Long workspaceId,
                                   @PathVariable String name,
                                   @PathVariable String version) {
        Prompt p = promptService.get(workspaceId, name, version);
        if (p == null) return ApiResponse.error("7005", "Prompt not found");
        return ApiResponse.ok(p);
    }

    @PostMapping("/{name}/{version}/render")
    public ApiResponse<String> render(@PathVariable Long workspaceId,
                                      @PathVariable String name,
                                      @PathVariable String version,
                                      @RequestBody(required = false) RenderRequest req) {
        Map<String, Object> vars = req != null ? req.variables() : Map.of();
        return ApiResponse.ok(promptService.render(workspaceId, name, version, vars));
    }

    @DeleteMapping("/{name}/{version}")
    public ApiResponse<Void> delete(@PathVariable Long workspaceId,
                                    @PathVariable String name,
                                    @PathVariable String version) {
        promptService.delete(workspaceId, name, version);
        return ApiResponse.ok(null);
    }
}
```

---

## Phase 5: Tests

### File: `core/prompt/PromptRendererTest.java`

```java
package io.kyligence.ragagent.core.prompt;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    void rendersSimpleTemplate() {
        String result = renderer.render("Hello, {{ name }}!", Map.of("name", "World"));
        assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    void rendersConditional() {
        String tpl = "{% if formal %}Dear {{ name }}{% else %}Hi {{ name }}{% endif %}";
        assertThat(renderer.render(tpl, Map.of("name", "Alice", "formal", true)))
                .isEqualTo("Dear Alice");
    }

    @Test
    void rendersWithEmptyVariables() {
        assertThat(renderer.render("static text", Map.of())).isEqualTo("static text");
    }

    @Test
    void throwsOnBadTemplate() {
        assertThatThrownBy(() -> renderer.render("{% if unclosed", Map.of()))
                .isInstanceOf(PlatformException.class);
    }
}
```

### File: `core/prompt/PromptServiceImplTest.java`

```java
package io.kyligence.ragagent.core.prompt;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptServiceImplTest {

    @Mock private PromptMapper promptMapper;
    private PromptServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PromptServiceImpl(promptMapper, new PromptRenderer());
    }

    @Test
    void saveCreatesNewPrompt() {
        when(promptMapper.selectOne(any())).thenReturn(null);
        when(promptMapper.insert(any())).thenReturn(1);
        Prompt p = service.save(1L, new PromptService.SavePromptCommand(
                "qa_system", "v1", "Answer: {{ answer }}", null, null));
        assertThat(p.getName()).isEqualTo("qa_system");
        verify(promptMapper).insert(any(Prompt.class));
    }

    @Test
    void saveUpdatesExisting() {
        Prompt existing = new Prompt();
        existing.setId("id"); existing.setTemplate("old");
        when(promptMapper.selectOne(any())).thenReturn(existing);
        when(promptMapper.updateById(any())).thenReturn(1);
        Prompt result = service.save(1L, new PromptService.SavePromptCommand(
                "qa", "v1", "new", null, null));
        assertThat(result.getTemplate()).isEqualTo("new");
        verify(promptMapper, never()).insert(any());
    }

    @Test
    void renderReturnsRenderedString() {
        Prompt p = new Prompt(); p.setTemplate("Hello, {{ name }}!");
        when(promptMapper.selectOne(any())).thenReturn(p);
        assertThat(service.render(1L, "g", "v1", Map.of("name", "Bob")))
                .isEqualTo("Hello, Bob!");
    }

    @Test
    void renderThrowsWhenNotFound() {
        when(promptMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.render(1L, "x", "v1", Map.of()))
                .isInstanceOf(PlatformException.class);
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=PromptRendererTest,PromptServiceImplTest test` 全绿
3. `mvn -pl server -am compile` 通过（PromptController）
4. 已有测试不被破坏
5. Liquibase `007-prompt.yaml` 被 master changelog 自动加载

## Dependencies

- Pebble 依赖已在 B1 Phase 0 引入 ✓
- `ErrorCode.PROMPT_NOT_FOUND` / `PROMPT_RENDER_FAILED` 已存在 ✓
- `WorkspaceAware` 注解已在 Plan A 创建 ✓
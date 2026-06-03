package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ApplicationContext ctx;
    private final ToolDefMapper toolDefMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ToolRegistryImpl(ApplicationContext ctx, ToolDefMapper toolDefMapper,
                            RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.ctx = ctx;
        this.toolDefMapper = toolDefMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void autoRegister() {
        ctx.getBeansOfType(JavaBeanTool.class).values().forEach(t -> {
            tools.put(t.name(), t);
            log.info("Registered JavaBeanTool: {}", t.name());
        });
    }

    public void loadWorkspaceTools(Long workspaceId) {
        List<ToolDef> defs = toolDefMapper.selectList(
            Wrappers.<ToolDef>lambdaQuery()
                .eq(ToolDef::getWorkspaceId, workspaceId)
                .eq(ToolDef::getEnabled, true)
                .eq(ToolDef::getImplType, ToolImplType.HTTP));
        defs.forEach(def -> {
            tools.put(def.getName(), new HttpTool(def, restTemplate, objectMapper));
            log.info("Loaded HTTP tool: {}", def.getName());
        });
    }

    @Override
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public ToolResult invoke(String name, ToolInput input, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new PlatformException(ErrorCode.TOOL_NOT_FOUND, "Tool not found: " + name);
        }
        return tool.invoke(input, ctx);
    }

    @Override
    public List<ToolSummary> list() {
        return tools.values().stream()
            .map(t -> new ToolSummary(t.name(), t.description(), t.inputSchemaJson()))
            .toList();
    }
}

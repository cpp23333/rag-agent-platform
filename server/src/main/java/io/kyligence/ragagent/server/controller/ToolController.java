package io.kyligence.ragagent.server.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.core.tool.ToolDef;
import io.kyligence.ragagent.core.tool.ToolDefMapper;
import io.kyligence.ragagent.core.tool.ToolImplType;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/tools")
@RequiredArgsConstructor
public class ToolController {

    public record CreateToolRequest(
            @NotBlank String name,
            String description,
            @NotBlank String implType,
            String inputSchemaJson,
            String configJson) {}

    private final ToolDefMapper toolDefMapper;

    @GetMapping
    public ApiResponse<List<ToolDef>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(toolDefMapper.selectList(
            Wrappers.<ToolDef>lambdaQuery()
                .eq(ToolDef::getWorkspaceId, workspaceId)));
    }

    @PostMapping
    public ApiResponse<ToolDef> create(@PathVariable Long workspaceId,
                                       @Valid @RequestBody CreateToolRequest req) {
        ToolDef def = new ToolDef();
        def.setWorkspaceId(workspaceId);
        def.setName(req.name());
        def.setDescription(req.description());
        def.setImplType(ToolImplType.valueOf(req.implType()));
        def.setInputSchemaJson(req.inputSchemaJson());
        def.setConfigJson(req.configJson());
        def.setEnabled(true);
        def.setCreatedAt(LocalDateTime.now());
        def.setUpdatedAt(LocalDateTime.now());
        toolDefMapper.insert(def);
        return ApiResponse.ok(def);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        toolDefMapper.deleteById(id);
        return ApiResponse.ok(null);
    }
}

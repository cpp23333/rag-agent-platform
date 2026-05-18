package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.auth.WorkspaceService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workspace 管理接口
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    public record CreateRequest(@NotBlank @Size(max = 128) String name) {}

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceService.WorkspaceView>> list() {
        String userId = currentUserId();
        return ApiResponse.ok(workspaceService.listForUser(userId));
    }

    @PostMapping
    public ApiResponse<WorkspaceService.WorkspaceView> create(@Valid @RequestBody CreateRequest req) {
        String userId = currentUserId();
        return ApiResponse.ok(workspaceService.create(userId, req.name()));
    }

    private String currentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

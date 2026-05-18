package io.kyligence.ragagent.core.tenant;

import io.kyligence.ragagent.core.auth.Role;

/**
 * Workspace 上下文（不可变）
 *
 * @param workspaceId 当前 workspace ID
 * @param userId      当前用户 ID
 * @param role        当前用户在 workspace 中的角色
 */
public record WorkspaceContext(
        String workspaceId,
        String userId,
        Role role
) {
}

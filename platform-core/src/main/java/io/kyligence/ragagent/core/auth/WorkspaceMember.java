package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workspace 成员关系
 */
@Data
@TableName("workspace_member")
public class WorkspaceMember {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String userId;

    @TableField(value = "role")
    private Role role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Boolean deleted;
}

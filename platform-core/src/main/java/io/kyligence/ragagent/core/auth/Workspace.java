package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workspace 实体
 */
@Data
@TableName("workspace")
public class Workspace {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    private String ownerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}

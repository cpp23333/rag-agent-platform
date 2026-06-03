package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tool_def")
public class ToolDef {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long workspaceId;
    private String name;
    private String description;
    private ToolImplType implType;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String configJson;
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

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

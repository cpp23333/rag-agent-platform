package io.kyligence.ragagent.core.model;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("model_provider")
public class ModelProvider {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long workspaceId;
    private String name;
    private String type;
    private String baseUrl;
    private String apiKeyEncrypted;
    private String modelsJson;
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

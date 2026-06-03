package io.kyligence.ragagent.core.event;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("platform_event")
public class PlatformEvent {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private EventType eventType;
    private String aggregateId;
    private String workspaceId;
    private String payloadJson;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}

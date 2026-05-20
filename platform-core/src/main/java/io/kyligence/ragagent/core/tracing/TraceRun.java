package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("trace_run")
public class TraceRun {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private RunType type;
    private RunStatus status;
    private String parentRunId;
    private String metadataJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}

package io.kyligence.ragagent.rag.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ingest_job")
public class IngestJob {
    @TableId(type = IdType.ASSIGN_UUID) private String id;
    private String kbId;
    private Long workspaceId;
    private String status;    // PENDING / RUNNING / DONE / FAILED
    private Integer progress;
    private Integer totalDocs;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}

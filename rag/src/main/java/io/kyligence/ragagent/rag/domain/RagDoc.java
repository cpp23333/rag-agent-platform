package io.kyligence.ragagent.rag.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("rag_doc")
public class RagDoc {
    @TableId(type = IdType.ASSIGN_UUID) private String id;
    private String kbId;
    private Long workspaceId;
    private String source;
    private String contentHash;
    private String status;    // PENDING / INDEXING / DONE / FAILED
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime ingestedAt;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}

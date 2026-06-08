package io.kyligence.ragagent.rag.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("rag_chunk")
public class RagChunk {
    @TableId(type = IdType.ASSIGN_UUID) private String id;
    private String kbId;
    private String docId;
    private Long workspaceId;
    private String text;
    private String metadataJson;
    private Integer position;
    private String modality;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}

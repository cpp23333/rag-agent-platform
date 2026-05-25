package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("memory_short_term")
public class ShortTermMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Integer idx;
    private MessageRole role;
    private String content;
    private String name;
    private String metadataJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

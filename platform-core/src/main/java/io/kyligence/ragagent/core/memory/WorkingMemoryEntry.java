package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("memory_working")
public class WorkingMemoryEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String entryKey;
    private String valueJson;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

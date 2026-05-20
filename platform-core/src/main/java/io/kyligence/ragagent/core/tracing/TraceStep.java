package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trace_step")
public class TraceStep {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String name;
    private StepType type;
    private String input;
    private String output;
    private Integer tokens;
    private BigDecimal cost;
    private Long latencyMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

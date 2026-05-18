package io.kyligence.ragagent.shared.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageRequest {
    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    /**
     * 计算 offset（从 0 开始）
     */
    public int getOffset() {
        return (page - 1) * pageSize;
    }
}

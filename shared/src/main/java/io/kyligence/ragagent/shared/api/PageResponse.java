package io.kyligence.ragagent.shared.api;

import lombok.Data;

import java.util.List;

/**
 * 分页响应
 *
 * @param <T> 数据项类型
 */
@Data
public class PageResponse<T> {
    private List<T> items;
    private long total;
    private int page;
    private int pageSize;

    public PageResponse(List<T> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    /**
     * 总页数
     */
    public int getTotalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }

    /**
     * 是否有下一页
     */
    public boolean hasNext() {
        return page < getTotalPages();
    }
}

package io.kyligence.ragagent.core.tenant;

import java.lang.annotation.*;

/**
 * 标记在 Mapper 接口上，表示该 Mapper 的所有查询需要自动注入 workspace_id 过滤条件
 *
 * 示例：
 * <pre>
 * @Mapper
 * @WorkspaceAware
 * public interface ApiKeyMapper extends BaseMapper&lt;ApiKey&gt; {
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkspaceAware {
}

package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

/**
 * ApiKey Mapper
 *
 * 标记 @WorkspaceAware，所有查询自动注入 workspace_id 过滤条件
 */
@Mapper
@WorkspaceAware
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}

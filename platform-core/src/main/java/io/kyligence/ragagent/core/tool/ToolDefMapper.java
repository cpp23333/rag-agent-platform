package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface ToolDefMapper extends BaseMapper<ToolDef> {
}

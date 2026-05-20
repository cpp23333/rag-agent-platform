package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface TraceRunMapper extends BaseMapper<TraceRun> {
}

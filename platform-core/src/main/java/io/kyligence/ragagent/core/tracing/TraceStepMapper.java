package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceStepMapper extends BaseMapper<TraceStep> {
}

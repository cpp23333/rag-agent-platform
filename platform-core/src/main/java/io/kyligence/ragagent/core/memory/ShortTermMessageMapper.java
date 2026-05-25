package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShortTermMessageMapper extends BaseMapper<ShortTermMessage> {
    @Select("SELECT MAX(idx) FROM memory_short_term WHERE run_id = #{runId} FOR UPDATE")
    Integer selectMaxIdx(@Param("runId") String runId);
}

package io.kyligence.ragagent.core.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkingMemoryEntryMapper extends BaseMapper<WorkingMemoryEntry> {
}

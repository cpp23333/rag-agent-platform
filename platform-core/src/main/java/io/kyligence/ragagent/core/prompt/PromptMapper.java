package io.kyligence.ragagent.core.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface PromptMapper extends BaseMapper<Prompt> {
}

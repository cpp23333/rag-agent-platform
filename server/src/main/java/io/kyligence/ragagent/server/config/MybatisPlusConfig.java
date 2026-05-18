package io.kyligence.ragagent.server.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import io.kyligence.ragagent.core.tenant.WorkspaceFilterInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
    "io.kyligence.ragagent.core.auth"
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new WorkspaceFilterInterceptor());
        return interceptor;
    }
}

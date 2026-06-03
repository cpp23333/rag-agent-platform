package io.kyligence.ragagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolRegistryImplTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private ToolDefMapper toolDefMapper;
    @Mock private RestTemplate restTemplate;

    private ToolRegistryImpl registry;

    @BeforeEach
    void setUp() {
        when(applicationContext.getBeansOfType(JavaBeanTool.class)).thenReturn(Map.of());
        registry = new ToolRegistryImpl(applicationContext, toolDefMapper,
                restTemplate, new ObjectMapper());
        registry.autoRegister();
    }

    @Test
    void registerAndFind() {
        Tool tool = stubTool("echo");
        registry.register(tool);
        assertThat(registry.find("echo")).contains(tool);
    }

    @Test
    void findReturnsEmptyForUnknown() {
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void invokeThrowsWhenNotFound() {
        assertThatThrownBy(() ->
            registry.invoke("missing", new ToolInput(Map.of()), new ToolContext("r", "w", 1L)))
            .isInstanceOf(PlatformException.class);
    }

    @Test
    void invokeCallsTool() {
        Tool tool = stubTool("greet");
        when(tool.invoke(any(), any())).thenReturn(ToolResult.ok("hi"));
        registry.register(tool);

        ToolResult result = registry.invoke("greet",
            new ToolInput(Map.of()), new ToolContext("r", "w", 1L));
        assertThat(result.success()).isTrue();
        assertThat(result.output().value()).isEqualTo("hi");
    }

    @Test
    void autoRegisterPicksUpJavaBeanTools() {
        JavaBeanTool bean = mock(JavaBeanTool.class);
        when(bean.name()).thenReturn("calc");
        when(applicationContext.getBeansOfType(JavaBeanTool.class))
            .thenReturn(Map.of("calc", bean));

        registry.autoRegister();
        assertThat(registry.find("calc")).contains(bean);
    }

    @Test
    void listReturnsAllTools() {
        registry.register(stubTool("a"));
        registry.register(stubTool("b"));
        assertThat(registry.list()).extracting(ToolRegistry.ToolSummary::name)
            .contains("a", "b");
    }

    private Tool stubTool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }
}

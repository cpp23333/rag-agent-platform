package io.kyligence.ragagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpToolTest {

    @Test
    void invokesHttpEndpointAndReturnsBody() throws Exception {
        ToolDef def = new ToolDef();
        def.setName("search");
        def.setDescription("search tool");
        def.setConfigJson("{\"url\":\"http://test/search\",\"method\":\"POST\"}");

        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"result\":\"found\"}"));

        HttpTool tool = new HttpTool(def, rest, new ObjectMapper());
        ToolResult result = tool.invoke(new ToolInput(Map.of("q", "hello")),
            new ToolContext("run1", "ws1", 1L));

        assertThat(result.success()).isTrue();
        assertThat(result.output().value()).isEqualTo("{\"result\":\"found\"}");
    }
}

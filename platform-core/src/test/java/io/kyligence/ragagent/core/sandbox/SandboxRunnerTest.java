package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SandboxRunnerTest {

    private final SandboxRunner runner = new SandboxRunner();

    @Test
    void returnsComputedValue() {
        Object result = runner.run(
            "return (Integer) input.get(\"x\") + (Integer) input.get(\"y\");",
            Map.of("x", 3, "y", 4));
        assertThat(result).isEqualTo(7);
    }

    @Test
    void allowsJavaUtilAndMath() {
        Object result = runner.run(
            "java.util.List<String> list = new java.util.ArrayList<>();" +
            "list.add(\"hello\"); return list.size();",
            Map.of());
        assertThat(result).isEqualTo(1);
    }

    @Test
    void cachesCompiledScript() {
        String src = "return 42;";
        runner.run(src, Map.of());
        // second call should use cache (no exception = pass)
        assertThat(runner.run(src, Map.of())).isEqualTo(42);
    }

    @Test
    void blocksIoImport() {
        assertThatThrownBy(() -> runner.run(
            "import java.io.File; return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("Forbidden import");
    }

    @Test
    void blocksReflection() {
        assertThatThrownBy(() -> runner.run(
            "Class.forName(\"java.lang.Runtime\"); return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("Reflection");
    }

    @Test
    void blocksSystemExit() {
        assertThatThrownBy(() -> runner.run(
            "System.exit(0); return null;", Map.of()))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("System.exit");
    }

    @Test
    void timesOutLongRunningScript() {
        assertThatThrownBy(() -> runner.run(
            "while(true){} return null;",
            Map.of(), Duration.ofMillis(200)))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("timed out");
    }
}

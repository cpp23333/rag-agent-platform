package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.janino.ScriptEvaluator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component
public class SandboxRunner {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final ExecutorService EXECUTOR =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sandbox-runner");
            t.setDaemon(true);
            return t;
        });

    private final SandboxSecurityManager security = new SandboxSecurityManager();
    private final CompiledScriptCache cache = new CompiledScriptCache();

    public Object run(String source, Map<String, Object> input) {
        return run(source, input, DEFAULT_TIMEOUT);
    }

    public Object run(String source, Map<String, Object> input, Duration timeout) {
        security.validate(source);

        Future<Object> future = EXECUTOR.submit(() -> execute(source, input));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PlatformException(ErrorCode.SANDBOX_TIMEOUT,
                "Script execution timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PlatformException pe) throw pe;
            throw new PlatformException(ErrorCode.SANDBOX_EXECUTION_FAILED,
                "Script execution failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(ErrorCode.SANDBOX_EXECUTION_FAILED,
                "Script execution interrupted");
        }
    }

    private Object execute(String source, Map<String, Object> input) throws Exception {
        ScriptEvaluator se = cache.get(source, src -> {
            ScriptEvaluator evaluator = new ScriptEvaluator();
            evaluator.setParameters(new String[]{"input"}, new Class[]{Map.class});
            evaluator.setReturnType(Object.class);
            evaluator.cook(src);
            return evaluator;
        });
        return se.evaluate(new Object[]{input});
    }
}

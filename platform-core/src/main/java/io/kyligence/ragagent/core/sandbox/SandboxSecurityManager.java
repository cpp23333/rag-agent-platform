package io.kyligence.ragagent.core.sandbox;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates sandbox source before compilation.
 * Uses simple pattern matching — sufficient for the threat model (trusted-ish users, not adversarial).
 */
public class SandboxSecurityManager {

    private static final Set<String> BLOCKED_IMPORTS = Set.of(
        "java.io", "java.nio", "java.net", "java.lang.reflect",
        "java.lang.Runtime", "java.lang.ProcessBuilder",
        "java.lang.ClassLoader", "java.lang.Thread",
        "sun.", "com.sun.", "javax.script"
    );

    private static final Pattern REFLECT_PATTERN =
        Pattern.compile("\\b(Class\\.forName|getDeclaredMethod|getMethod|invoke|newInstance)\\b");

    private static final Pattern SYSTEM_EXIT_PATTERN =
        Pattern.compile("\\bSystem\\s*\\.\\s*exit\\b");

    public void validate(String source) {
        for (String blocked : BLOCKED_IMPORTS) {
            if (source.contains("import " + blocked)) {
                throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                    "Forbidden import: " + blocked);
            }
        }
        if (REFLECT_PATTERN.matcher(source).find()) {
            throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                "Reflection is not allowed in sandbox");
        }
        if (SYSTEM_EXIT_PATTERN.matcher(source).find()) {
            throw new PlatformException(ErrorCode.SANDBOX_SECURITY_VIOLATION,
                "System.exit is not allowed in sandbox");
        }
    }
}

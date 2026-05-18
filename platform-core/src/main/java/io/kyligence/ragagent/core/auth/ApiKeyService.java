package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * API Key 验证服务
 */
@Service
public class ApiKeyService {

    public static final String KEY_PREFIX = "rak_";

    public record Resolved(ApiKey apiKey, String workspaceId) {}

    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, PasswordEncoder passwordEncoder) {
        this.apiKeyMapper = apiKeyMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Verify a presented "rak_<prefix>_<secret>" token
     */
    public Optional<Resolved> verify(String presented) {
        if (presented == null || !presented.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }

        String body = presented.substring(KEY_PREFIX.length());
        int sep = body.indexOf('_');
        if (sep < 0) {
            return Optional.empty();
        }

        String prefix = body.substring(0, sep);
        String secret = body.substring(sep + 1);

        ApiKey key = apiKeyMapper.selectOne(
            Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getKeyPrefix, prefix));

        if (key == null || key.getDeleted()) {
            return Optional.empty();
        }

        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }

        if (!passwordEncoder.matches(secret, key.getKeyHash())) {
            return Optional.empty();
        }

        // Update last used timestamp
        key.setLastUsedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);

        return Optional.of(new Resolved(key, key.getWorkspaceId()));
    }
}

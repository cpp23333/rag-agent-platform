package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 实体
 */
@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String name;

    /**
     * 密钥哈希值（SHA-256）
     */
    private String keyHash;

    /**
     * 密钥前缀（用于展示，如 "sk_live_abc...")
     */
    private String keyPrefix;

    private LocalDateTime lastUsedAt;

    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Boolean deleted;
}

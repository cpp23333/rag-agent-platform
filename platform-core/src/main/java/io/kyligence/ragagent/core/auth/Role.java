package io.kyligence.ragagent.core.auth;

/**
 * Workspace 成员角色
 */
public enum Role {
    /**
     * 所有者 - 完全控制权限
     */
    OWNER,

    /**
     * 管理员 - 除删除 workspace 外的所有权限
     */
    ADMIN,

    /**
     * 成员 - 可以创建和管理自己的资源
     */
    MEMBER,

    /**
     * 查看者 - 只读权限
     */
    VIEWER
}

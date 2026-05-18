package io.kyligence.ragagent.core.tenant;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;

/**
 * Workspace 上下文 ThreadLocal 封装
 */
public class WorkspaceContextHolder {

    private static final ThreadLocal<WorkspaceContext> CONTEXT = new InheritableThreadLocal<>();

    /**
     * 设置当前线程的 workspace 上下文
     */
    public static void setContext(WorkspaceContext context) {
        CONTEXT.set(context);
    }

    /**
     * 获取当前线程的 workspace 上下文
     *
     * @throws PlatformException 如果未设置上下文
     */
    public static WorkspaceContext getContext() {
        WorkspaceContext context = CONTEXT.get();
        if (context == null) {
            throw new PlatformException(
                    ErrorCode.UNAUTHORIZED,
                    "Workspace context not set. User must be authenticated."
            );
        }
        return context;
    }

    /**
     * 获取当前 workspace ID
     */
    public static String getWorkspaceId() {
        return getContext().workspaceId();
    }

    /**
     * 获取当前用户 ID
     */
    public static String getUserId() {
        return getContext().userId();
    }

    /**
     * 获取当前用户角色
     */
    public static io.kyligence.ragagent.core.auth.Role getRole() {
        return getContext().role();
    }

    /**
     * 清除当前线程的上下文（请求结束时调用）
     */
    public static void clear() {
        CONTEXT.remove();
    }
}

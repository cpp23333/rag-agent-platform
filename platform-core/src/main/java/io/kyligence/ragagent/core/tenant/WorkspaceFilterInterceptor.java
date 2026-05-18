package io.kyligence.ragagent.core.tenant;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Proxy;
import java.sql.SQLException;

/**
 * MyBatis-Plus 拦截器：自动为标记 @WorkspaceAware 的 Mapper 注入 workspace_id 过滤条件
 *
 * <p>拦截所有 SELECT/UPDATE/DELETE 语句，在 WHERE 子句中自动添加 workspace_id = '...'
 *
 * <p>INSERT 语句不处理（业务层需要显式设置 workspaceId）
 */
@Slf4j
public class WorkspaceFilterInterceptor implements InnerInterceptor {

    private static final String WORKSPACE_ID_COLUMN = "workspace_id";

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {

        if (!isWorkspaceAware(ms)) {
            return;
        }

        PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
        String originalSql = mpBoundSql.sql();

        try {
            Statement statement = PluginUtils.mpParse(originalSql, null);
            if (statement instanceof Select select) {
                injectWorkspaceCondition(select);
                mpBoundSql.sql(statement.toString());
                log.debug("Injected workspace_id filter: {}", statement);
            }
        } catch (Exception e) {
            log.error("Failed to inject workspace_id filter: {}", e.getMessage(), e);
            throw new SQLException("Failed to inject workspace_id filter", e);
        }
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter)
            throws SQLException {

        if (!isWorkspaceAware(ms)) {
            return;
        }

        // INSERT 不处理（业务层显式设置）
        if (ms.getSqlCommandType() == SqlCommandType.INSERT) {
            return;
        }

        BoundSql boundSql = ms.getBoundSql(parameter);
        PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
        String originalSql = mpBoundSql.sql();

        try {
            Statement statement = PluginUtils.mpParse(originalSql, null);
            if (statement instanceof Update update) {
                injectWorkspaceCondition(update);
                mpBoundSql.sql(statement.toString());
                log.debug("Injected workspace_id filter: {}", statement);
            } else if (statement instanceof Delete delete) {
                injectWorkspaceCondition(delete);
                mpBoundSql.sql(statement.toString());
                log.debug("Injected workspace_id filter: {}", statement);
            }
        } catch (Exception e) {
            log.error("Failed to inject workspace_id filter: {}", e.getMessage(), e);
            throw new SQLException("Failed to inject workspace_id filter", e);
        }
    }

    /**
     * 判断 Mapper 是否标记了 @WorkspaceAware
     */
    private boolean isWorkspaceAware(MappedStatement ms) {
        try {
            String namespace = ms.getId();
            String className = namespace.substring(0, namespace.lastIndexOf('.'));
            Class<?> mapperClass = Class.forName(className);

            // 如果是 Proxy，获取真实类
            if (Proxy.isProxyClass(mapperClass)) {
                Class<?>[] interfaces = mapperClass.getInterfaces();
                if (interfaces.length > 0) {
                    mapperClass = interfaces[0];
                }
            }

            return mapperClass.isAnnotationPresent(WorkspaceAware.class);
        } catch (ClassNotFoundException e) {
            log.warn("Cannot find mapper class for {}", ms.getId());
            return false;
        }
    }

    /**
     * 为 SELECT 注入 workspace_id 条件
     */
    private void injectWorkspaceCondition(Select select) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect plainSelect) {
            Expression where = plainSelect.getWhere();
            Expression workspaceCondition = createWorkspaceCondition();

            if (where == null) {
                plainSelect.setWhere(workspaceCondition);
            } else {
                plainSelect.setWhere(new net.sf.jsqlparser.expression.operators.conditional.AndExpression(
                        where, workspaceCondition
                ));
            }
        }
        // TODO: 支持 SetOperationList (UNION/INTERSECT/EXCEPT)
    }

    /**
     * 为 UPDATE 注入 workspace_id 条件
     */
    private void injectWorkspaceCondition(Update update) {
        Expression where = update.getWhere();
        Expression workspaceCondition = createWorkspaceCondition();

        if (where == null) {
            update.setWhere(workspaceCondition);
        } else {
            update.setWhere(new net.sf.jsqlparser.expression.operators.conditional.AndExpression(
                    where, workspaceCondition
            ));
        }
    }

    /**
     * 为 DELETE 注入 workspace_id 条件
     */
    private void injectWorkspaceCondition(Delete delete) {
        Expression where = delete.getWhere();
        Expression workspaceCondition = createWorkspaceCondition();

        if (where == null) {
            delete.setWhere(workspaceCondition);
        } else {
            delete.setWhere(new net.sf.jsqlparser.expression.operators.conditional.AndExpression(
                    where, workspaceCondition
            ));
        }
    }

    /**
     * 创建 workspace_id = '...' 条件
     */
    private Expression createWorkspaceCondition() {
        String workspaceId = WorkspaceContextHolder.getWorkspaceId();

        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(new Column(WORKSPACE_ID_COLUMN));
        equalsTo.setRightExpression(new StringValue(workspaceId));
        return equalsTo;
    }
}

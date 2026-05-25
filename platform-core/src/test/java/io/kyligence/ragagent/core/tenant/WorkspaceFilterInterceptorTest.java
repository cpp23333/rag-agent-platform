package io.kyligence.ragagent.core.tenant;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import io.kyligence.ragagent.core.auth.Role;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceFilterInterceptor Unit Tests")
class WorkspaceFilterInterceptorTest {

  private WorkspaceFilterInterceptor interceptor;

  @Mock
  private Executor executor;

  @Mock
  private MappedStatement mappedStatement;

  @Mock
  private ResultHandler<?> resultHandler;

  private static final String WORKSPACE_ID = "test-workspace-123";
  private static final String USER_ID = "test-user-456";

  @BeforeEach
  void setUp() {
    interceptor = new WorkspaceFilterInterceptor();
    WorkspaceContext context = new WorkspaceContext(WORKSPACE_ID, USER_ID, Role.ADMIN);
    WorkspaceContextHolder.setContext(context);
  }

  @AfterEach
  void tearDown() {
    WorkspaceContextHolder.clear();
  }

  @Test
  @DisplayName("beforeQuery injects workspace_id for @WorkspaceAware mapper")
  void beforeQuery_withWorkspaceAwareMapper_injectsWorkspaceId() throws SQLException {
    String originalSql = "SELECT * FROM knowledge_base WHERE name = 'test'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.selectById");

    interceptor.beforeQuery(
        executor, mappedStatement, null, RowBounds.DEFAULT, resultHandler, boundSql);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
    assertThat(modifiedSql).containsIgnoringCase("AND");
  }

  @Test
  @DisplayName("beforeQuery does not modify SQL for non-WorkspaceAware mapper")
  void beforeQuery_withNonWorkspaceAwareMapper_doesNotModifySql() throws SQLException {
    String originalSql = "SELECT * FROM users WHERE id = '123'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.core.auth.UserMapper.selectById");

    interceptor.beforeQuery(
        executor, mappedStatement, null, RowBounds.DEFAULT, resultHandler, boundSql);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).isEqualTo(originalSql);
  }

  @Test
  @DisplayName("beforeQuery adds WHERE clause when none exists")
  void beforeQuery_withNoWhereClause_addsWhereClause() throws SQLException {
    String originalSql = "SELECT * FROM knowledge_base";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.selectAll");

    interceptor.beforeQuery(
        executor, mappedStatement, null, RowBounds.DEFAULT, resultHandler, boundSql);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).containsIgnoringCase("WHERE");
    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
  }

  @Test
  @DisplayName("beforeUpdate injects workspace_id for UPDATE statements")
  void beforeUpdate_withUpdateStatement_injectsWorkspaceId() throws SQLException {
    String originalSql = "UPDATE knowledge_base SET name = 'updated' WHERE id = '123'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.updateById");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
    assertThat(modifiedSql).containsIgnoringCase("AND");
  }

  @Test
  @DisplayName("beforeUpdate injects workspace_id for DELETE statements")
  void beforeUpdate_withDeleteStatement_injectsWorkspaceId() throws SQLException {
    String originalSql = "DELETE FROM knowledge_base WHERE id = '123'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.deleteById");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.DELETE);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
    assertThat(modifiedSql).containsIgnoringCase("AND");
  }

  @Test
  @DisplayName("beforeUpdate does not modify INSERT statements")
  void beforeUpdate_withInsertStatement_doesNotModifySql() throws SQLException {
    String originalSql = "INSERT INTO knowledge_base (id, name) VALUES ('1', 'test')";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.insert");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.INSERT);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).isEqualTo(originalSql);
  }

  @Test
  @DisplayName("beforeUpdate does not modify SQL for non-WorkspaceAware mapper")
  void beforeUpdate_withNonWorkspaceAwareMapper_doesNotModifySql() throws SQLException {
    String originalSql = "UPDATE users SET name = 'test' WHERE id = '1'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.core.auth.UserMapper.updateById");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).isEqualTo(originalSql);
  }

  @Test
  @DisplayName("interceptor handles SQL without WHERE clause in UPDATE")
  void beforeUpdate_withNoWhereClauseInUpdate_addsWhereClause() throws SQLException {
    String originalSql = "UPDATE knowledge_base SET name = 'updated'";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.updateAll");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).containsIgnoringCase("WHERE");
    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
  }

  @Test
  @DisplayName("interceptor handles SQL without WHERE clause in DELETE")
  void beforeUpdate_withNoWhereClauseInDelete_addsWhereClause() throws SQLException {
    String originalSql = "DELETE FROM knowledge_base";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.deleteAll");
    when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.DELETE);
    when(mappedStatement.getBoundSql(any())).thenReturn(boundSql);

    interceptor.beforeUpdate(executor, mappedStatement, null);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).containsIgnoringCase("WHERE");
    assertThat(modifiedSql).contains("workspace_id = '" + WORKSPACE_ID + "'");
  }

  @Test
  @DisplayName("interceptor uses workspace_id from context")
  void interceptor_usesWorkspaceIdFromContext() throws SQLException {
    String customWorkspaceId = "custom-workspace-999";
    WorkspaceContext customContext = new WorkspaceContext(customWorkspaceId, USER_ID, Role.ADMIN);
    WorkspaceContextHolder.setContext(customContext);

    String originalSql = "SELECT * FROM knowledge_base";
    BoundSql boundSql = createBoundSql(originalSql);

    when(mappedStatement.getId())
        .thenReturn("io.kyligence.ragagent.test.WorkspaceAwareMapper.selectAll");

    interceptor.beforeQuery(
        executor, mappedStatement, null, RowBounds.DEFAULT, resultHandler, boundSql);

    PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
    String modifiedSql = mpBoundSql.sql();

    assertThat(modifiedSql).contains("workspace_id = '" + customWorkspaceId + "'");
  }

  // Helper method to create BoundSql
  private BoundSql createBoundSql(String sql) {
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    return new BoundSql(configuration, sql, null, null);
  }

  // Test marker annotation for workspace-aware mapper
  @WorkspaceAware
  interface TestWorkspaceAwareMapper {
  }
}

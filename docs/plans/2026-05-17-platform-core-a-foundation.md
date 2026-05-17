# Platform Core — Plan A: Foundation 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建好 rag-agent-platform 的工程地基：Maven 多模块骨架 + 本地开发依赖（MySQL/Postgres+pgvector/Redis/MinIO）+ Liquibase + 多租户体系（Workspace/User/Role/ApiKey）+ JWT 双通道鉴权 + Repository 层 workspace 隔离 AOP + Docker 镜像 + CI。完成后任何后续模块都可以直接挂上去开工。

**Architecture:** 单仓 Maven 父子 POM（7 个 module：`shared`/`platform-core`/`rag`/`agent`/`workflow`/`server`/`cli`，本 plan 只把 `shared`/`platform-core`/`server` 的骨架立起来；其他三个 module 留 `pom.xml` + 空 `src/`）。`server` 是 Spring Boot 启动入口；`platform-core` 提供 Auth + tenant 能力；`shared` 放跨模块 DTO/异常。多租户通过 `WorkspaceContextHolder`（ThreadLocal）+ Spring AOP 在 Repository 调用前强制注入 `workspace_id` 过滤条件，业务代码无感。

**Tech Stack:** Java 17 / Spring Boot 3.2.x / Spring Security 6 / Liquibase 4.x / MyBatis-Plus 3.5.x（业务库 ORM）/ MySQL 8 / Postgres 16 + pgvector / Redis 7 / MinIO / JJWT 0.12.x / BCrypt / Maven 3.9+ / JUnit 5 + AssertJ + Mockito + Testcontainers / Docker (`eclipse-temurin:17-jre`)

**Naming:** Maven groupId = `io.kyligence.ragagent`；Java root package = `io.kyligence.ragagent`；parent artifactId = `rag-agent-parent`。

**部署约束（spec §8.3）：** 所有生产形态均为 Docker 镜像；禁止依赖宿主机 JDK。

---

## File Structure

下面列出本 plan 落地的全部文件。每个文件单一职责。

```
rag-agent-platform/
├─ pom.xml                                                   # parent: 版本管理 + module 列表
├─ docker-compose.yml                                        # 本地 mysql/pg/redis/minio
├─ .env.example                                              # 数据库/MinIO 默认密码模板
├─ Dockerfile                                                # multi-stage: maven build → jre runtime
├─ .dockerignore
├─ .github/workflows/ci.yml                                  # PR/push: mvn verify
├─ .gitignore
├─ shared/
│  ├─ pom.xml
│  └─ src/main/java/io/kyligence/ragagent/shared/
│      ├─ api/ApiResponse.java                               # 统一响应包络
│      ├─ api/PageRequest.java
│      ├─ api/PageResponse.java
│      ├─ exception/PlatformException.java                   # 业务异常基类
│      └─ exception/ErrorCode.java                           # 错误码枚举
├─ platform-core/
│  ├─ pom.xml
│  └─ src/
│      ├─ main/java/io/kyligence/ragagent/core/
│      │   ├─ auth/
│      │   │   ├─ Role.java                                  # OWNER/ADMIN/MEMBER/VIEWER
│      │   │   ├─ User.java                                  # 实体
│      │   │   ├─ UserMapper.java                            # MyBatis-Plus mapper
│      │   │   ├─ Workspace.java
│      │   │   ├─ WorkspaceMapper.java
│      │   │   ├─ WorkspaceMember.java
│      │   │   ├─ WorkspaceMemberMapper.java
│      │   │   ├─ ApiKey.java
│      │   │   ├─ ApiKeyMapper.java
│      │   │   ├─ AuthService.java                           # register/login/refresh
│      │   │   ├─ JwtTokenProvider.java
│      │   │   └─ AuthExceptions.java                        # 4 个 ErrorCode 包装
│      │   └─ tenant/
│      │       ├─ WorkspaceContext.java                      # immutable: workspaceId, userId, role
│      │       ├─ WorkspaceContextHolder.java                # ThreadLocal 封装
│      │       ├─ WorkspaceAware.java                        # @WorkspaceAware 注解(标在 mapper 上)
│      │       └─ WorkspaceFilterInterceptor.java            # MyBatis-Plus inner interceptor
│      └─ test/java/io/kyligence/ragagent/core/
│          ├─ auth/JwtTokenProviderTest.java
│          ├─ auth/AuthServiceTest.java
│          └─ tenant/WorkspaceFilterInterceptorTest.java
├─ server/
│  ├─ pom.xml
│  └─ src/
│      ├─ main/java/io/kyligence/ragagent/server/
│      │   ├─ Application.java                               # @SpringBootApplication
│      │   ├─ config/
│      │   │   ├─ SecurityConfig.java                        # Spring Security 6 lambda DSL
│      │   │   ├─ JwtAuthenticationFilter.java
│      │   │   ├─ ApiKeyAuthenticationFilter.java
│      │   │   └─ MybatisPlusConfig.java                     # 注册 WorkspaceFilterInterceptor
│      │   └─ controller/
│      │       ├─ HealthController.java                      # /health
│      │       ├─ AuthController.java                        # /api/v1/auth/{register,login,refresh}
│      │       └─ WorkspaceController.java                   # /api/v1/workspaces
│      ├─ main/resources/
│      │   ├─ application.yml
│      │   └─ db/changelog/
│      │       ├─ db.changelog-master.yaml
│      │       └─ changes/
│      │           ├─ 001-workspace.yaml
│      │           ├─ 002-user.yaml
│      │           ├─ 003-workspace-member.yaml
│      │           └─ 004-api-key.yaml
│      └─ test/java/io/kyligence/ragagent/server/
│          ├─ controller/AuthControllerTest.java             # MockMvc
│          ├─ controller/WorkspaceControllerTest.java        # MockMvc
│          └─ FoundationIntegrationTest.java                 # Testcontainers MySQL 端到端
├─ rag/pom.xml                                               # 空 module 占位
├─ agent/pom.xml                                             # 空 module 占位
├─ workflow/pom.xml                                          # 空 module 占位
└─ cli/pom.xml                                               # 空 module 占位
```

**依赖关系：** `server` → `platform-core` → `shared`。其他四个 module 本 plan 不引用。

---

## Task 1: Scaffold 仓库根 + .gitignore + 父 POM

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`

- [ ] **Step 1: 写 `.gitignore`**

```
target/
*.class
*.log
*.jar
*.war
.idea/
*.iml
.vscode/
.DS_Store
.env
docker/data/
*.local.yml
```

- [ ] **Step 2: 写父 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>io.kyligence.ragagent</groupId>
    <artifactId>rag-agent-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>shared</module>
        <module>platform-core</module>
        <module>rag</module>
        <module>agent</module>
        <module>workflow</module>
        <module>server</module>
        <module>cli</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
        <jjwt.version>0.12.5</jjwt.version>
        <testcontainers.version>1.19.7</testcontainers.version>
        <liquibase.version>4.27.0</liquibase.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.kyligence.ragagent</groupId>
                <artifactId>shared</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.kyligence.ragagent</groupId>
                <artifactId>platform-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 3: 验证父 POM 解析**

Run: `mvn validate -N`
Expected: BUILD SUCCESS（`-N` 表示不递归到 module；此时 module 尚未创建，所以加 `-N`）。如果失败，应该是 XML 语法或者 Spring Boot parent 版本拼错。

- [ ] **Step 4: Commit**

```bash
git add pom.xml .gitignore
git commit -m "chore: scaffold parent POM + .gitignore"
```

---

## Task 2: 五个空 module（rag / agent / workflow / cli）+ shared 骨架

**Files:**
- Create: `rag/pom.xml`、`agent/pom.xml`、`workflow/pom.xml`、`cli/pom.xml`
- Create: `shared/pom.xml`
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/.gitkeep`

- [ ] **Step 1: 写一个占位 module pom 模板，复用到 rag/agent/workflow/cli**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.kyligence.ragagent</groupId>
        <artifactId>rag-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>{MODULE}</artifactId>
</project>
```

把 `{MODULE}` 分别替换为 `rag` / `agent` / `workflow` / `cli`，落到对应 `*/pom.xml`。

- [ ] **Step 2: 写 `shared/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.kyligence.ragagent</groupId>
        <artifactId>rag-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>shared</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 创建源目录占位**

```bash
mkdir -p shared/src/main/java/io/kyligence/ragagent/shared
mkdir -p shared/src/test/java/io/kyligence/ragagent/shared
touch shared/src/main/java/io/kyligence/ragagent/shared/.gitkeep
```

- [ ] **Step 4: 验证整体 reactor 解析**

Run: `mvn validate`
Expected: BUILD SUCCESS，5 个 module 都被识别（控制台输出 `Reactor Summary`，含 6 行 `... SUCCESS`：parent + shared + rag/agent/workflow/cli）。

- [ ] **Step 5: Commit**

```bash
git add shared rag agent workflow cli
git commit -m "chore: add empty rag/agent/workflow/cli modules + shared skeleton"
```

---

## Task 3: shared 模块的 ApiResponse / PageRequest / PageResponse / 异常体系

**Files:**
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/api/ApiResponse.java`
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/api/PageRequest.java`
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/api/PageResponse.java`
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/exception/ErrorCode.java`
- Create: `shared/src/main/java/io/kyligence/ragagent/shared/exception/PlatformException.java`
- Test: `shared/src/test/java/io/kyligence/ragagent/shared/api/ApiResponseTest.java`

- [ ] **Step 1: 写测试 `ApiResponseTest.java`**

```java
package io.kyligence.ragagent.shared.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void okWrapsData() {
        ApiResponse<String> r = ApiResponse.ok("hello");
        assertThat(r.success()).isTrue();
        assertThat(r.data()).isEqualTo("hello");
        assertThat(r.error()).isNull();
    }

    @Test
    void errorCarriesCodeAndMessage() {
        ApiResponse<Void> r = ApiResponse.error("E001", "bad input");
        assertThat(r.success()).isFalse();
        assertThat(r.data()).isNull();
        assertThat(r.error().code()).isEqualTo("E001");
        assertThat(r.error().message()).isEqualTo("bad input");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -pl shared -Dtest=ApiResponseTest test`
Expected: 编译失败（`ApiResponse` 不存在）。

- [ ] **Step 3: 写 `ApiResponse.java`**

```java
package io.kyligence.ragagent.shared.api;

public record ApiResponse<T>(boolean success, T data, ErrorPayload error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorPayload(code, message));
    }

    public record ErrorPayload(String code, String message) {}
}
```

- [ ] **Step 4: 跑测试，应通过**

Run: `mvn -pl shared -Dtest=ApiResponseTest test`
Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 5: 写 `PageRequest.java`**

```java
package io.kyligence.ragagent.shared.api;

public record PageRequest(int page, int size) {
    public PageRequest {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (size < 1 || size > 200) throw new IllegalArgumentException("size must be in [1,200]");
    }
    public long offset() {
        return (long) page * size;
    }
}
```

- [ ] **Step 6: 写 `PageResponse.java`**

```java
package io.kyligence.ragagent.shared.api;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int page, int size) {
    public static <T> PageResponse<T> of(List<T> items, long total, PageRequest req) {
        return new PageResponse<>(items, total, req.page(), req.size());
    }
}
```

- [ ] **Step 7: 写 `ErrorCode.java`（后续模块会扩充）**

```java
package io.kyligence.ragagent.shared.exception;

public enum ErrorCode {
    INVALID_REQUEST("E_INVALID_REQUEST"),
    UNAUTHORIZED("E_UNAUTHORIZED"),
    FORBIDDEN("E_FORBIDDEN"),
    NOT_FOUND("E_NOT_FOUND"),
    CONFLICT("E_CONFLICT"),
    INTERNAL("E_INTERNAL");

    private final String code;
    ErrorCode(String code) { this.code = code; }
    public String code() { return code; }
}
```

- [ ] **Step 8: 写 `PlatformException.java`**

```java
package io.kyligence.ragagent.shared.exception;

public class PlatformException extends RuntimeException {
    private final ErrorCode code;

    public PlatformException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PlatformException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

- [ ] **Step 9: 跑 shared 模块全部测试**

Run: `mvn -pl shared test`
Expected: BUILD SUCCESS。

- [ ] **Step 10: Commit**

```bash
git add shared/src
git commit -m "feat(shared): ApiResponse/Page envelope + ErrorCode/PlatformException"
```

---

## Task 4: 本地依赖 docker-compose（MySQL/Postgres+pgvector/Redis/MinIO）

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`

- [ ] **Step 1: 写 `.env.example`**

```
MYSQL_ROOT_PASSWORD=root
MYSQL_DATABASE=ragagent
MYSQL_USER=ragagent
MYSQL_PASSWORD=ragagent

POSTGRES_USER=ragagent
POSTGRES_PASSWORD=ragagent
POSTGRES_DB=ragagent_vec

MINIO_ROOT_USER=ragagent
MINIO_ROOT_PASSWORD=ragagent12345
```

- [ ] **Step 2: 写 `docker-compose.yml`**

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: ragagent-mysql
    restart: unless-stopped
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    volumes:
      - ./docker/data/mysql:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 5s
      timeout: 3s
      retries: 20

  postgres:
    image: pgvector/pgvector:pg16
    container_name: ragagent-postgres
    restart: unless-stopped
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - ./docker/data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 3s
      retries: 20

  redis:
    image: redis:7-alpine
    container_name: ragagent-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    volumes:
      - ./docker/data/redis:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  minio:
    image: minio/minio:RELEASE.2024-04-18T19-09-19Z
    container_name: ragagent-minio
    restart: unless-stopped
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes:
      - ./docker/data/minio:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 3s
      retries: 10
```

- [ ] **Step 3: 启动并验证健康**

```bash
cp .env.example .env
docker compose up -d
sleep 15
docker compose ps
```

Expected: 4 个容器全部 `Up (healthy)`。如果 minio healthcheck 不过，确认镜像内含 curl；如果不含可以改成 `wget --spider`。

- [ ] **Step 4: 验证 pgvector 扩展可加载**

```bash
docker exec ragagent-postgres psql -U ragagent -d ragagent_vec -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"
```

Expected: 返回非空 `extversion`（如 `0.7.0`）。

- [ ] **Step 5: 把 `docker/` 数据目录加入 gitignore（Task 1 已加 `docker/data/`，复核一次）**

```bash
grep -q '^docker/data/$' .gitignore || echo 'docker/data/' >> .gitignore
```

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env.example .gitignore
git commit -m "chore: docker-compose with mysql/pg+pgvector/redis/minio"
```

---

## Task 5: platform-core 模块 pom + 基础包结构

**Files:**
- Create: `platform-core/pom.xml`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/.gitkeep`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/tenant/.gitkeep`

- [ ] **Step 1: 写 `platform-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.kyligence.ragagent</groupId>
        <artifactId>rag-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>platform-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.kyligence.ragagent</groupId>
            <artifactId>shared</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 建源目录占位**

```bash
mkdir -p platform-core/src/main/java/io/kyligence/ragagent/core/auth
mkdir -p platform-core/src/main/java/io/kyligence/ragagent/core/tenant
mkdir -p platform-core/src/test/java/io/kyligence/ragagent/core/auth
mkdir -p platform-core/src/test/java/io/kyligence/ragagent/core/tenant
touch platform-core/src/main/java/io/kyligence/ragagent/core/auth/.gitkeep
touch platform-core/src/main/java/io/kyligence/ragagent/core/tenant/.gitkeep
```

- [ ] **Step 3: 编译**

Run: `mvn -pl platform-core -am compile`
Expected: BUILD SUCCESS（依赖能解析、无源代码也能编译过）。

- [ ] **Step 4: Commit**

```bash
git add platform-core
git commit -m "chore(platform-core): module pom + package skeleton"
```

---

## Task 6: server 模块 pom + Application + application.yml + health endpoint

**Files:**
- Create: `server/pom.xml`
- Create: `server/src/main/java/io/kyligence/ragagent/server/Application.java`
- Create: `server/src/main/java/io/kyligence/ragagent/server/controller/HealthController.java`
- Create: `server/src/main/resources/application.yml`

- [ ] **Step 1: 写 `server/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.kyligence.ragagent</groupId>
        <artifactId>rag-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>server</artifactId>

    <dependencies>
        <dependency>
            <groupId>io.kyligence.ragagent</groupId>
            <artifactId>platform-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.liquibase</groupId>
            <artifactId>liquibase-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>io.kyligence.ragagent.server.Application</mainClass>
                </configuration>
                <executions>
                    <execution>
                        <goals><goal>repackage</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 写 `Application.java`**

```java
package io.kyligence.ragagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.kyligence.ragagent")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

- [ ] **Step 3: 写 `HealthController.java`**

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }
}
```

- [ ] **Step 4: 写 `application.yml`**

```yaml
spring:
  application:
    name: rag-agent-platform
  datasource:
    url: jdbc:mysql://localhost:3306/ragagent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: ragagent
    password: ragagent
    driver-class-name: com.mysql.cj.jdbc.Driver
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

server:
  port: 8080

ragagent:
  auth:
    jwt:
      secret: change-me-in-prod-32-chars-minimum-please
      access-ttl: PT1H
      refresh-ttl: P30D
      issuer: rag-agent-platform

logging:
  level:
    io.kyligence.ragagent: INFO
```

注意：本任务里 Liquibase changelog 文件还不存在，所以会启动失败。**Task 7** 会建空 master changelog，之后才能跑 Spring Boot。本任务结束**不验证启动**，下一任务里一起验。

- [ ] **Step 5: 编译能过**

Run: `mvn -pl server -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add server/pom.xml server/src
git commit -m "feat(server): Spring Boot bootstrap with health endpoint"
```

---

## Task 7: Liquibase 空 master changelog + 首次启动验证

**Files:**
- Create: `platform-core/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `platform-core/src/main/resources/db/changelog/changes/.gitkeep`

> changelog 放在 `platform-core`（不是 `server`）：因为 `platform-core` 测试需要它在 classpath 上（Task 8+ 的 Testcontainers 测试），`server` 通过依赖 `platform-core` 自动拿到。

- [ ] **Step 1: 写空 master changelog**

```yaml
databaseChangeLog:
  - includeAll:
      path: changes
      relativeToChangelogFile: true
```

- [ ] **Step 2: 建空 `changes/` 目录**

```bash
mkdir -p platform-core/src/main/resources/db/changelog/changes
touch platform-core/src/main/resources/db/changelog/changes/.gitkeep
```

- [ ] **Step 3: 启动 Spring Boot 进程并验证 `/health`**

```bash
docker compose up -d
mvn -pl server -am spring-boot:run &
SERVER_PID=$!
# 等待 30 秒让进程起来
sleep 30
curl -s http://localhost:8080/health
kill $SERVER_PID
```

Expected: `curl` 返回 `{"success":true,"data":{"status":"UP"},"error":null}` 类似的 JSON。

> 此时 Spring Security 默认会拦所有 endpoint。如果 `/health` 返回 401，把 `application.yml` 加临时放行配置或先用 `--basic auth user/<console password>` 调通。**Task 16** 会落最终的 SecurityConfig，会显式放行 `/health` 与 `/api/v1/auth/**`。本任务允许暂时手动放行 `/health` 验证。

简化：本任务里直接加一个最小 SecurityConfig 放行 `/health`，避免来回返工。

- [ ] **Step 4: 写最小 `SecurityConfig.java`（后续 Task 16 会覆盖）**

`server/src/main/java/io/kyligence/ragagent/server/config/SecurityConfig.java`：

```java
package io.kyligence.ragagent.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health").permitAll()
                .anyRequest().permitAll()  // Task 16 会改成 .authenticated()
            );
        return http.build();
    }
}
```

- [ ] **Step 5: 重新启动并验证**

```bash
mvn -pl server -am spring-boot:run &
SERVER_PID=$!
sleep 30
curl -s http://localhost:8080/health
kill $SERVER_PID
```

Expected: 同 Step 3 的 JSON。控制台日志里能看到 `Liquibase: Successfully acquired change log lock` + `Successfully released change log lock`（master changelog 是空 includeAll，所以没有变更）。

- [ ] **Step 6: Commit**

```bash
git add server/src
git commit -m "feat(server): wire Liquibase master changelog + bootstrap security"
```

---

## Task 8: Workspace 表 + 实体 + Mapper + 测试

**Files:**
- Create: `platform-core/src/main/resources/db/changelog/changes/001-workspace.yaml`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/Workspace.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/WorkspaceMapper.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/WorkspaceMapperTest.java`

- [ ] **Step 1: 写 Liquibase changelog `001-workspace.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-workspace
      author: ragagent
      changes:
        - createTable:
            tableName: workspace
            columns:
              - column: { name: id,         type: BIGINT,        autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: name,       type: VARCHAR(128),  constraints: { nullable: false } }
              - column: { name: owner_id,   type: BIGINT,        constraints: { nullable: false } }
              - column: { name: created_at, type: DATETIME,      defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: updated_at, type: DATETIME,      defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: workspace
            columnNames: "owner_id, name"
            constraintName: uq_workspace_owner_name
```

- [ ] **Step 2: 写 `Workspace.java` 实体**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("workspace")
public class Workspace {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long ownerId;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 写 `WorkspaceMapper.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {
}
```

- [ ] **Step 4: 写 Testcontainers 集成测试 `WorkspaceMapperTest.java`**

```java
package io.kyligence.ragagent.core.auth;

import io.kyligence.ragagent.core.config.MybatisPlusTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = WorkspaceMapperTest.TestApp.class)
@Testcontainers
class WorkspaceMapperTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent")
            .withUsername("ragagent")
            .withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
        reg.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
    }

    @Autowired WorkspaceMapper workspaceMapper;

    @Test
    void insertAndLoadWorkspace() {
        Workspace w = new Workspace();
        w.setName("acme");
        w.setOwnerId(42L);
        workspaceMapper.insert(w);

        assertThat(w.getId()).isNotNull();
        Workspace loaded = workspaceMapper.selectById(w.getId());
        assertThat(loaded.getName()).isEqualTo("acme");
        assertThat(loaded.getOwnerId()).isEqualTo(42L);
    }

    @SpringBootApplication
    @MapperScan("io.kyligence.ragagent.core.auth")
    static class TestApp {}
}
```

> 集成测试需要测试用的 Liquibase changelog 在 classpath 上。`server` 的 `db/changelog/` 在 `server` module，但 `platform-core` test 看不到。处理方式：把 changelog **从 server 移到 platform-core**，再让 server 通过依赖间接拿到。下一步执行迁移。

- [ ] **Step 5: 确认 Step 1 写的 `001-workspace.yaml` 落在 `platform-core/src/main/resources/db/changelog/changes/`**

Task 7 已经把 changelog 根目录定在 `platform-core` 下，所以 Step 1 写的文件直接落在那里即可。无需 git mv。

- [ ] **Step 6: 跑测试**

Run: `mvn -pl platform-core -am test -Dtest=WorkspaceMapperTest`
Expected: BUILD SUCCESS, `Tests run: 1, Failures: 0`。

> 如果首次拉 `mysql:8.0` 较慢，可预先 `docker pull mysql:8.0`。

- [ ] **Step 7: Commit**

```bash
git add platform-core server
git commit -m "feat(core): workspace entity + mapper + 001 liquibase changelog"
```

---

## Task 9: User 表 + 实体 + Mapper + BCrypt 密码 + 测试

**Files:**
- Create: `platform-core/src/main/resources/db/changelog/changes/002-user.yaml`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/User.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/UserMapper.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/UserMapperTest.java`

- [ ] **Step 1: 写 `002-user.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-user
      author: ragagent
      changes:
        - createTable:
            tableName: user
            columns:
              - column: { name: id,            type: BIGINT,       autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: email,         type: VARCHAR(255), constraints: { nullable: false } }
              - column: { name: password_hash, type: VARCHAR(255), constraints: { nullable: false } }
              - column: { name: display_name,  type: VARCHAR(128) }
              - column: { name: created_at,    type: DATETIME,     defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: updated_at,    type: DATETIME,     defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: user
            columnNames: email
            constraintName: uq_user_email
```

- [ ] **Step 2: 写 `User.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String passwordHash;
    private String displayName;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 写 `UserMapper.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
```

- [ ] **Step 4: 写测试 `UserMapperTest.java`**

复用 Task 8 的 Testcontainers 结构，单独验证 unique email 约束：

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = UserMapperTest.TestApp.class)
@Testcontainers
class UserMapperTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent").withUsername("ragagent").withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
        reg.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
    }

    @Autowired UserMapper userMapper;

    @Test
    void insertAndFindByEmail() {
        User u = new User();
        u.setEmail("alice@example.com");
        u.setPasswordHash("hash");
        u.setDisplayName("Alice");
        userMapper.insert(u);

        User loaded = userMapper.selectOne(
            Wrappers.<User>lambdaQuery().eq(User::getEmail, "alice@example.com"));
        assertThat(loaded.getId()).isEqualTo(u.getId());
    }

    @Test
    void rejectsDuplicateEmail() {
        User u1 = new User();
        u1.setEmail("dup@example.com"); u1.setPasswordHash("a");
        userMapper.insert(u1);

        User u2 = new User();
        u2.setEmail("dup@example.com"); u2.setPasswordHash("b");
        assertThatThrownBy(() -> userMapper.insert(u2))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @SpringBootApplication
    @MapperScan("io.kyligence.ragagent.core.auth")
    static class TestApp {}
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl platform-core -am test -Dtest=UserMapperTest`
Expected: 2 个测试通过。

- [ ] **Step 6: Commit**

```bash
git add platform-core
git commit -m "feat(core): user entity + mapper + 002 changelog"
```

---

## Task 10: WorkspaceMember + Role 枚举 + 003 changelog

**Files:**
- Create: `platform-core/src/main/resources/db/changelog/changes/003-workspace-member.yaml`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/Role.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/WorkspaceMember.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/WorkspaceMemberMapper.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/WorkspaceMemberMapperTest.java`

- [ ] **Step 1: 写 `Role.java`**

```java
package io.kyligence.ragagent.core.auth;

public enum Role { OWNER, ADMIN, MEMBER, VIEWER }
```

- [ ] **Step 2: 写 `003-workspace-member.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 003-workspace-member
      author: ragagent
      changes:
        - createTable:
            tableName: workspace_member
            columns:
              - column: { name: id,            type: BIGINT,      autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id,  type: BIGINT,      constraints: { nullable: false } }
              - column: { name: user_id,       type: BIGINT,      constraints: { nullable: false } }
              - column: { name: role,          type: VARCHAR(16), constraints: { nullable: false } }
              - column: { name: created_at,    type: DATETIME,    defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: workspace_member
            columnNames: "workspace_id, user_id"
            constraintName: uq_member_ws_user
        - createIndex:
            tableName: workspace_member
            indexName: idx_member_user
            columns:
              - column: { name: user_id }
```

- [ ] **Step 3: 写 `WorkspaceMember.java` 和 `WorkspaceMemberMapper.java`**

`WorkspaceMember.java`：

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("workspace_member")
public class WorkspaceMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long userId;
    private Role role;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

`WorkspaceMemberMapper.java`：

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {
}
```

MyBatis-Plus 默认按 `enum.name()` 写库；如果想用 `@EnumValue` 自定义值就标在 enum 字段上。这里使用默认 `name()`，table 是 `VARCHAR(16)`。

- [ ] **Step 4: 写 `WorkspaceMemberMapperTest.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = WorkspaceMemberMapperTest.TestApp.class)
@Testcontainers
class WorkspaceMemberMapperTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent").withUsername("ragagent").withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
        reg.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
    }

    @Autowired WorkspaceMemberMapper memberMapper;

    @Test
    void insertAndQueryByRole() {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(1L); m.setUserId(2L); m.setRole(Role.OWNER);
        memberMapper.insert(m);

        WorkspaceMember loaded = memberMapper.selectOne(
            Wrappers.<WorkspaceMember>lambdaQuery()
                .eq(WorkspaceMember::getWorkspaceId, 1L)
                .eq(WorkspaceMember::getUserId, 2L));
        assertThat(loaded.getRole()).isEqualTo(Role.OWNER);
    }

    @SpringBootApplication
    @MapperScan("io.kyligence.ragagent.core.auth")
    static class TestApp {}
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl platform-core -am test -Dtest=WorkspaceMemberMapperTest`
Expected: 1 passed。

- [ ] **Step 6: Commit**

```bash
git add platform-core
git commit -m "feat(core): workspace_member + Role enum + 003 changelog"
```

---

## Task 11: ApiKey 表 + 实体 + Mapper + 004 changelog

**Files:**
- Create: `platform-core/src/main/resources/db/changelog/changes/004-api-key.yaml`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/ApiKey.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/ApiKeyMapper.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/ApiKeyMapperTest.java`

- [ ] **Step 1: 写 `004-api-key.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-api-key
      author: ragagent
      changes:
        - createTable:
            tableName: api_key
            columns:
              - column: { name: id,            type: BIGINT,       autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id,  type: BIGINT,       constraints: { nullable: false } }
              - column: { name: user_id,       type: BIGINT,       constraints: { nullable: false } }
              - column: { name: name,          type: VARCHAR(128), constraints: { nullable: false } }
              - column: { name: prefix,        type: VARCHAR(8),   constraints: { nullable: false } }
              - column: { name: secret_hash,   type: VARCHAR(255), constraints: { nullable: false } }
              - column: { name: created_at,    type: DATETIME,     defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: last_used_at,  type: DATETIME }
              - column: { name: revoked_at,    type: DATETIME }
        - createIndex:
            tableName: api_key
            indexName: idx_api_key_prefix
            columns:
              - column: { name: prefix }
```

> 设计：完整 key 形如 `rak_<prefix>_<secret>`；存表的是 `prefix`（用于检索）+ `secret_hash`（BCrypt）。验证时按 prefix 取候选，再 BCrypt 比对 secret。

- [ ] **Step 2: 写 `ApiKey.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long userId;
    private String name;
    private String prefix;
    private String secretHash;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
```

- [ ] **Step 3: 写 `ApiKeyMapper.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
```

- [ ] **Step 4: 写测试 `ApiKeyMapperTest.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ApiKeyMapperTest.TestApp.class)
@Testcontainers
class ApiKeyMapperTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent").withUsername("ragagent").withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
        reg.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
    }

    @Autowired ApiKeyMapper apiKeyMapper;

    @Test
    void findByPrefix() {
        ApiKey k = new ApiKey();
        k.setWorkspaceId(1L); k.setUserId(2L);
        k.setName("test"); k.setPrefix("abcd1234"); k.setSecretHash("hash");
        apiKeyMapper.insert(k);

        ApiKey loaded = apiKeyMapper.selectOne(
            Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getPrefix, "abcd1234"));
        assertThat(loaded.getName()).isEqualTo("test");
        assertThat(loaded.getRevokedAt()).isNull();
    }

    @SpringBootApplication
    @MapperScan("io.kyligence.ragagent.core.auth")
    static class TestApp {}
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl platform-core -am test -Dtest=ApiKeyMapperTest`
Expected: 1 passed。

- [ ] **Step 6: Commit**

```bash
git add platform-core
git commit -m "feat(core): api_key entity + mapper + 004 changelog"
```

---

## Task 12: WorkspaceContext + WorkspaceContextHolder

**Files:**
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/tenant/WorkspaceContext.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/tenant/WorkspaceContextHolder.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/tenant/WorkspaceContextHolderTest.java`

- [ ] **Step 1: 写测试 `WorkspaceContextHolderTest.java`**

```java
package io.kyligence.ragagent.core.tenant;

import io.kyligence.ragagent.core.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceContextHolderTest {

    @AfterEach
    void cleanup() { WorkspaceContextHolder.clear(); }

    @Test
    void setAndGet() {
        WorkspaceContextHolder.set(new WorkspaceContext(7L, 42L, Role.ADMIN));
        WorkspaceContext ctx = WorkspaceContextHolder.require();
        assertThat(ctx.workspaceId()).isEqualTo(7L);
        assertThat(ctx.userId()).isEqualTo(42L);
        assertThat(ctx.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void requireThrowsWhenAbsent() {
        assertThatThrownBy(WorkspaceContextHolder::require)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getReturnsNullWhenAbsent() {
        assertThat(WorkspaceContextHolder.get()).isNull();
    }

    @Test
    void isolatedAcrossThreads() throws Exception {
        WorkspaceContextHolder.set(new WorkspaceContext(1L, 1L, Role.OWNER));
        final WorkspaceContext[] otherThreadValue = { new WorkspaceContext(99L, 99L, Role.OWNER) };
        Thread t = new Thread(() -> otherThreadValue[0] = WorkspaceContextHolder.get());
        t.start(); t.join();
        assertThat(otherThreadValue[0]).isNull();
    }
}
```

- [ ] **Step 2: 跑测试，期望失败**

Run: `mvn -pl platform-core -Dtest=WorkspaceContextHolderTest test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写 `WorkspaceContext.java`**

```java
package io.kyligence.ragagent.core.tenant;

import io.kyligence.ragagent.core.auth.Role;

public record WorkspaceContext(Long workspaceId, Long userId, Role role) {
    public WorkspaceContext {
        if (workspaceId == null) throw new IllegalArgumentException("workspaceId required");
        if (userId == null) throw new IllegalArgumentException("userId required");
        if (role == null) throw new IllegalArgumentException("role required");
    }
}
```

- [ ] **Step 4: 写 `WorkspaceContextHolder.java`**

```java
package io.kyligence.ragagent.core.tenant;

public final class WorkspaceContextHolder {

    private static final ThreadLocal<WorkspaceContext> HOLDER = new ThreadLocal<>();

    private WorkspaceContextHolder() {}

    public static void set(WorkspaceContext ctx) {
        HOLDER.set(ctx);
    }

    public static WorkspaceContext get() {
        return HOLDER.get();
    }

    public static WorkspaceContext require() {
        WorkspaceContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("WorkspaceContext not set");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl platform-core -Dtest=WorkspaceContextHolderTest test`
Expected: 4 passed。

- [ ] **Step 6: Commit**

```bash
git add platform-core
git commit -m "feat(tenant): WorkspaceContext + ThreadLocal holder"
```

---

## Task 13: WorkspaceFilterInterceptor（MyBatis-Plus inner interceptor 注入 workspace_id）

**Files:**
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/tenant/WorkspaceAware.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/tenant/WorkspaceFilterInterceptor.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/tenant/WorkspaceFilterInterceptorTest.java`

> 实现思路：用 MyBatis-Plus 自带的 `TenantLineInnerInterceptor`，通过 `TenantLineHandler` 注入 `workspaceId`。任何 table 有 `workspace_id` 列的，select/update/delete 都会自动加 `WHERE workspace_id = ?`；insert 自动补 `workspace_id` 列。`workspace` / `user` / `api_key` 这些非业务表通过 `ignoreTable()` 排除。

- [ ] **Step 1: 写 `WorkspaceAware.java`（标记接口/类级注解，留给未来扩展，本任务不强依赖）**

```java
package io.kyligence.ragagent.core.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marker: mapper operating on a table with workspace_id column. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WorkspaceAware {
}
```

- [ ] **Step 2: 写 `WorkspaceFilterInterceptor.java`**

```java
package io.kyligence.ragagent.core.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

public class WorkspaceFilterInterceptor implements TenantLineHandler {

    /** Tables that don't have workspace_id column. */
    private static final Set<String> EXCLUDED = Set.of(
        "workspace", "user", "workspace_member", "api_key",
        "DATABASECHANGELOG", "DATABASECHANGELOGLOCK"
    );

    @Override
    public Expression getTenantId() {
        WorkspaceContext ctx = WorkspaceContextHolder.get();
        if (ctx == null) {
            // No tenant set: return -1 so query yields zero rows, never leaks.
            return new LongValue(-1L);
        }
        return new LongValue(ctx.workspaceId());
    }

    @Override
    public String getTenantIdColumn() {
        return "workspace_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return EXCLUDED.contains(tableName.toLowerCase());
    }
}
```

- [ ] **Step 3: 写单元测试 `WorkspaceFilterInterceptorTest.java`（纯逻辑测试，不需要 DB）**

```java
package io.kyligence.ragagent.core.tenant;

import io.kyligence.ragagent.core.auth.Role;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFilterInterceptorTest {

    private final WorkspaceFilterInterceptor handler = new WorkspaceFilterInterceptor();

    @AfterEach
    void clean() { WorkspaceContextHolder.clear(); }

    @Test
    void tenantIdComesFromHolder() {
        WorkspaceContextHolder.set(new WorkspaceContext(123L, 1L, Role.MEMBER));
        LongValue v = (LongValue) handler.getTenantId();
        assertThat(v.getValue()).isEqualTo(123L);
    }

    @Test
    void noTenantSetReturnsMinusOne() {
        LongValue v = (LongValue) handler.getTenantId();
        assertThat(v.getValue()).isEqualTo(-1L);
    }

    @Test
    void excludedTables() {
        assertThat(handler.ignoreTable("workspace")).isTrue();
        assertThat(handler.ignoreTable("user")).isTrue();
        assertThat(handler.ignoreTable("api_key")).isTrue();
        assertThat(handler.ignoreTable("DATABASECHANGELOG")).isTrue();
        assertThat(handler.ignoreTable("knowledge_base")).isFalse(); // future business table
    }

    @Test
    void tenantIdColumnName() {
        assertThat(handler.getTenantIdColumn()).isEqualTo("workspace_id");
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -pl platform-core -Dtest=WorkspaceFilterInterceptorTest test`
Expected: 4 passed。

- [ ] **Step 5: Commit**

```bash
git add platform-core
git commit -m "feat(tenant): WorkspaceFilterInterceptor + @WorkspaceAware marker"
```

---

## Task 14: MybatisPlusConfig 注册 interceptor + 集成测试验证 SQL 注入

**Files:**
- Create: `server/src/main/java/io/kyligence/ragagent/server/config/MybatisPlusConfig.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/tenant/WorkspaceFilterIntegrationTest.java`

- [ ] **Step 1: 写 `MybatisPlusConfig.java`**

```java
package io.kyligence.ragagent.server.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.kyligence.ragagent.core.tenant.WorkspaceFilterInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
    "io.kyligence.ragagent.core.auth",
    // future: rag, agent, workflow mapper packages
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new WorkspaceFilterInterceptor()));
        return interceptor;
    }
}
```

- [ ] **Step 2: 写 `WorkspaceFilterIntegrationTest.java`（造一张带 workspace_id 的表，验证 select 自动加过滤）**

需要先在 test changelog 里加一张测试表。在测试 resources 写一个临时 changelog：

`platform-core/src/test/resources/db/changelog/test-only.yaml`：

```yaml
databaseChangeLog:
  - changeSet:
      id: test-only-widget
      author: ragagent
      changes:
        - createTable:
            tableName: widget
            columns:
              - column: { name: id,            type: BIGINT,       autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id,  type: BIGINT,       constraints: { nullable: false } }
              - column: { name: name,          type: VARCHAR(64),  constraints: { nullable: false } }
```

并在 test resources 写一个测试专用 master：

`platform-core/src/test/resources/db/changelog/test-master.yaml`：

```yaml
databaseChangeLog:
  - include:
      file: classpath:db/changelog/db.changelog-master.yaml
  - include:
      file: classpath:db/changelog/test-only.yaml
```

测试代码：

```java
package io.kyligence.ragagent.core.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.kyligence.ragagent.core.auth.Role;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = WorkspaceFilterIntegrationTest.TestApp.class)
@Testcontainers
class WorkspaceFilterIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent").withUsername("ragagent").withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
        reg.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/test-master.yaml");
    }

    @Autowired WidgetMapper widgetMapper;

    @AfterEach
    void clean() { WorkspaceContextHolder.clear(); }

    @Test
    void selectFiltersByWorkspace() {
        WorkspaceContextHolder.set(new WorkspaceContext(1L, 100L, Role.OWNER));
        Widget w1 = new Widget(); w1.setName("alpha");
        widgetMapper.insert(w1);

        WorkspaceContextHolder.set(new WorkspaceContext(2L, 100L, Role.OWNER));
        Widget w2 = new Widget(); w2.setName("beta");
        widgetMapper.insert(w2);

        WorkspaceContextHolder.set(new WorkspaceContext(1L, 100L, Role.OWNER));
        assertThat(widgetMapper.selectList(null))
            .hasSize(1)
            .extracting(Widget::getName).containsExactly("alpha");

        WorkspaceContextHolder.set(new WorkspaceContext(2L, 100L, Role.OWNER));
        assertThat(widgetMapper.selectList(null))
            .hasSize(1)
            .extracting(Widget::getName).containsExactly("beta");
    }

    @Test
    void noContextReturnsEmpty() {
        // Cross-ws data should not leak when context unset
        WorkspaceContextHolder.set(new WorkspaceContext(3L, 100L, Role.OWNER));
        Widget w = new Widget(); w.setName("seed"); widgetMapper.insert(w);
        WorkspaceContextHolder.clear();
        assertThat(widgetMapper.selectList(null)).isEmpty();
    }

    @TableName("widget")
    public static class Widget {
        @TableId(type = IdType.AUTO) private Long id;
        private Long workspaceId;
        private String name;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getWorkspaceId() { return workspaceId; }
        public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Mapper
    public interface WidgetMapper extends BaseMapper<Widget> {}

    @SpringBootApplication
    @MapperScan(basePackageClasses = WorkspaceFilterIntegrationTest.WidgetMapper.class)
    static class TestApp {
        @Bean
        public MybatisPlusInterceptor mpInterceptor() {
            MybatisPlusInterceptor i = new MybatisPlusInterceptor();
            i.addInnerInterceptor(new TenantLineInnerInterceptor(new WorkspaceFilterInterceptor()));
            return i;
        }
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `mvn -pl platform-core -Dtest=WorkspaceFilterIntegrationTest test`
Expected: 2 passed。

- [ ] **Step 4: Commit**

```bash
git add platform-core server
git commit -m "feat(server): wire workspace tenant interceptor + integration tests"
```

---

## Task 15: JwtTokenProvider

**Files:**
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/JwtTokenProvider.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/JwtProperties.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/JwtTokenProviderTest.java`

- [ ] **Step 1: 写测试 `JwtTokenProviderTest.java`**

```java
package io.kyligence.ragagent.core.auth;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private final JwtProperties props = new JwtProperties(
        "test-secret-key-test-secret-key-test-secret-key",
        Duration.ofMinutes(60),
        Duration.ofDays(30),
        "test-issuer"
    );
    private final JwtTokenProvider provider = new JwtTokenProvider(props);

    @Test
    void issueAndParseAccess() {
        String token = provider.issueAccess(42L, "alice@example.com");
        JwtTokenProvider.Claims c = provider.parse(token);
        assertThat(c.userId()).isEqualTo(42L);
        assertThat(c.email()).isEqualTo("alice@example.com");
        assertThat(c.type()).isEqualTo(JwtTokenProvider.TokenType.ACCESS);
    }

    @Test
    void issueAndParseRefresh() {
        String token = provider.issueRefresh(7L);
        JwtTokenProvider.Claims c = provider.parse(token);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.type()).isEqualTo(JwtTokenProvider.TokenType.REFRESH);
    }

    @Test
    void expiredTokenRejected() {
        JwtProperties expired = new JwtProperties(
            props.secret(), Duration.ofMillis(-1), Duration.ofMillis(-1), props.issuer());
        JwtTokenProvider exp = new JwtTokenProvider(expired);
        String t = exp.issueAccess(1L, "x@y.z");
        assertThatThrownBy(() -> exp.parse(t)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedTokenRejected() {
        String t = provider.issueAccess(1L, "x@y.z");
        String tampered = t.substring(0, t.length() - 4) + "AAAA";
        assertThatThrownBy(() -> provider.parse(tampered)).isInstanceOf(Exception.class);
    }
}
```

- [ ] **Step 2: 跑测试，期望失败**

Run: `mvn -pl platform-core -Dtest=JwtTokenProviderTest test`
Expected: 编译失败。

- [ ] **Step 3: 写 `JwtProperties.java`**

```java
package io.kyligence.ragagent.core.auth;

import java.time.Duration;

public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl, String issuer) {
    public JwtProperties {
        if (secret == null || secret.length() < 32)
            throw new IllegalArgumentException("jwt.secret must be >= 32 chars");
        if (issuer == null || issuer.isBlank())
            throw new IllegalArgumentException("jwt.issuer required");
    }
}
```

- [ ] **Step 4: 写 `JwtTokenProvider.java`**

```java
package io.kyligence.ragagent.core.auth;

import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public class JwtTokenProvider {

    public enum TokenType { ACCESS, REFRESH }

    public record Claims(Long userId, String email, TokenType type, Instant issuedAt, Instant expiresAt) {}

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_EMAIL = "email";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccess(Long userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim(CLAIM_TYPE, TokenType.ACCESS.name())
            .claim(CLAIM_EMAIL, email)
            .issuer(props.issuer())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(props.accessTtl())))
            .signWith(key)
            .compact();
    }

    public String issueRefresh(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim(CLAIM_TYPE, TokenType.REFRESH.name())
            .issuer(props.issuer())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(props.refreshTtl())))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        Jws<io.jsonwebtoken.Claims> jws = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.issuer())
            .build()
            .parseSignedClaims(token);
        io.jsonwebtoken.Claims c = jws.getPayload();
        TokenType type = TokenType.valueOf(c.get(CLAIM_TYPE, String.class));
        String email = c.get(CLAIM_EMAIL, String.class);
        return new Claims(
            Long.valueOf(c.getSubject()),
            email,
            type,
            c.getIssuedAt().toInstant(),
            c.getExpiration().toInstant()
        );
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl platform-core -Dtest=JwtTokenProviderTest test`
Expected: 4 passed。

- [ ] **Step 6: 在 `application.yml` 把 `ragagent.auth.jwt.*` 绑成 `JwtProperties` Bean，避免后续重复 wiring**

新建 `platform-core/src/main/java/io/kyligence/ragagent/core/auth/AuthAutoConfig.java`：

```java
package io.kyligence.ragagent.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

@Configuration
public class AuthAutoConfig {

    @ConfigurationProperties(prefix = "ragagent.auth.jwt")
    static class JwtPropsBinding {
        public String secret;
        public Duration accessTtl;
        public Duration refreshTtl;
        public String issuer;
    }

    @Bean
    JwtPropsBinding jwtPropsBinding() { return new JwtPropsBinding(); }

    @Bean
    JwtProperties jwtProperties(JwtPropsBinding b) {
        return new JwtProperties(b.secret, b.accessTtl, b.refreshTtl, b.issuer);
    }

    @Bean
    JwtTokenProvider jwtTokenProvider(JwtProperties p) {
        return new JwtTokenProvider(p);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add platform-core
git commit -m "feat(auth): JwtTokenProvider + JwtProperties + BCrypt passwordEncoder"
```

---

## Task 16: AuthService（register / login / refresh / createWorkspaceForOwner）

**Files:**
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/AuthExceptions.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/AuthService.java`
- Test: `platform-core/src/test/java/io/kyligence/ragagent/core/auth/AuthServiceTest.java`

- [ ] **Step 1: 写 `AuthExceptions.java`**

```java
package io.kyligence.ragagent.core.auth;

import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;

public final class AuthExceptions {
    private AuthExceptions() {}

    public static PlatformException emailTaken(String email) {
        return new PlatformException(ErrorCode.CONFLICT, "email already registered: " + email);
    }

    public static PlatformException invalidCredentials() {
        return new PlatformException(ErrorCode.UNAUTHORIZED, "invalid email or password");
    }

    public static PlatformException invalidRefreshToken() {
        return new PlatformException(ErrorCode.UNAUTHORIZED, "invalid refresh token");
    }

    public static PlatformException userNotFound() {
        return new PlatformException(ErrorCode.NOT_FOUND, "user not found");
    }
}
```

- [ ] **Step 2: 写 `AuthService.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public record RegisterCommand(String email, String password, String displayName) {}
    public record LoginCommand(String email, String password) {}
    public record TokenPair(String accessToken, String refreshToken) {}

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final JwtTokenProvider jwt;
    private final PasswordEncoder pwd;

    public AuthService(UserMapper userMapper,
                       WorkspaceMapper workspaceMapper,
                       WorkspaceMemberMapper memberMapper,
                       JwtTokenProvider jwt,
                       PasswordEncoder pwd) {
        this.userMapper = userMapper;
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.jwt = jwt;
        this.pwd = pwd;
    }

    @Transactional
    public TokenPair register(RegisterCommand cmd) {
        User existing = userMapper.selectOne(
            Wrappers.<User>lambdaQuery().eq(User::getEmail, cmd.email()));
        if (existing != null) throw AuthExceptions.emailTaken(cmd.email());

        User u = new User();
        u.setEmail(cmd.email());
        u.setPasswordHash(pwd.encode(cmd.password()));
        u.setDisplayName(cmd.displayName());
        userMapper.insert(u);

        // Default workspace per user
        Workspace ws = new Workspace();
        ws.setName(cmd.email() + "'s workspace");
        ws.setOwnerId(u.getId());
        workspaceMapper.insert(ws);

        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(ws.getId());
        m.setUserId(u.getId());
        m.setRole(Role.OWNER);
        memberMapper.insert(m);

        return new TokenPair(
            jwt.issueAccess(u.getId(), u.getEmail()),
            jwt.issueRefresh(u.getId())
        );
    }

    public TokenPair login(LoginCommand cmd) {
        User u = userMapper.selectOne(
            Wrappers.<User>lambdaQuery().eq(User::getEmail, cmd.email()));
        if (u == null) throw AuthExceptions.invalidCredentials();
        if (!pwd.matches(cmd.password(), u.getPasswordHash())) throw AuthExceptions.invalidCredentials();
        return new TokenPair(
            jwt.issueAccess(u.getId(), u.getEmail()),
            jwt.issueRefresh(u.getId())
        );
    }

    public TokenPair refresh(String refreshToken) {
        JwtTokenProvider.Claims c;
        try {
            c = jwt.parse(refreshToken);
        } catch (Exception e) {
            throw AuthExceptions.invalidRefreshToken();
        }
        if (c.type() != JwtTokenProvider.TokenType.REFRESH) throw AuthExceptions.invalidRefreshToken();
        User u = userMapper.selectById(c.userId());
        if (u == null) throw AuthExceptions.userNotFound();
        return new TokenPair(
            jwt.issueAccess(u.getId(), u.getEmail()),
            jwt.issueRefresh(u.getId())
        );
    }
}
```

- [ ] **Step 3: 写 Mockito 单元测试 `AuthServiceTest.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    UserMapper userMapper;
    WorkspaceMapper workspaceMapper;
    WorkspaceMemberMapper memberMapper;
    JwtTokenProvider jwt;
    PasswordEncoder pwd;
    AuthService service;

    @BeforeEach
    void setup() {
        userMapper = mock(UserMapper.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        memberMapper = mock(WorkspaceMemberMapper.class);
        pwd = mock(PasswordEncoder.class);
        jwt = new JwtTokenProvider(new JwtProperties(
            "test-secret-key-test-secret-key-test-secret-key",
            Duration.ofMinutes(60), Duration.ofDays(30), "test"));
        service = new AuthService(userMapper, workspaceMapper, memberMapper, jwt, pwd);
    }

    @Test
    void registerSucceedsForNewEmail() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(pwd.encode("pw")).thenReturn("hash");
        doAnswer(inv -> { ((User)inv.getArgument(0)).setId(1L); return 1; })
            .when(userMapper).insert(any(User.class));
        doAnswer(inv -> { ((Workspace)inv.getArgument(0)).setId(10L); return 1; })
            .when(workspaceMapper).insert(any(Workspace.class));

        AuthService.TokenPair p = service.register(
            new AuthService.RegisterCommand("a@b.c", "pw", "A"));

        assertThat(p.accessToken()).isNotBlank();
        assertThat(p.refreshToken()).isNotBlank();
        verify(memberMapper).insert(argThat(m ->
            m.getRole() == Role.OWNER && m.getUserId() == 1L && m.getWorkspaceId() == 10L));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        User existing = new User(); existing.setEmail("a@b.c");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        assertThatThrownBy(() -> service.register(
            new AuthService.RegisterCommand("a@b.c", "pw", "A")))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void loginSucceedsWithGoodPassword() {
        User u = new User();
        u.setId(1L); u.setEmail("a@b.c"); u.setPasswordHash("hashed");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(u);
        when(pwd.matches("pw", "hashed")).thenReturn(true);

        AuthService.TokenPair p = service.login(new AuthService.LoginCommand("a@b.c", "pw"));
        assertThat(p.accessToken()).isNotBlank();
    }

    @Test
    void loginRejectsBadPassword() {
        User u = new User();
        u.setId(1L); u.setEmail("a@b.c"); u.setPasswordHash("hashed");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(u);
        when(pwd.matches("pw", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new AuthService.LoginCommand("a@b.c", "pw")))
            .isInstanceOf(PlatformException.class)
            .hasMessageContaining("invalid email or password");
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.login(new AuthService.LoginCommand("x@y.z", "pw")))
            .isInstanceOf(PlatformException.class);
    }

    @Test
    void refreshRotatesTokens() {
        User u = new User(); u.setId(42L); u.setEmail("a@b.c");
        when(userMapper.selectById(42L)).thenReturn(u);

        String refreshToken = jwt.issueRefresh(42L);
        AuthService.TokenPair p = service.refresh(refreshToken);
        assertThat(p.accessToken()).isNotBlank();
        assertThat(p.refreshToken()).isNotBlank();
    }

    @Test
    void refreshRejectsAccessTokenAsRefresh() {
        String accessToken = jwt.issueAccess(1L, "a@b.c");
        assertThatThrownBy(() -> service.refresh(accessToken))
            .isInstanceOf(PlatformException.class);
    }

    @Test
    void refreshRejectsGarbageToken() {
        assertThatThrownBy(() -> service.refresh("not.a.jwt"))
            .isInstanceOf(PlatformException.class);
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -pl platform-core -Dtest=AuthServiceTest test`
Expected: 8 passed。

- [ ] **Step 5: Commit**

```bash
git add platform-core
git commit -m "feat(auth): AuthService register/login/refresh + provisions default workspace"
```

---

## Task 17: AuthController（REST endpoint）+ MockMvc test

**Files:**
- Create: `server/src/main/java/io/kyligence/ragagent/server/controller/AuthController.java`
- Create: `server/src/main/java/io/kyligence/ragagent/server/controller/GlobalExceptionHandler.java`
- Test: `server/src/test/java/io/kyligence/ragagent/server/controller/AuthControllerTest.java`

- [ ] **Step 1: 写 `GlobalExceptionHandler.java`**

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.shared.api.ApiResponse;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatform(PlatformException ex) {
        HttpStatus status = switch (ex.code()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
            .body(ApiResponse.error(ex.code().code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL.code(), ex.getMessage()));
    }
}
```

- [ ] **Step 2: 写 `AuthController.java`**

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.auth.AuthService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 128) String displayName) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    public ApiResponse<AuthService.TokenPair> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(auth.register(
            new AuthService.RegisterCommand(req.email(), req.password(), req.displayName())));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.TokenPair> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(auth.login(
            new AuthService.LoginCommand(req.email(), req.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.TokenPair> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(auth.refresh(req.refreshToken()));
    }
}
```

- [ ] **Step 3: 写 MockMvc 测试 `AuthControllerTest.java`**

```java
package io.kyligence.ragagent.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.core.auth.AuthService;
import io.kyligence.ragagent.core.auth.AuthExceptions;
import io.kyligence.ragagent.server.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AuthService authService;
    // Required after Task 18 wires ApiKeyService into SecurityConfig; harmless before.
    @MockBean io.kyligence.ragagent.core.auth.ApiKeyService apiKeyService;
    // Also harmless: JwtTokenProvider is wired by AuthAutoConfig once Task 15 lands.
    // If running this test BEFORE Task 15, comment the next line out.
    @MockBean io.kyligence.ragagent.core.auth.JwtTokenProvider jwtTokenProvider;

    @Test
    void registerReturnsTokens() throws Exception {
        when(authService.register(any())).thenReturn(new AuthService.TokenPair("acc", "ref"));

        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"a@b.c","password":"longenough","displayName":"A"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("acc"));
    }

    @Test
    void registerValidatesEmail() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"not-an-email","password":"longenough"}
                """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void registerValidatesPasswordLength() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"a@b.c","password":"short"}
                """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void loginUnauthorizedOnBadCreds() throws Exception {
        when(authService.login(any())).thenThrow(AuthExceptions.invalidCredentials());
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"a@b.c","password":"longenough"}
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("E_UNAUTHORIZED"));
    }

    @Test
    void refreshUnauthorizedOnBadToken() throws Exception {
        when(authService.refresh(any())).thenThrow(AuthExceptions.invalidRefreshToken());
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"refreshToken":"garbage"}
                """))
            .andExpect(status().isUnauthorized());
    }
}
```

注意：此处 `@Import(SecurityConfig.class)` 会引入 Task 7 写的"允许全部"那个临时 config。**Task 18 会把它替换成真实 SecurityConfig**，并保持 `/api/v1/auth/**` 放行；本测试通过。

- [ ] **Step 4: 跑测试**

Run: `mvn -pl server -am test -Dtest=AuthControllerTest`
Expected: 5 passed。

- [ ] **Step 5: Commit**

```bash
git add server
git commit -m "feat(server): auth REST endpoints + global exception handler"
```

---

## Task 18: SecurityConfig + JwtAuthenticationFilter + ApiKeyAuthenticationFilter

**Files:**
- Modify: `server/src/main/java/io/kyligence/ragagent/server/config/SecurityConfig.java` (Task 7 留的临时版本被本任务覆盖为真实版本)
- Create: `server/src/main/java/io/kyligence/ragagent/server/config/JwtAuthenticationFilter.java`
- Create: `server/src/main/java/io/kyligence/ragagent/server/config/ApiKeyAuthenticationFilter.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/ApiKeyService.java`
- Test: `server/src/test/java/io/kyligence/ragagent/server/config/SecurityFiltersTest.java`

- [ ] **Step 1: 写 `ApiKeyService.java`（验证 prefix+secret，更新 last_used_at）**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class ApiKeyService {

    public static final String KEY_PREFIX = "rak_";

    public record CreatedKey(ApiKey record, String plaintext) {}
    public record Resolved(ApiKey record, User user) {}

    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder pwd;

    public ApiKeyService(ApiKeyMapper apiKeyMapper, UserMapper userMapper, PasswordEncoder pwd) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
        this.pwd = pwd;
    }

    /** Verify a presented "rak_<prefix>_<secret>" token. */
    public Optional<Resolved> verify(String presented) {
        if (presented == null || !presented.startsWith(KEY_PREFIX)) return Optional.empty();
        String body = presented.substring(KEY_PREFIX.length());
        int sep = body.indexOf('_');
        if (sep < 0) return Optional.empty();
        String prefix = body.substring(0, sep);
        String secret = body.substring(sep + 1);

        ApiKey key = apiKeyMapper.selectOne(
            Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getPrefix, prefix));
        if (key == null || key.getRevokedAt() != null) return Optional.empty();
        if (!pwd.matches(secret, key.getSecretHash())) return Optional.empty();

        key.setLastUsedAt(Instant.now());
        apiKeyMapper.updateById(key);

        User user = userMapper.selectById(key.getUserId());
        if (user == null) return Optional.empty();
        return Optional.of(new Resolved(key, user));
    }
}
```

- [ ] **Step 2: 写 `JwtAuthenticationFilter.java`**

```java
package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.JwtTokenProvider;
import io.kyligence.ragagent.core.auth.Role;
import io.kyligence.ragagent.core.tenant.WorkspaceContext;
import io.kyligence.ragagent.core.tenant.WorkspaceContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwt;

    public JwtAuthenticationFilter(JwtTokenProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtTokenProvider.Claims c = jwt.parse(token);
                if (c.type() == JwtTokenProvider.TokenType.ACCESS) {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        c.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // workspaceId 由后续 WorkspaceController/header 决定;
                    // 为简化, 本 plan 期内 JWT 不带 workspace claim,
                    // 由请求 header X-Workspace-Id 指定, AOP 在 controller 入口 set.
                    String wsHeader = req.getHeader("X-Workspace-Id");
                    if (wsHeader != null) {
                        WorkspaceContextHolder.set(new WorkspaceContext(
                            Long.parseLong(wsHeader), c.userId(), Role.MEMBER));
                    }
                }
            } catch (Exception ignored) {
                // fall through unauthenticated
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            WorkspaceContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
```

> 设计取舍：JWT 不绑 workspace（用户可有多 workspace），由 `X-Workspace-Id` header 选择，每次请求显式。Workspace 成员校验留在 `WorkspaceController` / Plan B 的服务层做（本 Plan 不强校验，但 AOP 层 ws_id=-1 兜底）。

- [ ] **Step 3: 写 `ApiKeyAuthenticationFilter.java`**

```java
package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.ApiKey;
import io.kyligence.ragagent.core.auth.ApiKeyService;
import io.kyligence.ragagent.core.auth.Role;
import io.kyligence.ragagent.core.auth.User;
import io.kyligence.ragagent.core.tenant.WorkspaceContext;
import io.kyligence.ragagent.core.tenant.WorkspaceContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = req.getHeader("X-Api-Key");
            if (header != null) {
                Optional<ApiKeyService.Resolved> maybe = apiKeyService.verify(header);
                if (maybe.isPresent()) {
                    ApiKey k = maybe.get().record();
                    User u = maybe.get().user();
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        u.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_API")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    WorkspaceContextHolder.set(new WorkspaceContext(
                        k.getWorkspaceId(), u.getId(), Role.MEMBER));
                }
            }
        }
        chain.doFilter(req, resp);
    }
}
```

- [ ] **Step 4: 用真实 SecurityConfig 替换 Task 7 的临时版本**

```java
package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.ApiKeyService;
import io.kyligence.ragagent.core.auth.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwt,
                                                   ApiKeyService apiKeyService) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(jwt),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new ApiKeyAuthenticationFilter(apiKeyService),
                JwtAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 5: 写过滤器集成测试 `SecurityFiltersTest.java`**

```java
package io.kyligence.ragagent.server.config;

import io.kyligence.ragagent.core.auth.JwtProperties;
import io.kyligence.ragagent.core.auth.JwtTokenProvider;
import io.kyligence.ragagent.server.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, SecurityFiltersTest.TestBeans.class})
class SecurityFiltersTest {

    @Autowired MockMvc mvc;
    @MockBean io.kyligence.ragagent.core.auth.ApiKeyService apiKeyService;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithBadJwtIs401() throws Exception {
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer not.a.token"))
            .andExpect(status().isUnauthorized());
    }

    @Configuration
    static class TestBeans {
        @Bean
        JwtTokenProvider jwt() {
            return new JwtTokenProvider(new JwtProperties(
                "test-secret-key-test-secret-key-test-secret-key",
                Duration.ofMinutes(60), Duration.ofDays(30), "test"));
        }
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `mvn -pl server -am test -Dtest=SecurityFiltersTest,AuthControllerTest`
Expected: 全部通过（HealthController 已存在；`/api/v1/workspaces` Task 19 才会建，401 是因为 Spring Security 在 controller 不存在时也会先拦截）。

> 如果 `/api/v1/workspaces` 不存在导致返回 404 而非 401，调整测试断言为 `is4xxClientError()`。

- [ ] **Step 7: Commit**

```bash
git add platform-core server
git commit -m "feat(security): JWT + API key dual auth filters wired into Spring Security"
```

---

## Task 19: WorkspaceController（list / create / set-current）

**Files:**
- Create: `server/src/main/java/io/kyligence/ragagent/server/controller/WorkspaceController.java`
- Create: `platform-core/src/main/java/io/kyligence/ragagent/core/auth/WorkspaceService.java`
- Test: `server/src/test/java/io/kyligence/ragagent/server/controller/WorkspaceControllerTest.java`

- [ ] **Step 1: 写 `WorkspaceService.java`**

```java
package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceService {

    public record WorkspaceView(Long id, String name, Long ownerId, Role role) {}

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public WorkspaceService(WorkspaceMapper workspaceMapper, WorkspaceMemberMapper memberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    public List<WorkspaceView> listForUser(Long userId) {
        List<WorkspaceMember> ms = memberMapper.selectList(
            Wrappers.<WorkspaceMember>lambdaQuery().eq(WorkspaceMember::getUserId, userId));
        return ms.stream().map(m -> {
            Workspace w = workspaceMapper.selectById(m.getWorkspaceId());
            return new WorkspaceView(w.getId(), w.getName(), w.getOwnerId(), m.getRole());
        }).toList();
    }

    @Transactional
    public WorkspaceView create(Long userId, String name) {
        Workspace ws = new Workspace();
        ws.setName(name);
        ws.setOwnerId(userId);
        workspaceMapper.insert(ws);

        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(ws.getId());
        m.setUserId(userId);
        m.setRole(Role.OWNER);
        memberMapper.insert(m);

        return new WorkspaceView(ws.getId(), ws.getName(), ws.getOwnerId(), Role.OWNER);
    }
}
```

- [ ] **Step 2: 写 `WorkspaceController.java`**

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.auth.WorkspaceService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    public record CreateRequest(@NotBlank @Size(max = 128) String name) {}

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceService.WorkspaceView>> list() {
        Long userId = currentUserId();
        return ApiResponse.ok(workspaceService.listForUser(userId));
    }

    @PostMapping
    public ApiResponse<WorkspaceService.WorkspaceView> create(@Valid @RequestBody CreateRequest req) {
        Long userId = currentUserId();
        return ApiResponse.ok(workspaceService.create(userId, req.name()));
    }

    private Long currentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 3: 写 MockMvc 测试 `WorkspaceControllerTest.java`**

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.auth.JwtProperties;
import io.kyligence.ragagent.core.auth.JwtTokenProvider;
import io.kyligence.ragagent.core.auth.Role;
import io.kyligence.ragagent.core.auth.WorkspaceService;
import io.kyligence.ragagent.server.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WorkspaceController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WorkspaceControllerTest.TestBeans.class})
class WorkspaceControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @MockBean WorkspaceService workspaceService;
    @MockBean io.kyligence.ragagent.core.auth.ApiKeyService apiKeyService;

    @Test
    void listRequiresAuth() throws Exception {
        mvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsForAuthedUser() throws Exception {
        String token = jwt.issueAccess(42L, "a@b.c");
        when(workspaceService.listForUser(42L)).thenReturn(List.of(
            new WorkspaceService.WorkspaceView(1L, "default", 42L, Role.OWNER)));

        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("default"))
            .andExpect(jsonPath("$.data[0].role").value("OWNER"));
    }

    @Test
    void createReturnsNewWorkspace() throws Exception {
        String token = jwt.issueAccess(42L, "a@b.c");
        when(workspaceService.create(eq(42L), eq("team-x"))).thenReturn(
            new WorkspaceService.WorkspaceView(99L, "team-x", 42L, Role.OWNER));

        mvc.perform(post("/api/v1/workspaces")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"team-x"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(99));
    }

    @Configuration
    static class TestBeans {
        @Bean
        JwtTokenProvider jwt() {
            return new JwtTokenProvider(new JwtProperties(
                "test-secret-key-test-secret-key-test-secret-key",
                Duration.ofMinutes(60), Duration.ofDays(30), "test"));
        }
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -pl server -am test -Dtest=WorkspaceControllerTest`
Expected: 3 passed。

- [ ] **Step 5: Commit**

```bash
git add platform-core server
git commit -m "feat(server): WorkspaceController list+create with JWT auth"
```

---

## Task 20: 端到端集成测试（Testcontainers 全链路）

**Files:**
- Test: `server/src/test/java/io/kyligence/ragagent/server/FoundationE2ETest.java`

- [ ] **Step 1: 写 `FoundationE2ETest.java`**

```java
package io.kyligence.ragagent.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FoundationE2ETest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ragagent").withUsername("ragagent").withPassword("ragagent");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", mysql::getJdbcUrl);
        reg.add("spring.datasource.username", mysql::getUsername);
        reg.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired MockMvc mvc;

    @Test
    void registerLoginListWorkspaces() throws Exception {
        // 1. register
        MvcResult reg = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"e2e@example.com","password":"long-enough-pw","displayName":"E2E"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();
        String regBody = reg.getResponse().getContentAsString();
        String accessToken = extract(regBody, "accessToken");

        // 2. list (uses default ws created on register)
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].role").value("OWNER"));

        // 3. login same user, fresh token still works
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"e2e@example.com","password":"long-enough-pw"}
                """))
            .andExpect(status().isOk()).andReturn();
        String newToken = extract(login.getResponse().getContentAsString(), "accessToken");
        assertThat(newToken).isNotEqualTo(accessToken);

        // 4. wrong password rejected
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"e2e@example.com","password":"wrong-password!!"}
                """))
            .andExpect(status().isUnauthorized());
    }

    private String extract(String body, String key) {
        int i = body.indexOf("\"" + key + "\":\"");
        int s = i + key.length() + 4;
        int e = body.indexOf("\"", s);
        return body.substring(s, e);
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn -pl server -am test -Dtest=FoundationE2ETest`
Expected: 1 passed。

- [ ] **Step 3: 跑所有模块所有测试**

Run: `mvn verify`
Expected: BUILD SUCCESS（reactor 全绿）。

- [ ] **Step 4: Commit**

```bash
git add server
git commit -m "test(server): foundation E2E covering register/login/workspace list"
```

---

## Task 21: Dockerfile + 镜像构建验证

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: 写 `.dockerignore`**

```
.git
.idea
docker/data
**/target
*.iml
.env
```

- [ ] **Step 2: 写 `Dockerfile`（multi-stage）**

```dockerfile
# syntax=docker/dockerfile:1.6

FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml ./
COPY shared/pom.xml shared/pom.xml
COPY platform-core/pom.xml platform-core/pom.xml
COPY rag/pom.xml rag/pom.xml
COPY agent/pom.xml agent/pom.xml
COPY workflow/pom.xml workflow/pom.xml
COPY server/pom.xml server/pom.xml
COPY cli/pom.xml cli/pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline
COPY shared shared
COPY platform-core platform-core
COPY rag rag
COPY agent agent
COPY workflow workflow
COPY server server
COPY cli cli
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ARG JAR_FILE=server/target/server-*.jar
COPY --from=builder /build/${JAR_FILE} app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: 构建镜像**

```bash
docker build -t rag-agent-platform:0.1.0-SNAPSHOT .
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 启动镜像并验证 /health**

```bash
docker run --rm -d --name rag-agent-test \
  --network host \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/ragagent?useSSL=false&allowPublicKeyRetrieval=true \
  -e SPRING_DATASOURCE_USERNAME=ragagent \
  -e SPRING_DATASOURCE_PASSWORD=ragagent \
  -e RAGAGENT_AUTH_JWT_SECRET=local-dev-jwt-secret-at-least-32-chars-long \
  rag-agent-platform:0.1.0-SNAPSHOT
sleep 30
curl -s http://localhost:8080/health
docker stop rag-agent-test
```

Expected: `{"success":true,"data":{"status":"UP"},"error":null}`。

> 如果 docker compose 已起，mysql 端口已占用，跳过 `docker compose up` 直接复用即可。

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "build: multi-stage Dockerfile producing eclipse-temurin:17-jre image"
```

---

## Task 22: GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: 写 `ci.yml`**

```yaml
name: ci

on:
  push:
    branches: [master, main]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: temurin
          cache: maven
      - name: mvn verify
        run: mvn -B -ntp verify

  docker-build:
    runs-on: ubuntu-latest
    needs: verify
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - name: Build image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: false
          tags: rag-agent-platform:ci
```

- [ ] **Step 2: 本地试一遍 `mvn verify`**

Run: `mvn -B -ntp verify`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add .github
git commit -m "ci: github actions workflow for mvn verify + docker build"
```

---

## 验收清单（在 Task 22 完成后人工核对）

- [ ] `mvn verify` 在本地全绿
- [ ] `docker compose up -d` 起 4 个容器健康
- [ ] `docker build -t rag-agent-platform:dev .` 成功
- [ ] 启动容器 → `curl /health` 返回 `{"success":true,...,"status":"UP"}`
- [ ] `POST /api/v1/auth/register` 创建用户 + 默认 workspace + 返回 access/refresh token
- [ ] `GET /api/v1/workspaces` 带 access token → 返回自己的 workspace 列表
- [ ] 不带 token 访问任何 `/api/v1/**`（除 auth/health）→ 401
- [ ] MyBatis-Plus tenant interceptor 把 workspace_id 自动注入到非排除表的 SQL
- [ ] 全部 commit 落地，main 分支推送后 CI 绿

---

## 下一步

Plan A 完成后，开始写：

- **Plan B（核心服务）：** §3.1 ModelGateway + §3.7 PromptHub + §3.3 MemoryStore + §3.4 Tracing
- **Plan C（编排支撑）：** §3.2 ToolRegistry + §3.5 SandboxRunner + §3.6 EventBus

不在本 plan 内的功能：API key 的"颁发/吊销"REST 接口（验证管线已就位但管理端点延后到 Plan B 一起做，因为需要带 workspace 维度的 list）。

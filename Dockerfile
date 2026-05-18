# Multi-stage build: Maven build → JRE runtime
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy POM files for dependency resolution
COPY pom.xml .
COPY shared/pom.xml shared/
COPY platform-core/pom.xml platform-core/
COPY server/pom.xml server/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY shared/src shared/src
COPY platform-core/src platform-core/src
COPY server/src server/src

# Build application
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/server/target/*.jar app.jar

# Create non-root user
RUN groupadd -r ragagent && useradd -r -g ragagent ragagent
RUN chown -R ragagent:ragagent /app
USER ragagent

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

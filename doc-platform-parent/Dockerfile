# ============================================================
# 通用单据驱动平台：按服务构建可运行镜像（构建上下文 = doc-platform-parent）
#   docker build --build-arg SERVICE=meta-service -t doc/meta-service:1.0.0 .
# SERVICE 可选：doc-gateway / meta-service / runtime-service
# 镜像 tag 禁止 latest；生产建议按服务版本或 Git SHA 命名。
#
# 注意：本文件已按 Spring Boot 3.5 分层 jar 方式实现，但本机暂无可用 Docker
# 环境，**未经实际构建验证**；首次使用请在具备 Docker 的机器上构建并冒烟验证。
# 非容器化部署仍以 deploy/app.sh（开发）或 systemd（生产）为准。
# ============================================================

ARG SERVICE=meta-service

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
ARG SERVICE=meta-service
WORKDIR /build
COPY . .
RUN mvn -B -pl "${SERVICE}" -am clean package -DskipTests \
    && JAR="$(ls ${SERVICE}/target/${SERVICE}-*.jar | head -n 1)" \
    && java -Djarmode=tools -jar "${JAR}" extract --layers --launcher --destination /extracted

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
ARG SERVICE=meta-service
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=builder /extracted/dependencies/ ./
COPY --from=builder /extracted/snapshot-dependencies/ ./
COPY --from=builder /extracted/spring-boot-loader/ ./
COPY --from=builder /extracted/launcher/ ./
COPY --from=builder /extracted/application/ ./
USER app
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]

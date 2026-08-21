# ============================================================
# 占位模板：镜像 tag 禁 latest，按规范 L-01 验证点补齐。
# 通用单据驱动平台微服务：通过构建参数 SERVICE 指定服务模块（默认 meta-service）。
#   docker build --build-arg SERVICE=meta-service -t doc/meta-service:x.y.z .
# ============================================================

# 目标服务模块（doc-gateway / meta-service / runtime-service）
ARG SERVICE=meta-service

# ---------- 构建阶段 ----------
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# 占位：复制源码并执行 maven build（请按实际构建方式补齐），例如：
# COPY . .
# RUN ./mvnw -B clean package -DskipTests -pl ${SERVICE} -am

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# 重新声明 ARG（多阶段构建中运行阶段不可见构建阶段的 ARG）
ARG SERVICE=meta-service

# 占位：使用 Spring Boot 3.3+ 的 `-Djarmode=tools -jar ${SERVICE}.jar extract --layers --launcher`
# 解包后进行分层复制（按规范补齐），例如：
# COPY --from=builder /build/${SERVICE}/target/extracted/dependencies/ ./
# COPY --from=builder /build/${SERVICE}/target/extracted/snapshot-dependencies/ ./
# COPY --from=builder /build/${SERVICE}/target/extracted/spring-boot-loader/ ./
# COPY --from=builder /build/${SERVICE}/target/extracted/launcher/ ./
# COPY --from=builder /build/${SERVICE}/target/extracted/application/ ./

# 创建非 root 用户并切换
RUN groupadd --system app && useradd --system --gid app app
USER app

# 占位端口：doc-gateway=8080 / meta-service=8081 / runtime-service=8082，按目标服务调整
EXPOSE 8080

# 占位：按规范补齐启动命令，例如：
# ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]

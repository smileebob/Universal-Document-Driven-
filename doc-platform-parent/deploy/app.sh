#!/usr/bin/env bash
# ============================================================
# 占位模板：按实际环境补齐（非容器化部署：主机/虚机直接运行 jar）。
# 本脚本仅供开发/联调环境使用；生产主机部署必须按 12-DevOps-Deployment-Rules
# L-01 轨道 B 使用 systemd 托管（非 root、Restart 自愈），禁止 nohup 裸跑上生产。
# 生产上线前请按规范 L-03（发布与回滚）验证点补齐：
#   - 发布前校验制品与配置，发布后健康检查确认；
#   - 回滚说明：每次发布前保留上一版本 jar（如 {service}-<旧版本>.jar），
#     回滚时将 JAR_FILE 指回上一版本制品并重启即可。
# 用法（在 doc-platform-parent 目录下执行）：
#   ./deploy/app.sh start|stop|restart|status <service-name>
#   service-name：doc-gateway / meta-service / runtime-service
#   示例：./deploy/app.sh start meta-service
# ============================================================
set -euo pipefail

# ---------- 参数区 ----------
ACTION="${1:-}"
SERVICE_NAME="${2:-}"
if [ -z "${SERVICE_NAME}" ]; then
  echo "Usage: $0 {start|stop|restart|status} <service-name>"
  echo "  service-name: doc-gateway / meta-service / runtime-service"
  exit 1
fi
APP_NAME="${SERVICE_NAME}"

# 制品 jar 匹配模式：{service}/target/{service}-*.jar（mvn clean package -DskipTests 后的产物）；
# 通配符可能命中多个 jar（如旧版本残留），start 时取最新一个并在多匹配时告警。
JAR_GLOB="${SERVICE_NAME}/target/${SERVICE_NAME}-*.jar"

PID_FILE="${APP_NAME}.pid"
LOG_DIR="logs"

# JVM 参数占位：-Xms/-Xmx 需经压测确定；
# 容器/内存受限场景可改用 -XX:MaxRAMPercentage=75.0
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=75.0}"

# 运行环境占位（dev/test/prod）
APP_PROFILE="${APP_PROFILE:-dev}"

# 按服务名推导健康检查端口（可经 SERVER_PORT 环境变量覆盖）：
# doc-gateway=8080 / meta-service=8081 / runtime-service=8082
case "${SERVICE_NAME}" in
  doc-gateway)     DEFAULT_PORT=8080 ;;
  meta-service)    DEFAULT_PORT=8081 ;;
  runtime-service) DEFAULT_PORT=8082 ;;
  *)               DEFAULT_PORT=8080 ;;
esac
SERVER_PORT="${SERVER_PORT:-${DEFAULT_PORT}}"

# 强杀超时必须大于应用优雅停机超时（spring.lifecycle.timeout-per-shutdown-phase=30s），避免请求被强杀中断
WAIT_TIMEOUT=45

# 就绪等待：启动后轮询 actuator/health，超时视为启动失败（不能只凭 PID 判定 started）
READY_TIMEOUT="${READY_TIMEOUT:-90}"

# 服务名白名单（防止误传路径/任意进程名）
case "${SERVICE_NAME}" in
  doc-gateway|meta-service|runtime-service) ;;
  *)
    echo "[deploy] 非法服务名: ${SERVICE_NAME}（仅支持 doc-gateway / meta-service / runtime-service）"
    exit 1
    ;;
esac

# ---------- 环境变量注入说明 ----------
# NACOS_SERVER_ADDR / NACOS_NAMESPACE / SENTINEL_DASHBOARD
# OTS_ENDPOINT / OTS_INSTANCE / OTS_ACCESS_KEY_ID / OTS_ACCESS_KEY_SECRET（Tablestore，业务服务）
# ROCKETMQ_ENDPOINT / ROCKETMQ_ACCESS_KEY / ROCKETMQ_SECRET_KEY（RocketMQ，业务服务）
# REDIS_HOST / REDIS_PORT / REDIS_USERNAME / REDIS_PASSWORD / REDIS_TIMEOUT_MS / REDIS_CONNECT_TIMEOUT_MS
# GATEWAY_JWT_SECRET / GATEWAY_AUTH_ENABLED（doc-gateway 认证）
# 以上变量由 application.yml / Nacos 配置的 ${ENV:默认值} 机制读取（R-05：敏感值零明文）：
# 启动前经 export 或 /etc/environment / systemd EnvironmentFile 注入，
# 未注入时回落到 application.yml 中的本地占位默认值（仅限开发环境）。

start() {
  # 制品选择：显式 JAR_FILE 优先；否则只允许唯一匹配，避免误启动旧版本
  local jar_file="${JAR_FILE:-}"
  if [ -z "${jar_file}" ]; then
    local jar_candidates jar_count
    jar_candidates=$(ls -1t ${JAR_GLOB} 2>/dev/null || true)
    if [ -z "${jar_candidates}" ]; then
      echo "[${APP_NAME}] no jar matched ${JAR_GLOB}, run 'mvn clean package -DskipTests' first"
      exit 1
    fi
    jar_count=$(printf '%s\n' "${jar_candidates}" | wc -l | tr -d ' ')
    if [ "${jar_count}" -gt 1 ]; then
      echo "[${APP_NAME}] ERROR: ${jar_count} jars matched ${JAR_GLOB}，无法确定版本。"
      echo "请清理旧制品，或显式指定 JAR_FILE=<path> 后重试："
      printf '%s\n' "${jar_candidates}"
      exit 1
    fi
    jar_file=$(printf '%s\n' "${jar_candidates}" | sed -n '1p')
  elif [ ! -f "${jar_file}" ]; then
    echo "[${APP_NAME}] JAR_FILE 不存在: ${jar_file}"
    exit 1
  fi

  mkdir -p "${LOG_DIR}"
  echo "[${APP_NAME}] starting ${jar_file} (profile=${APP_PROFILE})..."
  # nohup 后台启动，日志输出到 logs/ 目录（生产禁用本方式，见文件头声明）
  # shellcheck disable=SC2086
  nohup java ${JAVA_OPTS} \
    -Dspring.profiles.active="${APP_PROFILE}" \
    -jar "${jar_file}" \
    > "${LOG_DIR}/${APP_NAME}.log" 2>&1 &
  local pid=$!
  echo "${pid}" > "${PID_FILE}"

  if ! wait_ready "${pid}"; then
    echo "[${APP_NAME}] 启动失败：${READY_TIMEOUT}s 内未通过健康检查，日志：${LOG_DIR}/${APP_NAME}.log"
    tail -n 30 "${LOG_DIR}/${APP_NAME}.log" || true
    kill "${pid}" 2>/dev/null || true
    rm -f "${PID_FILE}"
    exit 1
  fi
  echo "[${APP_NAME}] started, pid=${pid}, health=UP"
}

# 就绪等待：进程存活 + actuator/health 返回 UP
wait_ready() {
  local pid="$1" waited=0
  while [ "${waited}" -lt "${READY_TIMEOUT}" ]; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "[${APP_NAME}] 进程已退出"
      return 1
    fi
    if curl -sf "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

stop() {
  if [ ! -f "${PID_FILE}" ]; then
    echo "[${APP_NAME}] not running (pid file missing)"
    return 0
  fi
  local pid
  pid=$(cat "${PID_FILE}")
  echo "[${APP_NAME}] stopping pid=${pid} (SIGTERM)..."
  # application.yml 已配 server.shutdown: graceful，
  # 收到 SIGTERM 后会等待在途请求处理完毕再退出。
  kill "${pid}" || true
  # 超时兜底：WAIT_TIMEOUT 秒内未退出则强制 kill
  local waited=0
  while kill -0 "${pid}" 2>/dev/null; do
    if [ "${waited}" -ge "${WAIT_TIMEOUT}" ]; then
      echo "[${APP_NAME}] graceful stop timeout, force kill"
      kill -9 "${pid}" || true
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
  rm -f "${PID_FILE}"
  echo "[${APP_NAME}] stopped"
}

status() {
  # 健康检查：actuator/health + PID 归属提示（端口可能被其他进程占用）
  local pid=""
  if [ -f "${PID_FILE}" ]; then
    pid=$(cat "${PID_FILE}" 2>/dev/null || true)
  fi
  if curl -sf "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null; then
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
      echo "[${APP_NAME}] healthy (actuator/health UP, pid=${pid})"
    else
      echo "[${APP_NAME}] health=UP，但未找到存活 PID（可能由其他进程占用端口或手工启动）"
    fi
  else
    echo "[${APP_NAME}] unhealthy or not running (port=${SERVER_PORT})"
    return 1
  fi
}

restart() {
  stop
  start
}

case "${ACTION}" in
  start)   start ;;
  stop)    stop ;;
  restart) restart ;;
  status)  status ;;
  *)
    echo "Usage: $0 {start|stop|restart|status} <service-name>"
    exit 1
    ;;
esac

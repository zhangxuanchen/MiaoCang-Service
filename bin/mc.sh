#!/bin/bash
# 喵藏书房 · 运维脚本：启动/停止/状态/日志/备份/LaunchAgent 安装/laya 侧车环境
# 用法: bin/mc.sh {start|stop|restart|status|logs|backup|install|uninstall|run-foreground|laya-setup|laya-log}
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
JAR="target/miaocang-service-1.0.0.jar"
PID_FILE="data/mc.pid"
RUN_LOG="data/logs/run.log"
APP_LOG="data/logs/miaocang.log"
BACKUP_DIR="data/backups"
KEEP_BACKUPS=7
PORT=8080

mkdir -p data/logs "$BACKUP_DIR"

# jar 是否最新：pom 之后，src/main 下不能有任何比 jar 更新的文件。
# 旧版用 `[ "$JAR" -nt "$ROOT/src/main/resources" ]` 比对「目录」，而改文件内容不会变更目录 mtime，
# 只在增删文件时才变——所以只改前端资源常被判成「jar 是新的」而不重新打包 → 页面「改了没生效」。
# find -newer 逐文件比对，java 与 resources 一起覆盖。
jar_fresh() {
  [ -f "$JAR" ] || return 1
  [ "$JAR" -nt "$ROOT/pom.xml" ] || return 1
  [ -z "$(find "$ROOT/src/main" -newer "$JAR" -print -quit 2>/dev/null)" ]
}
# 给 index.html 注入版本号，绕开浏览器内存/HTTP 缓存导致的「改了没生效」假象
# 把 src="xxx.js" / href="xxx.css" 变成 src="xxx.js?v=YYYYmmdd-HHMMSS"（秒级，每次 build 唯一）
# 两阶段：先剥掉所有已有的 ?v=...，再一次性注入新的
cache_bust_index() {
  local html="$ROOT/src/main/resources/static/index.html"
  local ts
  ts="$(date +%Y%m%d-%H%M%S)"
  sed -E -i '' \
    -e 's#(src="[^"]+\.(js|css|map))\?v=[^"]*#\1#g' \
    -e 's#(href="[^"]+\.(css|map))\?v=[^"]*#\1#g' \
    -e "s#(src=\"[^\"]+\\.(js|css|map))\"#\\1?v=${ts}\"#g" \
    -e "s#(href=\"[^\"]+\\.(css|map))\"#\\1?v=${ts}\"#g" \
    "$html"
}
build() {
  # cache_bust_index 必须先于 jar_fresh 执行：它修改 index.html（改 src/main 下的文件），
  # 让 jar_fresh 返回 false 触发打包；否则 jar_fresh 检查时 index.html 还没被改，jar 被当作是新的
  cache_bust_index
  if ! jar_fresh; then
    echo "📦 打包 jar（源码比 jar 新或 jar 缺失）…"
    mvn -q package -DskipTests
  fi
}

# 运行中的 PID：只认「PID 文件里记着、还活着、且命令行确实是本 jar」的进程，
# 兜底再按 jar 名匹配进程。
# 为什么不再按端口猜：`lsof -ti tcp:PORT -sTCP:LISTEN` 只能说明「有人占了 8080」，
# 不能说明那个人是本应用——实测本机 8080 被别的服务监听（Docker 里的 nginx），
# 按端口猜会把无关进程认成「喵藏在运行」：start 直接不启动、stop 更会误杀别人的进程。
pid_of() {
  if [ -f "$PID_FILE" ]; then
    local p; p="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null && ps -p "$p" -o command= 2>/dev/null | grep -q "$JAR"; then
      echo "$p"; return
    fi
  fi
  pgrep -f "$JAR" 2>/dev/null | head -1 || true
}

healthy() { [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/" 2>/dev/null || true)" = "200" ]; }

wait_up() { for _ in $(seq 1 60); do healthy && return 0; sleep 2; done; return 1; }

start() {
  local pid; pid="$(pid_of || true)"
  if [ -n "$pid" ]; then echo "已在运行 (pid=$pid)"; return 0; fi
  build
  echo "🚀 启动喵藏书房（prod profile）…"
  nohup java -jar "$JAR" --spring.profiles.active=prod >> "$RUN_LOG" 2>&1 &
  echo $! > "$PID_FILE"
  if wait_up; then echo "✅ 已就绪: http://localhost:$PORT  (pid=$(cat "$PID_FILE"))"; else echo "❌ 启动失败，看日志: tail -50 $APP_LOG $RUN_LOG"; return 1; fi
}

stop() {
  local pid; pid="$(pid_of || true)"
  if [ -z "$pid" ]; then echo "未在运行"; rm -f "$PID_FILE"; return 0; fi
  echo "🛑 停止 (pid=$pid)…"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  # 兜住 kill -9 留下的孤儿侧车（正常路径由 LayaSidecarManager 的 @PreDestroy 回收）
  pkill -f "sidecar/laya_server.py" 2>/dev/null || true
  echo "已停止"
}

status() {
  local pid; pid="$(pid_of || true)"
  if [ -n "$pid" ] && healthy; then echo "🟢 运行中 (pid=$pid) http://localhost:$PORT"; 
  elif [ -n "$pid" ]; then echo "🟡 进程存在 (pid=$pid) 但探活失败"
  else echo "🔴 未运行"; fi
}

logs() { tail -n 200 -f "$APP_LOG" 2>/dev/null || tail -n 200 -f "$RUN_LOG"; }

# 备份：H2 数据库 + library 文件区 → data/backups/<时间戳>/（保留最近 KEEP_BACKUPS 份）
# 在线拷贝 H2 存在一致性风险，脚本自动「停→拷→启」，前后运行状态保持不变
backup() {
  local was_running=0; [ -n "$(pid_of || true)" ] && was_running=1
  local dest="$BACKUP_DIR/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dest"
  [ "$was_running" = "1" ] && stop
  cp data/miaocang.mv.db "$dest/" 2>/dev/null || echo "⚠️ 未找到 data/miaocang.mv.db"
  [ -d data/library ] && tar -czf "$dest/library.tgz" -C data library
  [ "$was_running" = "1" ] && start
  ls -1dt "$BACKUP_DIR"/*/ 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs rm -rf 2>/dev/null || true
  echo "✅ 备份完成: $dest"; du -sh "$dest"
}

# LaunchAgent：登录自启 + 崩溃自动拉起（前台进程交给 launchd 托管，根治「终端退出进程被杀」）
PLIST_ID="com.miaocang.service"
PLIST_SRC="$ROOT/bin/$PLIST_ID.plist"
PLIST_DST="$HOME/Library/LaunchAgents/$PLIST_ID.plist"

install_agent() {
  build
  sed "s#__ROOT__#$ROOT#g" "$PLIST_SRC" > "$PLIST_DST"
  launchctl unload "$PLIST_DST" 2>/dev/null || true
  launchctl load "$PLIST_DST"
  echo "✅ LaunchAgent 已安装并启动（登录自启 + 崩溃自动拉起）"
  echo "   查看: launchctl list | grep miaocang；日志: $ROOT/data/logs/"
}
uninstall_agent() {
  launchctl unload "$PLIST_DST" 2>/dev/null || true
  rm -f "$PLIST_DST"
  echo "LaunchAgent 已卸载（服务停止；需要手动运行请用 bin/mc.sh start）"
}

# launchd 专用：前台运行（不可 nohup detach），由 launchd 负责保活与日志
run_foreground() { exec java -jar "$JAR" --spring.profiles.active=prod; }

# laya 侧车环境准备（幂等）：建 venv → 装 CPU-only 依赖 → 下载权重 → 冒烟。
# 应用启动时 LayaSidecarManager 检测到环境缺失也会自己调它，这里只是手动入口。
laya_setup() { bash "$ROOT/bin/laya-setup.sh" "$@"; }
laya_log() { tail -n 200 -f "$ROOT/data/logs/laya.log"; }

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) logs ;;
  backup) backup ;;
  install) install_agent ;;
  uninstall) uninstall_agent ;;
  run-foreground) run_foreground ;;
  laya-setup) laya_setup ;;
  laya-log) laya_log ;;
  *) echo "用法: bin/mc.sh {start|stop|restart|status|logs|backup|install|uninstall|run-foreground|laya-setup|laya-log}"; exit 1 ;;
esac

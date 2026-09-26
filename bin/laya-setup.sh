#!/bin/bash
# 喵藏 · laya 侧车环境准备：建 venv → 装 CPU-only 依赖 → 下载 huggingface 权重
#
# 幂等：已就绪的步骤自动跳过，可反复执行。
# 两个调用入口：
#   bin/mc.sh laya-setup          —— 手动
#   Spring Boot 启动时 LayaSidecarManager 检测到环境缺失会自己调它（自动准备）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$ROOT/sidecar/.venv"
PY="$VENV/bin/python"
MODELS_DIR="${MIAOCANG_LAYA_MODELS_DIR:-$ROOT/data/laya/models}"
MODEL="${MIAOCANG_LAYA_MODEL:-multilingual}"
HF_HOME_DIR="$ROOT/data/laya/hf"
REPO="convaiinnovations/laya"
# huggingface.co 在部分网络下不可达（ConnectTimeout）；默认用镜像兜底，可用环境变量覆盖
MIAOCANG_HF_ENDPOINT="${MIAOCANG_HF_ENDPOINT:-}"
MIRROR="${MIAOCANG_HF_MIRROR:-https://hf-mirror.com}"

log() { echo "[laya-setup] $*"; }
fail() { echo "[laya-setup][错误] $*" >&2; exit 1; }

# 下载权重：HF_ENDPOINT 必须在 Python 进程启动前设好（huggingface_hub 在 import 时读常量）
run_download() {
  HF_HOME="$HF_HOME_DIR" HF_ENDPOINT="${1:-https://huggingface.co}" \
    "$PY" - "$REPO" "$MODEL" "$MODELS_DIR" <<'PYEOF'
import sys
from huggingface_hub import snapshot_download
repo, model, dest = sys.argv[1], sys.argv[2], sys.argv[3]
snapshot_download(repo_id=repo, allow_patterns=[model + "/*"], local_dir=dest)
print("[laya-setup] 下载完成")
PYEOF
}

# ---------------------------------------------------------------- 1) venv
if [ ! -x "$PY" ]; then
  PYBIN=""
  for c in python3.11 python3.12 python3.13 python3.10 python3; do
    if command -v "$c" >/dev/null 2>&1; then PYBIN="$c"; break; fi
  done
  [ -n "$PYBIN" ] || fail "找不到 python3（laya 要求 ≥3.10）"
  log "创建 venv（${PYBIN}）→ $VENV"
  "$PYBIN" -m venv "$VENV"
fi
log "解释器：$("$PY" -V 2>&1)"

# ---------------------------------------------------------------- 2) 依赖
mkdir -p "$ROOT/data/logs"
if ! "$PY" -c "import torch" >/dev/null 2>&1; then
  log "安装 CPU-only torch（首次较慢，数百 MB）…"
  "$PY" -m pip install -q --upgrade pip
  "$PY" -m pip install --index-url https://download.pytorch.org/whl/cpu torch
fi
if ! "$PY" -c "import transformers, safetensors, huggingface_hub, numpy" >/dev/null 2>&1; then
  log "安装侧车依赖（transformers / safetensors / huggingface_hub / numpy）…"
  "$PY" -m pip install -q -r "$ROOT/sidecar/requirements.txt"
fi
"$PY" -c "import torch, transformers; print('[laya-setup] torch %s · transformers %s' % (torch.__version__, transformers.__version__))"

# ---------------------------------------------------------------- 3) 权重
if [ -f "$MODELS_DIR/$MODEL/rl_agent_config.json" ]; then
  log "权重已就绪：$MODELS_DIR/$MODEL"
else
  # 注意：变量后面紧跟中文/全角字符时必须用 ${VAR} 收口，否则 bash 会把多字节字符
  # 当成变量名的一部分（报 MODEL<乱码>: unbound variable）
  log "下载 $REPO 的 ${MODEL} 权重 → ${MODELS_DIR}/${MODEL}（约 650MB，需联网一次）…"
  mkdir -p "$MODELS_DIR"
  if [ -n "$MIAOCANG_HF_ENDPOINT" ]; then
    run_download "$MIAOCANG_HF_ENDPOINT" || fail "权重下载失败：MIAOCANG_HF_ENDPOINT=$MIAOCANG_HF_ENDPOINT 不可用"
  elif ! run_download; then
    # 国内网络直连 huggingface.co 会 ConnectTimeout（Errno 60）；用镜像重试一次即可
    log "直连 huggingface.co 不通，改用镜像 ${MIRROR} 重试（可用 MIAOCANG_HF_ENDPOINT 覆盖）…"
    run_download "$MIRROR" || fail "权重下载失败：直连与镜像 ${MIRROR} 都不可用"
  fi
fi
[ -f "$MODELS_DIR/$MODEL/rl_agent_config.json" ] || fail "权重目录不完整（缺 rl_agent_config.json）：$MODELS_DIR/$MODEL"

# ---------------------------------------------------------------- 4) 冒烟
# 真正 import 一次 vendored 包 + 列权重文件，确认「不依赖外部工程」这一条成立
"$PY" - <<PYEOF
import os, sys
sys.path.insert(0, "$ROOT/sidecar")
import laya
p = os.path.join("$MODELS_DIR", "$MODEL")
print("[laya-setup] 内嵌 laya 包 %s · 权重文件 %s" % (laya.__version__, sorted(os.listdir(p))))
PYEOF

log "完成。跑 bin/mc.sh restart 即可（应用启动时会自动带起侧车）"
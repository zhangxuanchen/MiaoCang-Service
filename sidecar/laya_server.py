"""喵藏内嵌的 laya 推理侧车（只跑模型前向，不含测试台界面）。

--------------------------------------------------------------------------
派生作品声明（Apache License 2.0 第 4(b) 条要求的「已修改」标识）
本文件派生自上游 laya 项目的 java/backend/server.py
（https://huggingface.co/convaiinnovations/laya ，Apache License 2.0，
 Copyright Convai Innovations），已被修改，非上游原文。
许可证全文见同目录 LICENSE，来源与合规说明见同目录 UPSTREAM.txt。
--------------------------------------------------------------------------

由 Spring Boot 的 LayaSidecarManager 在应用启动时以子进程拉起，只绑 127.0.0.1。
相对上游 java/backend/server.py 的改动：

1. 去掉 `GET /`（测试台 UI）与上游的 `/api/*` 端点：喵藏不做调试面板，只保留
   `/health`、`/v1/predict`、`/v1/preload` 三个 Java 契约端点；
2. `POST /v1/predict` 的 `model` **缺省走自动路由**（上游缺省是 english，中文内容会
   掉进「english 读非拉丁文本崩到接近随机却仍报高置信」的坑）；
3. 补请求体上限 MAX_BODY_BYTES（上游无上限；虽只绑 loopback，仍不该让一个异常大请求打爆进程）；
4. 端口缺省由 8770 改为 8777，避免与上游 server.py 同时运行时的端口冲突。

    python sidecar/laya_server.py --device cpu --preload multilingual --models-dir data/laya/models
"""
import argparse
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
# 让 `import laya` 命中本仓库自带的 sidecar/laya/，而不是外部安装的 laya
sys.path.insert(0, HERE)

os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("USE_TORCH", "1")

import laya  # noqa: E402  （必须在 sys.path 调整之后）

AVAILABLE_MODELS = ["english", "multilingual", "typed-decisions"]
MAX_BODY_BYTES = 2 * 1024 * 1024

# 单一 Router：模型常驻、LRU、自动路由都交给它，避免重复占内存
ROUTER = None


def _resolve_models(models_dir):
    """本地权重目录优先，没有的模型回退上游 hub id（联网时才会下载）。

    本地目录必须是完整 checkpoint（含 rl_agent_config.json）。用本地目录加载有两个好处：
    温度校准改动落在仓库自己的目录里，既不会污染 HF 缓存，也不会被重新下载覆盖。
    """
    local = {}
    if not models_dir:
        return local
    for name in AVAILABLE_MODELS:
        path = os.path.join(models_dir, name)
        if os.path.isdir(path) and os.path.isfile(os.path.join(path, "rl_agent_config.json")):
            local[name] = path
    return local


def _device_label():
    for name in ROUTER.loaded:
        try:
            return str(ROUTER.load(name).device)
        except Exception:
            pass
    return "auto"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        """静音逐请求日志；有意义的日志由 main() 与 _json 错误分支主动打。"""

    # ---------------------------------------------------------------- 基础设施
    def _send(self, code, body, ctype):
        raw = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            raise ValueError("请求体过大：%d 字节（上限 %d）" % (length, MAX_BODY_BYTES))
        if not length:
            return {}
        return json.loads(self.rfile.read(length) or b"{}")

    # ---------------------------------------------------------------- 路由
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            self._json(200, {
                "status": "ok",
                "loaded": ROUTER.loaded,
                "models": {k: list(v) for k, v in laya.DEFAULT_MODELS.items()},
                "version": laya.__version__,
            })
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            payload = self._read_json()
            if path == "/v1/predict":
                self._v1_predict(payload)
            elif path == "/v1/preload":
                names = payload.get("models") or AVAILABLE_MODELS
                ROUTER.preload(names)
                self._json(200, {"loaded": ROUTER.loaded})
            else:
                self._json(404, {"error": "not found"})
        except Exception as exc:  # 统一错误响应，Java 侧解析 {"error": ...}
            detail = "%s: %s" % (type(exc).__name__, exc)
            print("[laya-sidecar] 请求失败 %s -> %s" % (path, detail), flush=True)
            self._json(400, {"error": detail})

    # ---------------------------------------------------------------- 推理端点
    def _v1_predict(self, payload):
        model = payload.get("model") or None          # 缺省 = 自动路由（上游缺省是 english）
        questions = payload.get("questions")
        if not isinstance(questions, dict) or not questions:
            raise ValueError("questions 必须是非空的 {id -> 定义} 对象")
        t0 = time.time()
        result = ROUTER.predict(payload.get("state"), questions, model=model)
        result["latency_ms"] = round((time.time() - t0) * 1000, 2)
        self._json(200, result)


def main():
    global ROUTER
    parser = argparse.ArgumentParser(description="喵藏内嵌 laya 推理侧车")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址；内嵌场景保持 loopback")
    parser.add_argument("--port", type=int, default=8777,
                        help="默认 8777；刻意避开上游 java/backend/server.py 的 8770")
    parser.add_argument("--device", default="cpu", help="cpu / mps / cuda")
    parser.add_argument("--token", default=None, help="HuggingFace token（私有模型需要）")
    parser.add_argument("--preload", default="multilingual",
                        help="逗号分隔的预加载 checkpoint；传空则懒加载")
    parser.add_argument("--models-dir", default=None,
                        help="本地权重根目录：<models-dir>/<name> 存在即用本地目录加载")
    args = parser.parse_args()

    local_models = _resolve_models(args.models_dir)
    if local_models:
        print("[laya-sidecar] 本地权重: %s" % json.dumps(local_models, ensure_ascii=False), flush=True)

    ROUTER = laya.Router(
        models=local_models or None,
        device=args.device,
        token=args.token or os.environ.get("HF_TOKEN"),
        max_loaded=len(AVAILABLE_MODELS),
    )
    names = [n.strip() for n in (args.preload or "").split(",") if n.strip()]
    if names:
        t0 = time.time()
        print("[laya-sidecar] 开始预加载 %s（CPU 冷加载通常 7-10 秒）…" % names, flush=True)
        ROUTER.preload(names)
        print("[laya-sidecar] 预加载完成，耗时 %.1fs" % (time.time() - t0), flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("[laya-sidecar] 就绪 → http://%s:%d  device=%s  loaded=%s  preload=%s  laya=%s"
          % (args.host, server.server_address[1], _device_label(), ROUTER.loaded,
             names or "lazy", laya.__version__), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("[laya-sidecar] 已停止", flush=True)


if __name__ == "__main__":
    main()
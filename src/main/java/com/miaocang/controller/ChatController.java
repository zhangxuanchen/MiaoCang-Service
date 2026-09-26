package com.miaocang.controller;

import com.miaocang.chat.ChatSessionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 每猫专属会话区 API（agentscope-harness + 四层压缩记忆）。
 *
 * <p>会话区的操作范围 = 当前选择猫的存储文档位置（{@code data/library/cat-{id}/}）：
 * <ul>
 *   <li>POST /api/cats/{catId}/chat/stream —— SSE 对话（token / tool / toolresult / done / error / ping）</li>
 *   <li>GET  /api/cats/{catId}/chat/memory —— 记忆面板（快照 + 折叠足迹 + 压缩日志）</li>
 *   <li>POST /api/cats/{catId}/chat/clear —— L4 主动清空会话记忆</li>
 *   <li>POST /api/cats/{catId}/chat/compress —— 标记待压缩（下一轮开始时自收敛）</li>
 *   <li>GET  /api/cats/{catId}/chat/files —— 该猫工作区文件清单</li>
 *   <li>GET  /api/cats/{catId}/chat/file —— 预览工作区一个文本文件</li>
 * </ul>
 *
 * <p><b>catId=0 = Wiki 管理员</b>（全站悬浮球入口）：工作区 = 书库根 {@code data/library/}，
 * 持有所有猫的工作区与公共区权限，LLM 配置走全局兜底，可代主人查询/操作整个书库。
 */
@RestController
@RequestMapping("/api/cats/{catId}/chat")
public class ChatController {

    private final ChatSessionService sessions;

    public ChatController(ChatSessionService sessions) {
        this.sessions = sessions;
    }

    /** SSE 对话：请求体 {sessionId, message, pageContext}；sessionId 为空则服务端生成并在 done 事件回传。
     *  pageContext = 主人当前所在页面的描述（前端组装），让喵结合用户正在看的页面回答。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long catId, @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "").strip();
        if (message.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        return sessions.stream(catId, body.get("sessionId"), message, body.get("pageContext"));
    }

    /** 记忆面板：会话记忆快照 + 折叠足迹 + 压缩日志 + 模式。 */
    @GetMapping("/memory")
    public Map<String, Object> memory(@PathVariable Long catId, @RequestParam String sessionId) {
        return sessions.memoryState(catId, sessionId);
    }

    /** L4：清空该会话记忆（重新开始）。 */
    @PostMapping("/clear")
    public Map<String, Object> clear(@PathVariable Long catId, @RequestBody Map<String, String> body) {
        return sessions.clear(catId, body.get("sessionId"));
    }

    /** 标记待压缩：下一轮对话开始时按 L1→L2→L3→L4 自收敛。 */
    @PostMapping("/compress")
    public Map<String, Object> compress(@PathVariable Long catId, @RequestBody Map<String, String> body) {
        return sessions.markCompress(catId, body.get("sessionId"));
    }

    /** 该猫工作区文件清单（会话区文件抽屉）。 */
    @GetMapping("/files")
    public List<Map<String, Object>> files(@PathVariable Long catId) {
        return sessions.listFiles(catId);
    }

    /** 预览该猫工作区里的一个文本文件。 */
    @GetMapping("/file")
    public Map<String, Object> file(@PathVariable Long catId, @RequestParam String path) {
        return sessions.readFile(catId, path);
    }

    /** 保存（覆盖）该猫工作区里的一个文本文件：文件预览弹窗的「编辑」用。 */
    @PutMapping("/file")
    public Map<String, Object> writeFile(@PathVariable Long catId, @RequestParam String path,
                                         @RequestBody Map<String, String> body) {
        return sessions.writeFile(catId, path, body.get("content"));
    }

    /** 预览该猫工作区里的二进制文档（xlsx/docx）：base64 下发，前端 JSZip 解析渲染。 */
    @GetMapping("/file-b64")
    public Map<String, Object> fileB64(@PathVariable Long catId, @RequestParam String path) {
        return sessions.readFileBase64(catId, path);
    }

    /** 删除该猫工作区里的一个文件或文件夹（文件树右键菜单；根目录与内部隐藏目录不可删）。 */
    @DeleteMapping("/file")
    public Map<String, Object> deleteFile(@PathVariable Long catId, @RequestParam String path) {
        sessions.deleteWsEntry(catId, path);
        return Map.of("ok", true);
    }
}

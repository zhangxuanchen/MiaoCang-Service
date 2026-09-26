package com.miaocang.controller;

import com.miaocang.service.LibrarySyncService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 书库同步：与喵藏客户端书库（git 仓库）联动 —— 状态 / 导入 / 重建 / 提交 / 推送 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibrarySyncService librarySync;

    public LibraryController(LibrarySyncService librarySync) {
        this.librarySync = librarySync;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return librarySync.status();
    }

    /** rescan 式导入：收编客户端 push 过来的 md，剔除文件已消失的条目 */
    @PostMapping("/import")
    public Map<String, Object> importFromLibrary() {
        Map<String, Object> result = librarySync.importFromLibrary();
        boolean committed = librarySync.commit("从客户端书库导入");
        result.put("committed", committed);
        return result;
    }

    /** 全量重建：导入 + 补写缺失文件 + 重建 .catalog.json / INDEX.md */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        Map<String, Object> result = librarySync.rebuildAll();
        boolean committed = librarySync.commit("重建书库索引");
        result.put("committed", committed);
        return result;
    }

    /** 手动提交书库变更 */
    @PostMapping("/commit")
    public Map<String, Object> commit(@RequestBody(required = false) Map<String, Object> body) {
        String message = body == null || body.get("message") == null || String.valueOf(body.get("message")).isBlank()
                ? "书库同步" : String.valueOf(body.get("message"));
        return Map.of("committed", librarySync.commit(message));
    }

    /** 可选：推送到配置的远端 */
    @PostMapping("/push")
    public Map<String, Object> push() {
        return Map.of("message", librarySync.push());
    }
}

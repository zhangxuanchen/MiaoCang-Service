package com.miaocang.chat;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 猫工作区文件工具（会话区的操作范围 = 当前猫的存储文档位置；管理员 = 整个书库）。
 *
 * <p>每只猫的工作区根 = {@code <library.dir>/<cat.workspacePath>/}，本类把
 * read_file / write_file / list_files 三个工具的可见范围严格限制在该目录内
 * （路径白名单守卫，防目录穿越），使 Agent 只能读写这只猫自己的文档。
 * Wiki 管理员（catId=0）的工作区根 = 书库根，工具范围即整个书库。
 *
 * <p>设计原则（沿用 Memory-Observatory）：错误回传 ERROR 字符串而非抛异常、
 * {@code @ToolParam} 显式 name、写文件用临时文件 + 原子移动避免脏文件。
 */
public class WorkspaceFileTools {

    /** 单次读取返回的最大字符数。 */
    private static final int MAX_READ_CHARS = 50_000;
    /** list_files / grep_files / glob_files 最多返回的条目数。 */
    private static final int MAX_LIST = 200;
    /** 搜索时最多遍历的文件数，防止整库扫描被拖住。 */
    private static final int MAX_SCAN = 3000;

    /**
     * write_file 结构化结果：{"type":"file","action":"write","ok":true/false,"path":"…","message":"…"}。
     * 前端识别后做实时反馈（刷新文件树 / 重载被改文件的预览 / 失败红色提示）；
     * read/list 结果保持纯原文返回给模型（大文本进 JSON 只会加解析负担）。
     */
    private static String wrapWrite(boolean ok, String path, String message) {
        return "{\"type\":\"file\",\"action\":\"write\",\"ok\":" + ok
                + ",\"path\":\"" + esc(path) + "\",\"message\":\"" + esc(message) + "\"}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "    ");
    }

    /** 工作区根目录（所有相对路径的基准）。 */
    private final Path workspaceRoot;
    /** 工作区相对路径（如 {@code admin/cat-1}），用于把喵误传的「整条书库路径」归一回来。 */
    private final String workspacePath;
    /** 记忆召回依赖（memKey 由会话层绑定；null 时 memory_recall 报未启用）。 */
    private final ConversationMemory memory;
    private final String memKey;

    public WorkspaceFileTools(Path workspaceRoot, String workspacePath,
                             ConversationMemory memory, String memKey) {
        this.workspaceRoot = workspaceRoot;
        this.workspacePath = workspacePath;
        this.memory = memory;
        this.memKey = memKey;
    }

    /* ---- 路径守卫：相对路径解析到工作区内，防穿越 ---- */

    private Path guard(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        /* 喵的读文件 / shell 视角是整条书库路径，它常把 data/library/admin/cat-1/origin/x.md
           当工作区相对路径传出来；先归一，否则只读会误报不存在、写入会往里长一棵镜像目录树 */
        String rel = CatWorkspaceService.relativeToWorkspace(workspacePath, relPath);
        Path p = workspaceRoot.resolve(rel).normalize();
        if (!p.startsWith(workspaceRoot) || p.equals(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界：只允许访问工作区内的文件");
        }
        return p;
    }

    private static String err(Exception e) {
        return "ERROR: " + e.getMessage();
    }

    /** 工具描述里的范围说明（注解值需编译期常量，故用通用文案；具体范围由系统提示词进一步说明）。 */
    private static final String SCOPE_HINT =
            "工作区范围：猫助理=这只猫的工作区；Wiki管理员=整个书库（含所有猫的工作区与公共区）。";

    @Tool(name = "read_file",
            description = "读取工作区里一个文本文件的内容并返回；"
                    + "路径必须是相对工作区的相对路径，如 'notes/想法.md'。" + SCOPE_HINT)
    public String readFile(
            @ToolParam(name = "path", description = "文件相对路径，如 'notes/想法.md'")
            String path) {
        try {
            Path p = guard(path);
            if (!Files.exists(p)) return "ERROR: 文件不存在: " + path;
            if (Files.isDirectory(p)) return "ERROR: 目标是目录，请先 list_files 查看结构";
            String s = Files.readString(p, StandardCharsets.UTF_8);
            if (s.length() > MAX_READ_CHARS) {
                return s.substring(0, MAX_READ_CHARS) + "\n…（文件较长已截断，全文 "
                        + s.length() + " 字符，可按片段继续读取）";
            }
            return s;
        } catch (Exception e) {
            return err(e);
        }
    }

    @Tool(name = "write_file",
            description = "在工作区里创建或完整覆盖一个文件（自动创建缺失的父目录）。"
                    + "content 是文件的完整新内容，不是增量；只改一小段请优先用 edit_file。" + SCOPE_HINT)
    public String writeFile(
            @ToolParam(name = "path", description = "目标文件相对路径")
            String path,
            @ToolParam(name = "content", description = "文件完整内容")
            String content) {
        try {
            Path p = guard(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            writeAtomic(p, content == null ? "" : content);
            return wrapWrite(true, path, "OK: 已写入 " + path + "（" + (content == null ? 0 : content.length()) + " 字符），前端文件树与预览已同步刷新");
        } catch (Exception e) {
            return wrapWrite(false, path, "ERROR: " + e.getMessage());
        }
    }

    @Tool(name = "edit_file",
            description = "在工作区文件里做局部替换（old_string → new_string），文件其余部分原样不动。"
                    + "改一段、追加一节、修个错字都用它——比 write_file 全量覆盖安全，不会把没写到的原文弄丢。"
                    + "old_string 默认要求在文件里唯一出现（0 次或多次会报错并告知实际次数，请多带上下文让它唯一）。" + SCOPE_HINT)
    public String editFile(
            @ToolParam(name = "path", description = "目标文件相对路径")
            String path,
            @ToolParam(name = "old_string", description = "要被替换掉的原文片段（含原有的换行与缩进）")
            String oldString,
            @ToolParam(name = "new_string", description = "替换成的新内容；传空串即删除该片段")
            String newString,
            @ToolParam(name = "replace_all", required = false,
                    description = "可选，true 时替换全部出现（默认 false，要求唯一）")
            Boolean replaceAll) {
        String rep = newString == null ? "" : newString;
        try {
            if (oldString == null || oldString.isEmpty()) throw new IllegalArgumentException("old_string 不能为空");
            Path p = guard(path);
            if (!Files.isRegularFile(p)) throw new IllegalArgumentException("文件不存在: " + path);
            String s = Files.readString(p, StandardCharsets.UTF_8);
            int count = countOf(s, oldString);
            if (count == 0) {
                throw new IllegalArgumentException("old_string 在文件里没找到（要跟原文逐字一致，包括换行与缩进）");
            }
            boolean all = Boolean.TRUE.equals(replaceAll);
            if (count > 1 && !all) {
                throw new IllegalArgumentException("old_string 在文件里出现了 " + count + " 次、不唯一；"
                        + "请多带一点上下文让它唯一，或传 replace_all=true 全部替换");
            }
            String out = all ? s.replace(oldString, rep) : replaceFirst(s, oldString, rep);
            writeAtomic(p, out);
            return wrapWrite(true, path, "OK: 已在 " + path + " 替换 " + (all ? count : 1) + " 处，前端文件树与预览已同步刷新");
        } catch (Exception e) {
            return wrapWrite(false, path, "ERROR: " + e.getMessage());
        }
    }

    @Tool(name = "grep_files",
            description = "在工作区的文件里按子串搜索（区分大小写，不是正则），返回「文件:行号: 匹配行」。"
                    + "想知道「哪篇文档提到过某个词」时用它，比逐个 read_file 快得多。" + SCOPE_HINT)
    public String grepFiles(
            @ToolParam(name = "pattern", description = "要搜索的子串，如 '引力归档'")
            String pattern,
            @ToolParam(name = "subPath", required = false, description = "可选，限定搜索的相对目录")
            String subPath) {
        try {
            if (pattern == null || pattern.isBlank()) return "ERROR: pattern 不能为空";
            Path base = (subPath == null || subPath.isBlank()) ? workspaceRoot : guard(subPath);
            if (!Files.isDirectory(base)) return "ERROR: 不是目录: " + subPath;
            StringBuilder sb = new StringBuilder();
            int hits = 0, files = 0;
            for (Path p : walkFiles(base)) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (Exception notText) {
                    continue; /* 非 UTF-8 文本（图片 / xlsx）读不了，跳过 */
                }
                boolean first = true;
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains(pattern)) continue;
                    if (hits >= MAX_LIST) {
                        return sb.append("…（命中超过 ").append(MAX_LIST).append(" 条已截断，请收窄 pattern 或限定 subPath）").toString();
                    }
                    if (first) {
                        files++;
                        first = false;
                    }
                    hits++;
                    sb.append(workspaceRoot.relativize(p)).append(':').append(i + 1).append(": ")
                            .append(clip(lines.get(i), 160)).append('\n');
                }
            }
            if (hits == 0) return "（工作区里没搜到「" + pattern + "」）";
            return sb.append("共 ").append(hits).append(" 条命中，分布在 ").append(files).append(" 个文件").toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @Tool(name = "glob_files",
            description = "按 glob 模式列出工作区里的文件路径（含大小），如 'origin/**/*.md'、'**/INDEX.md'。"
                    + "找「某个目录下有哪些文档」时用它。" + SCOPE_HINT)
    public String globFiles(
            @ToolParam(name = "pattern", description = "glob 模式，如 'origin/**/*.md'")
            String pattern,
            @ToolParam(name = "subPath", required = false, description = "可选，限定起始目录")
            String subPath) {
        try {
            if (pattern == null || pattern.isBlank()) return "ERROR: pattern 不能为空";
            Path base = (subPath == null || subPath.isBlank()) ? workspaceRoot : guard(subPath);
            if (!Files.isDirectory(base)) return "ERROR: 不是目录: " + subPath;
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Path p : walkFiles(base)) {
                Path rel = workspaceRoot.relativize(p);
                if (!m.matches(rel) && !m.matches(p.getFileName())) continue;
                if (++n > MAX_LIST) {
                    sb.append("…（超过 ").append(MAX_LIST).append(" 条已截断）");
                    break;
                }
                sb.append(rel).append("（").append(sizeText(Files.size(p))).append("）\n");
            }
            if (sb.isEmpty()) return "（没有匹配「" + pattern + "」的文件）";
            return sb.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @Tool(name = "list_files",
            description = "列出工作区的文件与目录（递归，含相对路径与大小）。"
                    + "首次接触工作区时先调用它了解有哪些文档。" + SCOPE_HINT)
    public String listFiles(
            @ToolParam(name = "subPath", required = false,
                    description = "可选，相对路径子目录；留空列出工作区根")
            String subPath) {
        try {
            Path base = (subPath == null || subPath.isBlank()) ? workspaceRoot : guard(subPath);
            if (!Files.isDirectory(base)) return "ERROR: 不是目录: " + subPath;
            try (Stream<Path> walk = Files.walk(base)) {
                List<Path> items = walk
                        .filter(p -> !p.equals(base))
                        .filter(p -> !p.getFileName().toString().startsWith(".")
                                && !p.getFileName().toString().endsWith(".tmp"))
                        .sorted(Comparator.comparing(p -> p.toString()))
                        .limit(MAX_LIST + 1)
                        .toList();
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Path p : items) {
                    if (++n > MAX_LIST) {
                        sb.append("…（超过 ").append(MAX_LIST).append(" 条已截断）");
                        break;
                    }
                    String rel = workspaceRoot.relativize(p).toString();
                    if (Files.isDirectory(p)) {
                        sb.append("[目录] ").append(rel).append("/\n");
                    } else {
                        sb.append("        ").append(rel)
                                .append("（").append(sizeText(Files.size(p))).append("）\n");
                    }
                }
                if (sb.isEmpty()) return "（工作区还是空的）";
                return sb.toString();
            }
        } catch (Exception e) {
            return err(e);
        }
    }

    @Tool(name = "memory_recall",
            description = "按归档 id 找回某一次被压缩/折叠的记忆完整原文（用户+助手）。"
                    + "当上下文里只有摘要、而用户需要早前对话的完整细节时调用。"
                    + "id 来自摘要中的【记忆归档 #id】标记。")
    public String memoryRecall(
            @ToolParam(name = "archiveId", description = "记忆归档标记里给出的 id 字符串")
            String archiveId) {
        if (memory == null || memKey == null) {
            return "ERROR: 当前会话未启用记忆召回。";
        }
        return memory.findArchive(memKey, archiveId);
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /* ---- 内部工具 ---- */

    /** 遍历工作区条目：跳过任何以 {@code .} 开头的层级（.workbench / .archive / .context 等内部目录）与 .tmp 临时文件。 */
    private List<Path> walk(Path base, boolean filesOnly) throws IOException {
        try (Stream<Path> w = Files.walk(base)) {
            return w.filter(p -> !p.equals(base))
                    .filter(p -> !filesOnly || Files.isRegularFile(p))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .filter(p -> {
                        for (Path seg : base.relativize(p)) {
                            if (seg.toString().startsWith(".")) return false;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_SCAN + 1L)
                    .toList();
        }
    }

    private List<Path> walkFiles(Path base) throws IOException {
        return walk(base, true);
    }

    /** 原子写入：先写同目录临时文件再移动，避免写一半留下脏文件。 */
    private static void writeAtomic(Path p, String content) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    private static String replaceFirst(String haystack, String needle, String rep) {
        int at = haystack.indexOf(needle);
        return at < 0 ? haystack : haystack.substring(0, at) + rep + haystack.substring(at + needle.length());
    }

    /** 搜索结果单行摘要：掐掉首尾空白，超长截断，便于一行一条阅读。 */
    private static String clip(String line, int max) {
        String s = line.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

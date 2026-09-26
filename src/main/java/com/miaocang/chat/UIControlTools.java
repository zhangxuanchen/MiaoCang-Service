package com.miaocang.chat;

import com.miaocang.entity.ContentItem;
import com.miaocang.repository.ContentItemRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Set;

/**
 * 详情页指挥工具：让喵通过工具调用驱动前端 UI（打开详情 / 改标题 / 打开工作区文件预览）。
 *
 * <p>协议约定：工具返回 JSON 字符串（前端按 {@code type} 字段路由）——
 * <ul>
 *   <li>{@code {"type":"doc","action":"open","docId":1,"title":"…"}} → 前端打开该内容详情弹窗并高亮</li>
 *   <li>{@code {"type":"doc","action":"update","docId":1,"title":"…"}} → 详情弹窗开着且同 id 时实时刷新 + toast 反馈</li>
 *   <li>{@code {"type":"file","action":"read","path":"…"}} → 前端打开左侧工作区文件预览</li>
 *   <li>{@code {"type":"error","message":"…"}} → 前端红色反馈</li>
 * </ul>
 * 文件写入类反馈（刷新文件树 / 重载预览）由 WorkspaceFileTools 的结果自动携带
 * （{@code {"type":"file","action":"write|read|list",…}}），本类只管「指挥前端跳转/打开」。
 */
public class UIControlTools {

    private final boolean master;
    private final ContentItemRepository contents;
    /** 本会话所属猫 id（构造时绑定；管理员模式忽略归属校验）。 */
    private final Long catId;

    public UIControlTools(boolean master, ContentItemRepository contents, Long catId) {
        this.master = master;
        this.contents = contents;
        this.catId = catId;
    }

    private static String err(String msg) {
        return "{\"type\":\"error\",\"message\":\"" + esc(msg) + "\"}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    /** 打开一条书库内容的详情弹窗（喵说「打开 xxx」时用；id 来自 list_files/搜索结果或用户提到的编号）。 */
    @Tool(name = "open_doc",
            description = "在主人屏幕上打开一条书库内容的详情页（详情弹窗）。"
                    + "当主人说「打开xxx」「给我看看xxx」且你能确定内容 id 时调用；"
                    + "id 可从 list_files 里 points/ 目录的文件名或检索结果中获得。"
                    + "返回 JSON 指令由前端执行，无需再向主人复述「已打开」。")
    public String openDoc(
            @ToolParam(name = "docId", description = "内容 id（数字）")
            Long docId) {
        if (docId == null) return err("docId 不能为空");
        ContentItem c = contents.findById(docId).orElse(null);
        if (c == null) return err("内容不存在: " + docId);
        if (!owns(c)) return err("该内容不属于本喵的领地，无权打开");
        return "{\"type\":\"doc\",\"action\":\"open\",\"docId\":" + docId
                + ",\"title\":\"" + esc(c.getTitle()) + "\"}";
    }

    /** 修改一条书库内容的标题（详情页开着时会实时刷新出反馈）。 */
    @Tool(name = "rename_doc",
            description = "修改一条书库内容的标题（即时生效，详情页会实时刷新）。"
                    + "主人说「把xxx改名为yyy」时调用。改正文请用 write_file 写工作区文件，改标题用本工具。")
    public String renameDoc(
            @ToolParam(name = "docId", description = "内容 id（数字）")
            Long docId,
            @ToolParam(name = "newTitle", description = "新标题")
            String newTitle) {
        if (docId == null) return err("docId 不能为空");
        if (newTitle == null || newTitle.isBlank()) return err("新标题不能为空");
        ContentItem c = contents.findById(docId).orElse(null);
        if (c == null) return err("内容不存在: " + docId);
        if (!owns(c)) return err("该内容不属于本喵的领地，无权修改");
        c.setTitle(newTitle.strip());
        contents.save(c);
        return "{\"type\":\"doc\",\"action\":\"update\",\"docId\":" + docId
                + ",\"title\":\"" + esc(c.getTitle()) + "\"}";
    }

    /** 打开一个工作区文件的预览（前端中栏；等价于主人在文件树里点它）。 */
    @Tool(name = "open_file",
            description = "在主人屏幕上打开工作区一个文件的预览（中栏详情）。"
                    + "主人说「打开 notes/xx.md」时调用；路径与 read_file 同规则（工作区相对路径）。")
    public String openFile(
            @ToolParam(name = "path", description = "文件相对路径")
            String path) {
        if (path == null || path.isBlank()) return err("路径不能为空");
        return "{\"type\":\"file\",\"action\":\"read\",\"path\":\"" + esc(path.strip()) + "\"}";
    }

    /** 归属校验：管理员放开；猫助理只允许本猫领地内容。 */
    private boolean owns(ContentItem c) {
        return master || (catId != null && java.util.Objects.equals(c.getCatId(), catId));
    }
}

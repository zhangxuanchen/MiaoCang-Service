package com.miaocang.controller;

import com.miaocang.auth.CurrentUser;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.User;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端握手：连通性 + 身份 + 喵列表（带待裁决数）一次拿齐。
 * 客户端配置好服务器地址与 token 后先调本接口：
 * - 200 = 网络通 + token 有效 + 返回协议版本与该用户全部喵；
 * - 401 = token 失效（走重新登录）。
 * protocol 版本号：服务端同步协议破坏性变更时递增，客户端据此提示升级。
 */
@RestController
@RequestMapping("/api/client")
public class ClientGatewayController {

    /** 同步协议版本（manifest/pull/push 字段结构有破坏性变更时 +1） */
    public static final int PROTOCOL_VERSION = 1;

    private final CatRepository catRepo;
    private final ContentItemRepository contentRepo;

    public ClientGatewayController(CatRepository catRepo, ContentItemRepository contentRepo) {
        this.catRepo = catRepo;
        this.contentRepo = contentRepo;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        User me = CurrentUser.get();
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Cat c : catRepo.findByUserIdOrderByOrderIndexAscIdAsc(me.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("icon", c.getIcon());
            m.put("workspacePath", c.getWorkspacePath());
            m.put("pendingCount", contentRepo.countByCatIdAndStatus(c.getId(), ContentItem.STATUS_PENDING));
            cats.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocol", PROTOCOL_VERSION);
        out.put("user", Map.of("id", me.getId(), "username", me.getUsername(), "displayName", me.getDisplayName()));
        out.put("cats", cats);
        return out;
    }
}

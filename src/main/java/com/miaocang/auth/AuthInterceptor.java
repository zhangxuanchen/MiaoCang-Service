package com.miaocang.auth;

import com.miaocang.entity.AuthToken;
import com.miaocang.entity.Cat;
import com.miaocang.entity.ContentItem;
import com.miaocang.entity.User;
import com.miaocang.repository.AuthTokenRepository;
import com.miaocang.repository.CatRepository;
import com.miaocang.repository.ContentItemRepository;
import com.miaocang.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录拦截器：/api/** 全部要求 Bearer Token（登录/注册除外）。
 * 校验通过后把 User 写入 request attribute（CurrentUser 取用），
 * 并对携带资源 id 的路径做归属校验——猫、内容、原文件只允许其主人访问。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Pattern CAT_PATH = Pattern.compile("^/api/cats/(\\d+)(/.*)?$");
    private static final Pattern CONTENT_PATH = Pattern.compile("^/api/(?:contents|files)/(\\d+)(/.*)?$");

    private final UserRepository userRepo;
    private final AuthTokenRepository tokenRepo;
    private final CatRepository catRepo;
    private final ContentItemRepository contentRepo;

    public AuthInterceptor(UserRepository userRepo, AuthTokenRepository tokenRepo,
                           CatRepository catRepo, ContentItemRepository contentRepo) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
        this.catRepo = catRepo;
        this.contentRepo = contentRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (request.getMethod().equals("OPTIONS")) return true;
        /* 白名单：登录 / 注册 */
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) return true;

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, HttpStatus.UNAUTHORIZED, "未登录");
        }
        Optional<AuthToken> t = tokenRepo.findByToken(header.substring(7).trim());
        if (t.isEmpty() || t.get().getExpiresAt().isBefore(LocalDateTime.now())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        User user = userRepo.findById(t.get().getUserId()).orElse(null);
        if (user == null) return reject(response, HttpStatus.UNAUTHORIZED, "账号不存在");

        /* 资源归属校验：猫只属于主人（catId=0 = Wiki管理员，不对应真实猫，登录即可） */
        Matcher m = CAT_PATH.matcher(path);
        if (m.matches()) {
            Long catId = Long.valueOf(m.group(1));
            if (catId != 0L) {
                Cat cat = catRepo.findById(catId).orElse(null);
                if (cat == null) return reject(response, HttpStatus.NOT_FOUND, "猫不存在");
                if (!user.getId().equals(cat.getUserId())) return reject(response, HttpStatus.FORBIDDEN, "这不是你养的喵");
            }
        }
        /* 内容 / 原文件：内容归属某只猫 → 间接归属主人 */
        Matcher c = CONTENT_PATH.matcher(path);
        if (c.matches()) {
            Long contentId = Long.valueOf(c.group(1));
            ContentItem item = contentRepo.findById(contentId).orElse(null);
            if (item != null && item.getCatId() != null) {
                Cat cat = catRepo.findById(item.getCatId()).orElse(null);
                if (cat == null || !user.getId().equals(cat.getUserId())) {
                    return reject(response, HttpStatus.FORBIDDEN, "无权访问这条内容");
                }
            }
        }
        /* 会话区 / 记忆等带 catId 查询参数的接口：catId=0（Wiki管理员）登录即可，>0 校验归属 */
        String qCat = request.getParameter("catId");
        if (qCat != null && !qCat.isBlank() && !"0".equals(qCat)) {
            try {
                Long catId = Long.valueOf(qCat);
                Cat cat = catRepo.findById(catId).orElse(null);
                if (cat != null && !user.getId().equals(cat.getUserId())) {
                    return reject(response, HttpStatus.FORBIDDEN, "这不是你养的喵");
                }
            } catch (NumberFormatException ignore) { /* 非数字参数交给业务层 */ }
        }
        request.setAttribute(CurrentUser.ATTR, user);
        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }
}

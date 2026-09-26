package com.miaocang.auth;

import com.miaocang.entity.AuthToken;
import com.miaocang.entity.User;
import com.miaocang.repository.AuthTokenRepository;
import com.miaocang.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 登录认证（参照 citadel 的 API 形态）：注册 / 登录 / 当前用户 / 登出。
 * 密码 BCrypt 散列存储；登录签发 Bearer Token（7 天有效，落库持久化，重启不掉线）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long TOKEN_DAYS = 7;

    private final UserRepository userRepo;
    private final AuthTokenRepository tokenRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository userRepo, AuthTokenRepository tokenRepo) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
    }

    /** 注册：用户名 + 密码。用户名即其专属工作区根目录名，只允许字母数字下划线中文。 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = safe(body.get("username"));
        String password = body.get("password") == null ? "" : body.get("password");
        if (username.length() < 2 || username.length() > 30) throw new IllegalArgumentException("用户名 2~30 个字符");
        if (!username.matches("[\\w\\u4e00-\\u9fa5-]+")) throw new IllegalArgumentException("用户名只允许中文、字母、数字、下划线、连字符");
        if (password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
        if (userRepo.existsByUsername(username)) throw new IllegalArgumentException("用户名已被占用");
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(username);
        userRepo.save(u);
        return Map.of("ok", true, "message", "注册成功，请登录");
    }

    /** 登录：成功签发 token（Bearer），前端存 localStorage 后续请求携带 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = safe(body.get("username"));
        String password = body.get("password") == null ? "" : body.get("password");
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!encoder.matches(password, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        tokenRepo.deleteExpired(LocalDateTime.now());
        AuthToken t = new AuthToken();
        t.setToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        t.setUserId(u.getId());
        t.setExpiresAt(LocalDateTime.now().plusDays(TOKEN_DAYS));
        tokenRepo.save(t);
        return Map.of("token", t.getToken(), "expiresAt", t.getExpiresAt().toString(), "user", profile(u));
    }

    /** 当前登录用户（前端启动时校验 token 用） */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(CurrentUser.ATTR) User user) {
        return profile(user);
    }

    /** 登出：删除令牌 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestAttribute(CurrentUser.ATTR) User user,
                                      @RequestHeader(value = "Authorization", required = false) String header) {
        if (header != null && header.startsWith("Bearer ")) {
            tokenRepo.deleteByToken(header.substring(7).trim());
        }
        return Map.of("ok", true);
    }

    private Map<String, Object> profile(User u) {
        return Map.of("id", u.getId(), "username", u.getUsername(), "displayName", u.getDisplayName() == null ? u.getUsername() : u.getDisplayName());
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }
}

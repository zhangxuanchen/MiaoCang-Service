package com.miaocang.auth;

import com.miaocang.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 当前登录用户取用工具：AuthInterceptor 校验通过后写入 request attribute，业务代码随时取 */
public final class CurrentUser {

    public static final String ATTR = "mc.user";

    private CurrentUser() {}

    /** 取当前登录用户；未登录（理论上被拦截器挡住不会走到）抛 401 */
    public static User get() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            if (sra.getRequest().getAttribute(ATTR) instanceof User u) return u;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}

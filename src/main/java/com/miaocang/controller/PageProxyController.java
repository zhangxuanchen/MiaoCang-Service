package com.miaocang.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外链页面代理：详情页内嵌浏览用。
 *
 * 微信（以及不少站点）用 CSP 的 frame-ancestors 禁止被第三方 iframe 嵌入，直接 &lt;iframe src="原址"&gt;
 * 会被浏览器拒绝渲染而白屏。这里改由服务端把页面抓回来做「只读阅读」改写，再以本服务同源返回：
 *   - 剥掉原页面的 &lt;script&gt; 与其自带 CSP meta，避免第三方脚本在本服务源下执行
 *   - 注入 &lt;base href="原址"&gt;，相对路径的图片/CSS 仍按原站解析
 *   - 注入 referrer=no-referrer，绕开微信图片的防盗链
 *   - 响应头只放 frame-ancestors 'self'，于是本服务的详情页可以正常嵌它
 */
@RestController
@RequestMapping("/api/proxy")
public class PageProxyController {

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    /** 抓取上限，防止把超大页面灌进内存 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final Pattern SCRIPT = Pattern.compile(
            "<script\\b[^>]*>.*?</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_SELF_CLOSING = Pattern.compile(
            "<script\\b[^>]*/\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CSP = Pattern.compile(
            "<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?Content-Security-Policy[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_OPEN = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @GetMapping("/page")
    public ResponseEntity<?> page(@RequestParam("url") String url) {
        URI target;
        try {
            target = URI.create(url.trim());
        } catch (Exception e) {
            return err(HttpStatus.BAD_REQUEST, "链接格式不对");
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return err(HttpStatus.BAD_REQUEST, "只支持 http/https 链接");
        }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
            return err(HttpStatus.BAD_REQUEST, "不代理本机地址");
        }

        HttpResponse<byte[]> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            return err(HttpStatus.BAD_GATEWAY, "抓取失败：" + e.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return err(HttpStatus.BAD_GATEWAY, "原站返回 " + resp.statusCode());
        }
        if (resp.body().length > MAX_BYTES) {
            return err(HttpStatus.BAD_REQUEST, "页面超过 4MB，不做内嵌（可点「外部浏览器」打开）");
        }

        String ctype = resp.headers().firstValue("Content-Type").orElse("");
        if (!ctype.toLowerCase(Locale.ROOT).contains("html")) {
            return err(HttpStatus.BAD_REQUEST, "该链接不是网页（" + (ctype.isEmpty() ? "未知类型" : ctype) + "）");
        }

        String html = new String(resp.body(), charsetOf(ctype));
        html = rewrite(html, target.toString());

        HttpHeaders h = new HttpHeaders();
        h.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        h.setCacheControl(CacheControl.noStore());
        /* 只允许本服务自己的页面嵌它（内嵌浏览正是这么用的），并把脚本关掉 */
        h.set("Content-Security-Policy", "frame-ancestors 'self'; script-src 'none'");
        return new ResponseEntity<>(html, h, HttpStatus.OK);
    }

    /** 读取改写：去脚本与自带 CSP、补 base 与 referrer 策略 */
    private static String rewrite(String html, String url) {
        String out = SCRIPT.matcher(html).replaceAll("");
        out = SCRIPT_SELF_CLOSING.matcher(out).replaceAll("");
        out = META_CSP.matcher(out).replaceAll("");

        StringBuilder inject = new StringBuilder()
                .append("<base href=\"").append(url.replace("\"", "%22")).append("\">")
                .append("<meta name=\"referrer\" content=\"no-referrer\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'\">")
                /* 微信正文 div 默认内联 visibility:hidden / opacity:0，靠它自己的脚本启动后才显示；
                   脚本已被我们剥掉，这里直接解锁，否则内嵌进来正文是一片空白 */
                .append("<style>#js_content{visibility:visible !important;opacity:1 !important;}</style>");

        Matcher head = HEAD_OPEN.matcher(out);
        if (head.find()) {
            return out.substring(0, head.end()) + inject + out.substring(head.end());
        }
        Matcher root = HTML_OPEN.matcher(out);
        if (root.find()) {
            return out.substring(0, root.end()) + "<head>" + inject + "</head>" + out.substring(root.end());
        }
        return "<!DOCTYPE html><html><head>" + inject + "</head><body>" + out + "</body></html>";
    }

    private static Charset charsetOf(String contentType) {
        Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (Exception ignored) {
                /* 未知字符集按 UTF-8 */
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static ResponseEntity<Map<String, String>> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", msg));
    }
}
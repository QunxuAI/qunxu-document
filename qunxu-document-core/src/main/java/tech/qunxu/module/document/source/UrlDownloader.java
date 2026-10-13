package tech.qunxu.module.document.source;

import tech.qunxu.module.document.support.Sha256Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 下载器（模块内自足，用 JDK {@link HttpClient}，不引第三方 HTTP 库）。
 * <p>
 * 行为约定：
 * <ul>
 *   <li>禁用自动重定向，手工跟随且不超过 {@link UrlOptions#maxRedirects()}，<b>每一跳重新过 SSRF 校验</b>；</li>
 *   <li>流式读取字节并边读边算 SHA-256，超过 {@link UrlOptions#maxBytes()} 立即中断，避免超大响应撑爆内存；</li>
 *   <li>文件名优先取 {@code Content-Disposition} 的 filename，其次取 URL 路径最后一段。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class UrlDownloader {

    private static final Logger log = LoggerFactory.getLogger(UrlDownloader.class);

    private static final Pattern DISPOSITION_NAME =
            Pattern.compile("filename\\*?=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private final UrlOptions options;
    private final SsrfGuard guard;
    private final HttpClient client;

    public UrlDownloader(UrlOptions options, SsrfGuard guard) {
        this.options = options == null ? UrlOptions.defaults() : options;
        this.guard = guard == null ? new SsrfGuard(this.options) : guard;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(Math.max(1, this.options.connectTimeoutSeconds())))
                .build();
    }

    /**
     * 下载 URL 内容。
     *
     * @param url 绝对 URL（http/https）
     * @return 字节内容 + 推断文件名 + Content-Type + 内容哈希
     * @throws IOException                  网络失败、状态码非 2xx、超出字节上限或重定向次数
     * @throws IllegalStateException        URL 输入源未配置白名单
     * @throws IllegalArgumentException     URL 未通过 SSRF 校验
     */
    public Downloaded download(String url) throws IOException {
        String current = url;
        int redirects = 0;
        while (true) {
            URI uri = guard.validate(current);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(Math.max(1, options.readTimeoutSeconds())))
                    .header("User-Agent", "qunxu-document/1.0")
                    .build();
            HttpResponse<InputStream> httpResponse;
            try {
                httpResponse = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("URL 下载被中断：" + uri, e);
            }
            int status = httpResponse.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(httpResponse.body());
                String location = httpResponse.headers().firstValue("location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new IOException("HTTP " + status + " 重定向缺少 Location：" + uri);
                }
                if (redirects >= options.maxRedirects()) {
                    throw new IOException("重定向次数超过上限 " + options.maxRedirects() + "：" + url);
                }
                URI next = uri.resolve(location);
                log.debug("URL 重定向 {} -> {}", uri, next);
                current = next.toString();
                redirects++;
                continue;
            }
            try (InputStream body = httpResponse.body()) {
                if (status < 200 || status >= 300) {
                    throw new IOException("URL 返回状态码 " + status + "：" + uri);
                }
                String contentType = httpResponse.headers().firstValue("content-type").orElse(null);
                String disposition = httpResponse.headers().firstValue("content-disposition").orElse(null);
                Sha256Util.Content content = Sha256Util.readWithHash(body, options.maxBytes());
                String fileName = resolveFileName(disposition, uri);
                return new Downloaded(content.bytes(), content.sha256(), fileName, contentType, url);
            }
        }
    }

    /** Content-Disposition → URL 路径末段 → 兜底名 */
    private String resolveFileName(String disposition, URI uri) {
        if (disposition != null && !disposition.isBlank()) {
            Matcher matcher = DISPOSITION_NAME.matcher(disposition);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                int semicolon = name.indexOf(';');
                if (semicolon > 0) {
                    name = name.substring(0, semicolon);
                }
                name = name.replace("\\", "/");
                name = name.substring(name.lastIndexOf('/') + 1);
                if (!name.isBlank()) {
                    return name;
                }
            }
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.isBlank()) {
                return name;
            }
        }
        return "download";
    }

    private void closeQuietly(InputStream is) {
        if (is == null) {
            return;
        }
        try {
            is.close();
        } catch (IOException ignored) {
            // 重定向响应体无需处理
        }
    }

    /** URL 是否形似 http(s)（不做白名单校验，供上层判定输入源类型） */
    public static boolean looksLikeUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 下载结果。
     *
     * @param bytes       文件字节
     * @param sha256      字节内容哈希（缓存键基础，与 URL 域名/签名参数无关）
     * @param fileName    推断文件名（用于格式判定）
     * @param contentType 响应 Content-Type，可为 null
     * @param url         发起的原始 URL（溯源用）
     */
    public record Downloaded(byte[] bytes, String sha256, String fileName, String contentType, String url) {
    }
}

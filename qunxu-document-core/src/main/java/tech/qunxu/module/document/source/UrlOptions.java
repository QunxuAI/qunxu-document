package tech.qunxu.module.document.source;

import java.util.List;

/**
 * URL 输入源的下载约束（映射 {@code qunxu.document.url.*}）。
 *
 * @param allowHosts             主机白名单（小写；条目以 {@code .} 开头表示连子域一起放行）；
 *                               <b>为空即禁用 URL 输入源</b>，不静默放行任何域名
 * @param maxBytes               单次下载字节上限，超出立即中断
 * @param connectTimeoutSeconds  连接超时
 * @param readTimeoutSeconds     读取超时
 * @param maxRedirects           最大跟随重定向次数（每一跳都重新过 SSRF 校验）
 * @param allowPrivateNetwork    是否允许解析到回环/私有/链路本地地址；默认 false，
 *                               仅本地联调或内网对象存储直链场景显式打开
 * @author ethanwang
 * @since 2026-10-10
 */
public record UrlOptions(List<String> allowHosts,
                         long maxBytes,
                         int connectTimeoutSeconds,
                         int readTimeoutSeconds,
                         int maxRedirects,
                         boolean allowPrivateNetwork) {

    /** 保守默认值：白名单为空（禁用），50MB 上限，3 次重定向，禁内网 */
    public static UrlOptions defaults() {
        return new UrlOptions(List.of(), 52_428_800L, 10, 60, 3, false);
    }

    public UrlOptions {
        allowHosts = allowHosts == null ? List.of() : List.copyOf(allowHosts);
    }
}

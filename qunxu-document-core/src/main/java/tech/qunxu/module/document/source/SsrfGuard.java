package tech.qunxu.module.document.source;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * URL 输入源的 SSRF 护栏（对齐 docling 的 {@code url} 源，但默认更保守）。
 * <p>
 * 三道闸门，任一不过即拒绝：
 * <ol>
 *   <li><b>协议</b>：仅 http/https；</li>
 *   <li><b>主机白名单</b>：{@code qunxu.document.url.allow-hosts} <b>为空即禁用整个 URL 输入源</b>
 *       ——直接抛 {@link IllegalStateException}，绝不静默降级、绝不默认放行任何域名。
 *       条目精确匹配（小写），以 {@code .} 开头的条目才允许其子域；</li>
 *   <li><b>IP 二次校验</b>：白名单可能被 DNS 重绑定绕过，故解析目标地址后逐地址检查，
 *       回环/任意/链路本地/站点本地/私有段（127.0.0.0/8、10/8、172.16/12、192.168/16、169.254/16、
 *       100.64.0.0/10、::1、fc00::/7）一律拒绝；{@link UrlOptions#allowPrivateNetwork()} 显式为 true 时跳过本闸门
 *       （仅供本地联调与内网对象存储直链）。</li>
 * </ol>
 * 重定向的每一跳都必须重新过本守卫，避免 302 跳向内网。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class SsrfGuard {

    /** 白名单未配置时的统一提示（明确告知是配置缺失，而非代码缺陷） */
    public static final String DISABLED_MESSAGE = "qunxu.document.url.allow-hosts 未配置，URL 输入源不可用";

    private final UrlOptions options;

    public SsrfGuard(UrlOptions options) {
        this.options = options == null ? UrlOptions.defaults() : options;
    }

    public UrlOptions options() {
        return options;
    }

    /**
     * 校验 URL 是否允许访问。
     *
     * @param url 待访问的绝对 URL（重定向时传解析后的目标）
     * @return 解析后的 {@link URI}
     * @throws IllegalStateException    URL 输入源被禁用（白名单为空）
     * @throws IllegalArgumentException 协议不支持、URL 非法或主机不在白名单 / 解析到被禁网段
     */
    public URI validate(String url) {
        if (options.allowHosts().isEmpty()) {
            throw new IllegalStateException(DISABLED_MESSAGE);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 为空");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 非法：" + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https 协议，当前为：" + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名：" + url);
        }
        String lowerHost = normalizeHost(host);
        if (!hostAllowed(lowerHost)) {
            throw new IllegalArgumentException("主机不在白名单 qunxu.document.url.allow-hosts 内：" + lowerHost);
        }
        if (!options.allowPrivateNetwork()) {
            assertSafeAddress(lowerHost);
        }
        return uri;
    }

    /** URL 形态判定（不做网络解析，仅协议检查），用于前置快速失败 */
    public boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 主机名匹配：精确匹配，或以 {@code .} 开头的白名单条目放行其子域 */
    private boolean hostAllowed(String host) {
        List<String> allowHosts = options.allowHosts();
        for (String raw : allowHosts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = normalizeHost(raw.trim());
            if (entry.startsWith(".")) {
                String suffix = entry.substring(1);
                if (host.equals(suffix) || host.endsWith(entry)) {
                    return true;
                }
            } else if (host.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉白名单条目/主机名可能带的末尾点与端口，统一小写 */
    private String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith(".")) {
            // 白名单的「子域」前缀需保留
            return value;
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && !value.contains("]")) {
            value = value.substring(0, colon);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 解析目标主机全部地址，任一落在被禁网段即拒绝 */
    private void assertSafeAddress(String host) {
        InetAddress[] addresses;
        try {
            // 主机名字面量（如 127.0.0.1）同样走此校验，避免白名单写成内网 IP
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("主机无法解析：" + host, e);
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                throw new IllegalArgumentException(
                        "主机 " + host + " 解析到受限地址 " + address.getHostAddress()
                                + "（回环/私有/链路本地网段被拒绝；确需访问请显式配置 "
                                + "qunxu.document.url.allow-private-network=true）");
            }
        }
    }

    private String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** 是否属于受限网段 */
    private boolean isForbidden(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes == null) {
            return true;
        }
        if (address instanceof Inet4Address) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT（isSiteLocalAddress 不覆盖）
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 240.0.0.0/4 保留、198.18.0.0/15 基准测试
            if (b0 >= 240) {
                return true;
            }
            return b0 == 198 && (b1 == 18 || b1 == 19);
        }
        if (address instanceof Inet6Address) {
            int b0 = bytes[0] & 0xFF;
            // fc00::/7 唯一本地地址（isSiteLocalAddress 对 IPv6 ULA 不生效）
            return (b0 & 0xFE) == 0xFC;
        }
        return false;
    }
}

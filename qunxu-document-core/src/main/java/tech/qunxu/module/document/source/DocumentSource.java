package tech.qunxu.module.document.source;

import tech.qunxu.module.document.support.Sha256Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 文档输入源：本地流 / 字节 / URL 三种形态统一为一个类型（对齐 docling 中
 * {@code convert("/local/path")} 与 {@code convert("https://…")} 同源）。
 * <p>
 * <b>先取字节、再算 SHA-256</b>：缓存键是内容指纹，因此 URL 换域名、换签名参数都不影响命中，
 * 同一份内容经三种入口进来得到同一个键。字节只在首次需要时物化一次并复用
 * （{@link #openStream()} 每次返回独立可读流，Backend 可重复解析）。
 * <p>
 * 模块不依赖任何存储实现：本地/对象存储路径由 app 侧用 {@code storageService::readStream}
 * 适配成 {@link StreamOpener}。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class DocumentSource {

    /** 输入源形态 */
    public enum Kind {
        /** 本地路径 / 对象存储路径（惰性流） */
        PATH,
        /** 内存字节（上传、远程 backend 回传） */
        BYTES,
        /** http(s) URL（受 SSRF 白名单约束） */
        URL
    }

    private final Kind kind;
    private final StreamOpener opener;
    private final UrlDownloader downloader;
    private final String url;

    private String name;
    private String contentType;
    private byte[] bytes;
    private String sha256;

    private DocumentSource(Kind kind, String name, StreamOpener opener, String url, UrlDownloader downloader) {
        this.kind = kind;
        this.name = name;
        this.opener = opener;
        this.url = url;
        this.downloader = downloader;
    }

    /**
     * 本地/对象存储路径：每次读取都通过 {@link StreamOpener} 打开新流。
     *
     * @param name   文件名（含扩展名，用于格式判定；缓存与格式判定都依赖它）
     * @param opener 流提供者，如 {@code storageService::readStream}
     */
    public static DocumentSource fromPath(String name, StreamOpener opener) {
        Objects.requireNonNull(opener, "StreamOpener 不能为空");
        return new DocumentSource(Kind.PATH, name, opener, null, null);
    }

    /** 内存字节（name 为空时用 sha256 前 8 位兜底，保证格式判定与元信息不缺失） */
    public static DocumentSource fromBytes(String name, byte[] bytes) {
        return new DocumentSource(Kind.BYTES, name, null, null, null).withBytes(bytes, null);
    }

    /**
     * URL 输入源（本轮仅在模块内交付；知识库链路与前端入口下一轮接入）。
     * <p>
     * 构造时不发起网络请求，字节在首次 {@link #bytes()} / {@link #sha256()} 时下载，
     * 因此 URL 不可用时不会影响未使用 URL 源的转换。
     *
     * @param url        http/https 绝对地址
     * @param downloader 模块内下载器（含 SSRF 护栏），由 app 侧的自动配置注入
     */
    public static DocumentSource fromUrl(String url, UrlDownloader downloader) {
        Objects.requireNonNull(downloader, "URL 输入源需要 UrlDownloader（请配置 qunxu.document.url.allow-hosts）");
        if (!UrlDownloader.looksLikeUrl(url)) {
            throw new IllegalArgumentException("URL 输入源需要 http/https 地址，当前为：" + url);
        }
        return new DocumentSource(Kind.URL, null, null, url, downloader);
    }

    /** 按地址自动选择形态：http(s) 走 URL，其余按路径处理 */
    public static DocumentSource of(String location, StreamOpener opener, UrlDownloader downloader) {
        if (UrlDownloader.looksLikeUrl(location)) {
            return fromUrl(location, downloader);
        }
        return fromPath(location, opener);
    }

    /** 用已下载的字节填充内容（URL 源内部使用） */
    private DocumentSource withBytes(byte[] data, String mime) {
        this.bytes = data == null ? new byte[0] : data;
        this.sha256 = Sha256Util.sha256(this.bytes);
        if (this.name == null || this.name.isBlank()) {
            this.name = "document-" + this.sha256.substring(0, Math.min(8, this.sha256.length()));
        }
        if (mime != null && !mime.isBlank()) {
            this.contentType = mime;
        }
        return this;
    }

    /**
     * 物化字节（首次调用时按形态读取/下载，之后复用同一份内容）。
     */
    public byte[] bytes() throws IOException {
        if (bytes != null) {
            return bytes;
        }
        switch (kind) {
            case BYTES -> bytes = new byte[0];
            case PATH -> {
                try (InputStream is = opener.open()) {
                    if (is == null) {
                        throw new IOException("StreamOpener 返回空流：" + name);
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    is.transferTo(bos);
                    bytes = bos.toByteArray();
                }
            }
            case URL -> {
                UrlDownloader.Downloaded downloaded = downloader.download(url);
                bytes = downloaded.bytes();
                contentType = downloaded.contentType();
                sha256 = downloaded.sha256();
                if (name == null || name.isBlank()) {
                    name = downloaded.fileName();
                }
                return bytes;
            }
            default -> throw new IOException("未知输入源类型：" + kind);
        }
        sha256 = Sha256Util.sha256(bytes);
        return bytes;
    }

    /** 打开一次读取流（调用方负责关闭；可重复调用，每次得到独立流） */
    public InputStream openStream() throws IOException {
        return new ByteArrayInputStream(bytes());
    }

    /** 内容 SHA-256（十六进制小写），缓存键的第一要素 */
    public String sha256() throws IOException {
        if (sha256 == null) {
            bytes();
        }
        return sha256;
    }

    /** 文件名（含扩展名）；URL 源在物化后由下载器推断 */
    public String name() {
        return name;
    }

    /** URL 源的原始地址，其余形态为 null */
    public String originUrl() {
        return url;
    }

    /** URL 源的 Content-Type（下载器返回），其余形态为 null，由格式探测补齐 */
    public String contentType() {
        return contentType;
    }

    /** 源字节数（触发物化） */
    public long size() throws IOException {
        return bytes().length;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return kind + ":" + (url != null ? url : name);
    }
}

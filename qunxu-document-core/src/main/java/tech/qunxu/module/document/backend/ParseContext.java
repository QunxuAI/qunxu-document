package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.image.ImageExtractor;
import tech.qunxu.module.document.model.DocumentMeta;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.source.DocumentSource;
import tech.qunxu.module.document.support.TextDecoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 单次解析的上下文（Backend 的入参包）。
 * <p>
 * 把「输入源 + 格式 + 选项 + 图片通道」打包成一个对象，是为了让 {@link DocumentBackend} 的
 * SPI 签名保持 {@code parse(context)} 不变：将来给某个 Backend 加开关，只需扩
 * {@link ConvertOptions}，不用改所有实现。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class ParseContext {

    private final DocumentSource source;
    private final DocumentFormat format;
    private final ConvertOptions options;
    private final ImageExtractor images;
    private final String fileHash;
    private final long fileSize;

    /** 文本内容惰性解码缓存（markdown/plaintext/csv 共用） */
    private String decodedText;

    public ParseContext(DocumentSource source, DocumentFormat format, ConvertOptions options,
                        ImageExtractor images, String fileHash, long fileSize) {
        this.source = source;
        this.format = format;
        this.options = options == null ? ConvertOptions.defaults() : options;
        this.images = images;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
    }

    public DocumentSource source() {
        return source;
    }

    public DocumentFormat format() {
        return format;
    }

    public ConvertOptions options() {
        return options;
    }

    /** 图片抽取通道；{@code null} 表示本次转换不抽图（options.extractImages=false 或功能关闭） */
    public ImageExtractor images() {
        return images;
    }

    /** 是否应当抽取内嵌图片 */
    public boolean extractImages() {
        return images != null && options.isExtractImages();
    }

    /** 源内容 SHA-256 */
    public String fileHash() {
        return fileHash;
    }

    public long fileSize() {
        return fileSize;
    }

    /** 文件名 */
    public String fileName() {
        return source == null ? null : source.name();
    }

    /** 打开一次源文件流 */
    public InputStream openStream() throws IOException {
        return source.openStream();
    }

    /** 源字节 */
    public byte[] bytes() throws IOException {
        return source.bytes();
    }

    /**
     * 按探测字符集解码后的文本内容（供 markdown/plaintext 等文本类 Backend 使用）。
     */
    public String text() throws IOException {
        if (decodedText == null) {
            decodedText = TextDecoder.decode(bytes());
        }
        return decodedText;
    }

    /** 文本的独立流（POI/Tika 需要流式接口时用） */
    public InputStream textStream() throws IOException {
        return new ByteArrayInputStream(text().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 新建一个已填好来源元信息的中间格式文档；
     * {@code backendKey}/{@code backendVersion}/{@code parseMs} 由 converter 统一回填。
     */
    public QunxuDocument newDocument() {
        DocumentMeta meta = new DocumentMeta();
        meta.setSourceName(fileName());
        meta.setFileHash(fileHash);
        meta.setFileType(format == null ? "" : format.extension());
        meta.setMimeType(source == null ? null : source.contentType());
        meta.setFileSize(fileSize);
        return new QunxuDocument(meta);
    }
}

package tech.qunxu.module.document.support;

import tech.qunxu.module.document.api.DocumentFormat;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 格式探测：扩展名优先，扩展名缺失或无法判定时用 Tika 按 MIME 兜底。
 * <p>
 * 扩展名优先是刻意的：知识库文件名比内容嗅探更可信（把 pdf 改名成 .txt 的情况远少于
 * 「csv 里没有分隔符导致嗅探成 text/plain」）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class DocumentFormatDetector {

    private static final Logger log = LoggerFactory.getLogger(DocumentFormatDetector.class);

    /** Tika 的 detect 是线程安全的，一个实例足够复用 */
    private final Tika tika = new Tika();

    /** 仅按文件名判定（不读内容） */
    public DocumentFormat detect(String fileName) {
        return DocumentFormat.fromExtension(DocumentFormat.extensionOf(fileName));
    }

    /**
     * 按文件名 + 内容判定：扩展名能定就直接返回，否则用 Tika 嗅探 MIME 再映射。
     *
     * @param fileName 文件名（可为 null）
     * @param bytes    内容字节（可为 null，此时只按文件名判定）
     */
    public DocumentFormat detect(String fileName, byte[] bytes) {
        DocumentFormat byName = detect(fileName);
        if (byName != DocumentFormat.UNKNOWN) {
            return byName;
        }
        if (bytes == null || bytes.length == 0) {
            return DocumentFormat.UNKNOWN;
        }
        DocumentFormat byMime = fromMime(detectMimeType(fileName, bytes));
        if (byMime != DocumentFormat.UNKNOWN) {
            log.debug("扩展名缺失，按 MIME 判定格式：{} -> {}", fileName, byMime);
        }
        return byMime;
    }

    /** 探测 MIME（失败返回 null，不影响转换） */
    public String detectMimeType(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return tika.detect(is, fileName);
        } catch (IOException | RuntimeException e) {
            log.warn("MIME 探测失败：{}，{}", fileName, e.getMessage());
            return null;
        }
    }

    /** MIME → 本模块格式 */
    public DocumentFormat fromMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return DocumentFormat.UNKNOWN;
        }
        String mime = mimeType.trim().toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) {
            return DocumentFormat.IMAGE;
        }
        if (mime.contains("wordprocessingml") || mime.equals("application/msword")) {
            return mime.contains("wordprocessingml") ? DocumentFormat.DOCX : DocumentFormat.DOC;
        }
        if (mime.contains("presentationml") || mime.contains("powerpoint")) {
            return mime.contains("presentationml") ? DocumentFormat.PPTX : DocumentFormat.PPT;
        }
        if (mime.contains("spreadsheetml") || mime.contains("ms-excel")) {
            return mime.contains("spreadsheetml") ? DocumentFormat.XLSX : DocumentFormat.XLS;
        }
        if (mime.equals("application/pdf")) {
            return DocumentFormat.PDF;
        }
        if (mime.equals("text/csv")) {
            return DocumentFormat.CSV;
        }
        if (mime.equals("text/markdown")) {
            return DocumentFormat.MARKDOWN;
        }
        if (mime.equals("text/html")) {
            return DocumentFormat.HTML;
        }
        if (mime.equals("application/rtf")) {
            return DocumentFormat.RTF;
        }
        if (mime.equals("application/epub+zip")) {
            return DocumentFormat.EPUB;
        }
        if (mime.startsWith("application/vnd.oasis.opendocument")) {
            return DocumentFormat.ODT;
        }
        if (mime.equals("application/json") || mime.equals("text/xml") || mime.startsWith("application/xml")) {
            return DocumentFormat.STRUCTURED;
        }
        if (mime.startsWith("text/")) {
            return DocumentFormat.TEXT;
        }
        return DocumentFormat.UNKNOWN;
    }
}

package tech.qunxu.module.document.api;

import java.util.Locale;

/**
 * 文档格式（按扩展名归类，决定进哪个 DocumentBackend）。
 * <p>
 * 只列本项目关心的格式：同一格式下不同实现（如 doc 与 docx）由不同 Backend 承担，
 * 未列出的格式统一落 {@link #UNKNOWN} 并交给 Tika 兜底解析。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public enum DocumentFormat {

    /** Word OOXML */
    DOCX("docx"),
    /** Word 97-2003 二进制（本轮走 Tika 兜底） */
    DOC("doc"),
    /** OOXML 演示文稿 */
    PPTX("pptx"),
    /** PowerPoint 97-2003 二进制 */
    PPT("ppt"),
    /** OOXML 工作簿 */
    XLSX("xlsx"),
    /** Excel 97-2003 */
    XLS("xls"),
    /** 逗号分隔表 */
    CSV("csv"),
    /** Markdown */
    MARKDOWN("md", "markdown", "mdown", "mkd"),
    /** 纯文本 */
    TEXT("txt", "log"),
    /** PDF */
    PDF("pdf"),
    /** 富文本 */
    RTF("rtf"),
    /** 网页 */
    HTML("html", "htm"),
    /** OpenDocument 系列 */
    ODT("odt", "ods", "odp"),
    /** 电子书 */
    EPUB("epub"),
    /** 图片（本轮仅留扩展接口，不解析） */
    IMAGE("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff"),
    /** JSON / XML 等结构化文本 */
    STRUCTURED("json", "xml", "yaml", "yml"),
    /** 未识别 */
    UNKNOWN("");

    private final String[] extensions;

    DocumentFormat(String... extensions) {
        this.extensions = extensions;
    }

    /** 主扩展名（小写，不含点） */
    public String extension() {
        return extensions.length == 0 ? "" : extensions[0];
    }

    /** 是否表格类格式（决定分块时是否走行分块策略） */
    public boolean isTabular() {
        return this == XLSX || this == XLS || this == CSV;
    }

    /** 是否图片格式 */
    public boolean isImage() {
        return this == IMAGE;
    }

    /**
     * 按扩展名判定格式。
     *
     * @param extension 扩展名，可带点，大小写不敏感
     * @return 匹配的格式，未匹配为 {@link #UNKNOWN}
     */
    public static DocumentFormat fromExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return UNKNOWN;
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        for (DocumentFormat format : values()) {
            for (String candidate : format.extensions) {
                if (candidate.equals(normalized)) {
                    return format;
                }
            }
        }
        return UNKNOWN;
    }

    /** 从文件名取扩展名（无扩展名返回空串） */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName;
        int query = name.indexOf('?');
        if (query > 0) {
            // URL 形态的源：先剥离查询参数再取后缀
            name = name.substring(0, query);
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}

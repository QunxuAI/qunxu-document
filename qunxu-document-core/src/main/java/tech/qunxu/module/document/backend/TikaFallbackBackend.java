package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.DocumentMeta;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tika 兜底解析器：把「没有专用 Backend 或专用 Backend 失败」的格式统一抽成纯文本，按空行切段落。
 * <p>
 * 这是改造前知识库导入的唯一解析路径（{@code new Tika().parseToString()} + 空行分段），
 * 保留它有两个目的：
 * <ol>
 *   <li>零回归 —— doc/ppt/rtf/html/odt/epub 等格式的行为与改造前逐字一致；</li>
 *   <li>逃生舱 —— 专用 Backend（POI/PDFBox）抛异常时由 {@code DocumentConverter} 降级到这里，
 *       导入不会因为某个格式的解析器出问题而整体失败。</li>
 * </ol>
 * 图片格式<b>不</b>在此处理：Tika 对图片只能给出尺寸元数据，产不出正文，
 * 让它兜底只会得到「解析成功但内容为空」的假象 —— 图片走 {@code ImageDocumentBackend} 扩展位。
 * <p>
 * 注意：{@code tika-parsers-standard-package} 必须在 classpath，否则 Tika 恒返回空串。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class TikaFallbackBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(TikaFallbackBackend.class);

    /** 与改造前一致：Tika 默认截断阈值就是 10 万字符 */
    private static final int DEFAULT_MAX_CHARS = 100 * 1000;

    /** Tika 的 parse 线程安全（不碰 setter），一个实例复用避免每篇重建解析器链 */
    private final Tika tika = new Tika();

    @Override
    public String key() {
        return "tika-fallback";
    }

    @Override
    public String label() {
        return "Tika 兜底";
    }

    @Override
    public int order() {
        return 900;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format != DocumentFormat.IMAGE;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        ConvertOptions options = context.options();
        QunxuDocument document = context.newDocument();
        int maxChars = options.intAttribute("maxChars", DEFAULT_MAX_CHARS);
        String text;
        Metadata metadata = new Metadata();
        if (context.fileName() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, context.fileName());
        }
        try (InputStream is = context.openStream()) {
            text = tika.parseToString(is, metadata, maxChars);
        } catch (TikaException | RuntimeException e) {
            log.warn("Tika 兜底解析失败：{}，{}", context.fileName(), e.getMessage());
            throw e;
        }
        DocumentMeta meta = document.getMeta();
        String mime = metadata.get(Metadata.CONTENT_TYPE);
        if (meta.getMimeType() == null || meta.getMimeType().isBlank()) {
            meta.setMimeType(mime);
        }
        if (text == null || text.isBlank()) {
            document.warn("Tika 未抽取到文本内容");
            return document;
        }
        List<String> paragraphs = splitParagraphs(text);
        if (paragraphs.isEmpty()) {
            document.warn("Tika 抽取的文本没有有效段落");
        }
        for (String paragraph : paragraphs) {
            document.append(NodeItem.paragraph(paragraph));
        }
        if (text.length() >= maxChars) {
            document.warn("文本超过 " + maxChars + " 字符，已被 Tika 截断");
        }
        return document;
    }

    /**
     * 空行分段，段内换行压成空格 —— 与改造前 {@code DocumentServiceImpl} 的切分逻辑一致。
     * <p>
     * 单独抽成方法是为了让 {@code FlatChunker} 之外也能复用同一套规则（如自检工具对比新旧结果）。
     */
    public static List<String> splitParagraphs(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String block : normalized.split("\n{2,}")) {
            String paragraph = block.replaceAll("\\s*\n\\s*", " ").trim();
            if (!paragraph.isEmpty()) {
                result.add(paragraph);
            }
        }
        return result;
    }
}

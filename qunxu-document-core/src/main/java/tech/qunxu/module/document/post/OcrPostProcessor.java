package tech.qunxu.module.document.post;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.model.ProvenanceItem;
import tech.qunxu.module.document.ocr.OcrRequest;
import tech.qunxu.module.document.ocr.PageOcr;
import tech.qunxu.module.document.ocr.PdfPageRasterizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描件 OCR 后处理（对应 docling {@code PdfPipelineOptions.do_ocr} 落在 pipeline 末端的那一段）。
 * <p>
 * 为什么放在后处理链而不是做成一个 Backend：
 * <ul>
 *   <li>「缺文字」与「用哪个解析器」无关 —— tika-pdf 抽不到字的页，opendataloader 同样抽不到；
 *       做成 Backend 就得每个 PDF 解析器各写一遍栅格化与插入逻辑；</li>
 *   <li>后处理发生在缓存写入之前，OCR 结果自然随中间格式落缓存 —— 而「救活一份扫描件」恰恰
 *       是最需要缓存的一次，否则每次重切分都重付一遍识别费。</li>
 * </ul>
 * <p>
 * 页级兜底策略（与 docling {@code force_full_page_ocr=false} 同语义）：只识别<b>可索引文本量为 0 的页</b>。
 * 判据用 {@link QunxuDocument#indexableTextLength()} 的口径按页拆分，因此整页水印、只有页眉页脚页码的页
 * 仍会被判为「缺文字」并送去识别 —— 这些正是扫描件的典型形态。
 * <p>
 * 成本闸门（三道，任一不满足就少做而不是全做）：{@code options.maxPages} 决定识别窗口上界
 * （解析都没解析的页不补，补了会插到文档末尾造成阅读顺序错位）、每页栅格字节上限、单篇页数上限。
 * 超页数上限时只处理前 N 页并写告警，让调用方看见「这份文档没 OCR 完」而不是静默交付半份正文。
 *
 * @author ethanwang
 * @since 2026-10-11
 */
public class OcrPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(OcrPostProcessor.class);

    /** Markdown 标题行（VLM 输出的 OCR 常带 #/##，还原成标题节点才进得了章节树） */
    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    /** 列表项行（与 tika-pdf 同一口径的轻量识别，只认常见项目符号与数字序号） */
    private static final Pattern MD_LIST = Pattern.compile("^\\s{0,3}([-*+]|\\d{1,3}[.)])\\s+(.*)$");
    /** 块内空行分隔（OCR 返回的段落边界） */
    private static final Pattern BLOCK_SPLIT = Pattern.compile("\\n\\s*\\n");

    private final PageOcr pageOcr;
    private final PdfPageRasterizer rasterizer;
    private final int defaultMaxPages;
    /** 单次后处理的时长预算（秒）：同步请求不能被一个卡死的识别服务按页数乘超时拖死 */
    private final long budgetSeconds;

    /**
     * @param pageOcr          识别引擎；{@code null} 表示宿主没接 OCR 能力，本处理器只告警不改内容
     * @param rasterizer       页面栅格化器（携带 DPI/字节等闸门配置）
     * @param defaultMaxPages  单篇默认识别页数上限（{@code options.ocrMaxPages} 为空时使用）
     * @param budgetSeconds    时长预算（≤ 0 不限）：超预算就停下剩下的页，已识别的正文照常保留
     */
    public OcrPostProcessor(PageOcr pageOcr, PdfPageRasterizer rasterizer, int defaultMaxPages, long budgetSeconds) {
        this.pageOcr = pageOcr;
        this.rasterizer = rasterizer == null ? new PdfPageRasterizer(null) : rasterizer;
        this.defaultMaxPages = defaultMaxPages > 0 ? defaultMaxPages : 20;
        this.budgetSeconds = budgetSeconds > 0 ? budgetSeconds : 0;
    }

    /** 不限时长预算的构造（自检与不需闸门的宿主用） */
    public OcrPostProcessor(PageOcr pageOcr, PdfPageRasterizer rasterizer, int defaultMaxPages) {
        this(pageOcr, rasterizer, defaultMaxPages, 0);
    }

    @Override
    public String key() {
        return "ocr";
    }

    /** 排在结构类后处理之前：水印清洗、标题推断一类的处理器都针对解析器原生产出，OCR 补的是缺口 */
    @Override
    public int order() {
        return 50;
    }

    /** 只对 PDF 且调用方显式请求了 OCR 的文档动手（识别是外部付费调用，绝不默认触发） */
    @Override
    public boolean supports(QunxuDocument document, ParseContext context) {
        return document != null
                && context != null && context.format() == DocumentFormat.PDF
                && context.options().isDoOcr();
    }

    @Override
    public QunxuDocument process(QunxuDocument document, ParseContext context) {
        ConvertOptions options = context.options();
        if (pageOcr == null) {
            document.warn("已请求扫描件 OCR，但未配置识别引擎（PageOcr），本次不补文字");
            return document;
        }
        byte[] bytes;
        try {
            bytes = context.bytes();
        } catch (Exception e) {
            document.warn("扫描件 OCR 跳过：源字节不可读（" + e.getMessage() + "）");
            return document;
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int limit = ocrWindow(document, pdf, options);
            List<Integer> pages = pickPages(document, options, limit);
            if (pages.isEmpty()) {
                return document;
            }
            int budget = options.getOcrMaxPages() == null ? defaultMaxPages : options.getOcrMaxPages();
            if (pages.size() > budget) {
                document.warn("需 OCR 的页有 " + pages.size() + " 页，按上限只识别前 " + budget
                        + " 页（剩余页面无文字，可调 ocrMaxPages）");
                pages = new ArrayList<>(pages.subList(0, budget));
            }
            log.info("扫描件 OCR 开始：引擎 {}，目标页 {} 页，选项 [{}]", pageOcr.key(), pages, options.describe());
            recognize(document, pdf, pages, options);
        } catch (Exception e) {
            log.warn("扫描件 OCR 失败，保留原解析结果：{}", e.getMessage());
            document.warn("扫描件 OCR 失败：" + e.getMessage());
        }
        return document;
    }

    // ==================== 选页 ====================

    /**
     * 识别窗口上界：取「PDFBox 页数」与「本次实际解析的页数」的较小值。
     * 后者来自 maxPages 或页表 —— 超出窗口的页没进中间格式，补出来的文字只能追加到末尾，
     * 会把阅读顺序与页码引用一起带偏，不如不做。
     */
    private int ocrWindow(QunxuDocument document, PDDocument pdf, ConvertOptions options) {
        int window = Math.max(1, pdf.getNumberOfPages());
        if (options.getMaxPages() != null && options.getMaxPages() > 0) {
            window = Math.min(window, options.getMaxPages());
        }
        int parsed = 0;
        for (NodeItem item : document.getItems()) {
            parsed = Math.max(parsed, item.pageIndex());
        }
        if (parsed > 0) {
            window = Math.min(window, parsed);
        }
        return Math.max(1, window);
    }

    /** 待识别页（1 基，升序）：整页模式全取，否则只取可索引文本量为 0 的页 */
    private List<Integer> pickPages(QunxuDocument document, ConvertOptions options, int window) {
        List<Integer> pages = new ArrayList<>();
        if (options.isOcrFullPage()) {
            for (int page = 1; page <= window; page++) {
                pages.add(page);
            }
            return pages;
        }
        long[] chars = charsPerPage(document, window);
        for (int page = 1; page <= window; page++) {
            if (chars[page] <= 0) {
                pages.add(page);
            }
        }
        return pages;
    }

    /**
     * 按页统计可索引字符数（下标 1~window）。
     * 口径必须与 {@link QunxuDocument#indexableTextLength()} 一致：图片节点存的是文件名、
     * 页眉页脚不进检索，把这些算进去会让扫描件每页都「有字」，一页都识别不了。
     */
    private long[] charsPerPage(QunxuDocument document, int window) {
        long[] chars = new long[window + 1];
        for (NodeItem item : document.getItems()) {
            DocumentKind kind = item.getKind();
            if (kind == DocumentKind.PICTURE || kind == DocumentKind.PAGE_HEADER
                    || kind == DocumentKind.PAGE_FOOTER) {
                continue;
            }
            long length = textLength(item);
            if (length <= 0) {
                continue;
            }
            int page = item.pageIndex();
            if (page >= 1 && page <= window) {
                chars[page] += length;
            }
        }
        return chars;
    }

    private long textLength(NodeItem item) {
        long total = item.getText() == null ? 0 : item.getText().length();
        if (item instanceof NodeItem.TableItem table) {
            for (NodeItem.TableCell cell : table.getCells()) {
                total += cell.getText() == null ? 0 : cell.getText().length();
            }
        }
        return total;
    }

    // ==================== 识别与插入 ====================

    private void recognize(QunxuDocument document, PDDocument pdf, List<Integer> pages, ConvertOptions options) {
        PDFRenderer renderer = rasterizer.rendererFor(pdf);
        String engine = pageOcr.key();
        // 引用串由宿主写在属性里，模块不解释内容（详见 PageOcr#ENGINE_REF_ATTRIBUTE）
        String engineRef = options.attribute(PageOcr.ENGINE_REF_ATTRIBUTE);
        long deadline = budgetSeconds > 0 ? System.currentTimeMillis() + budgetSeconds * 1000L : Long.MAX_VALUE;
        int ok = 0;
        int blank = 0;
        int failed = 0;
        int skipped = 0;
        long chars = 0;
        List<Integer> oversized = new ArrayList<>();
        for (int page : pages) {
            if (System.currentTimeMillis() > deadline) {
                // 超预算：剩下的页不追。一页卡死乘以页数上限会把同段的 HTTP 请求拖到网关断开，
                // 不如停下并把「哪几页没补」说清：已识别的正文仍然可用，下次重解析可继续
                skipped = pages.size() - ok - blank - failed - oversized.size();
                document.warn("OCR 超过时长预算 " + budgetSeconds + "s，剩余 " + Math.max(1, skipped)
                        + " 页未识别（可调 ocr.timeout-seconds 或分批处理）");
                break;
            }
            PdfPageRasterizer.PageImage image;
            try {
                image = rasterizer.render(renderer, page - 1);
            } catch (Exception e) {
                failed++;
                log.warn("第 {} 页栅格化失败：{}", page, e.getMessage());
                continue;
            }
            if (image == null) {
                oversized.add(page);
                continue;
            }
            try {
                String text = pageOcr.recognize(new OcrRequest(
                        image.bytes(), image.mimeType(), page, options.getLanguage(), engineRef));
                if (text == null || text.isBlank()) {
                    blank++;
                    continue;
                }
                int inserted = insertPageText(document, page, text);
                if (inserted == 0) {
                    blank++;
                    continue;
                }
                ok++;
                chars += text.length();
            } catch (Exception e) {
                failed++;
                log.warn("第 {} 页 OCR 识别失败（引擎 {}）：{}", page, engine, e.getMessage());
            }
        }
        log.info("扫描件 OCR 结束：识别出文字 {} 页（{} 字符），空白 {} 页，失败 {} 页，超大跳过 {} 页",
                ok, chars, blank, failed, oversized.size());
        // 逐页告警合并成一条：一页一条会把几十条同样的话写进文档，而调用方关心的是「哪几页没补上」
        if (!oversized.isEmpty()) {
            document.warn("OCR：第 " + summarizePages(oversized) + " 页栅格图超过字节上限，已跳过识别");
        }
        if (failed > 0) {
            document.warn("OCR：" + pages.size() + " 页中 " + failed + " 页识别失败（引擎 " + engine + "）");
        }
        if (ok == 0 && blank == 0 && failed == 0 && oversized.isEmpty()) {
            document.warn("OCR 未补出任何文字（引擎 " + engine + "）");
        }
    }

    /** 页号压缩成可读串（连续段用短横线，如 {@code 1-3, 7}），避免几十页时报得冗长 */
    private String summarizePages(List<Integer> pages) {
        List<String> parts = new ArrayList<>();
        int start = -1;
        int previous = -2;
        for (Integer page : pages) {
            int value = page;
            if (value == previous + 1) {
                previous = value;
                continue;
            }
            if (start >= 0) {
                parts.add(start == previous ? String.valueOf(start) : start + "-" + previous);
            }
            start = value;
            previous = value;
        }
        if (start >= 0) {
            parts.add(start == previous ? String.valueOf(start) : start + "-" + previous);
        }
        return String.join(", ", parts);
    }

    /**
     * 把一页识别结果切成节点，插回该页末尾（页内按 OCR 原文顺序，页间由 {@code indexOfPageEnd} 保证）。
     *
     * @return 实际插入的节点数
     */
    private int insertPageText(QunxuDocument document, int page, String text) {
        List<NodeItem> items = new ArrayList<>();
        for (String block : BLOCK_SPLIT.split(normalize(text))) {
            if (block == null || block.isBlank()) {
                continue;
            }
            for (String paragraph : splitParagraphs(block)) {
                NodeItem item = toNode(paragraph);
                if (item != null) {
                    item.prov(ProvenanceItem.ofPage(page));
                    items.add(item);
                }
            }
        }
        return document.insertAt(document.indexOfPageEnd(page), items);
    }

    /** 栅格图渲染尺寸与页面对应，OCR 出的就是整页内容，页面上不该再有未识别的空洞 */
    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 块内切段：Markdown 标题/列表行自成一段，其余连续行合并为一个段落（折行补空格的规则同 tika-pdf）。
     * VLM 常把一行正文拆成多个物理行返回，整块当一段会丢版面；完全按行切又会把句子切碎。
     */
    private List<String> splitParagraphs(String block) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String raw : block.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (MD_HEADING.matcher(line).matches() || MD_LIST.matcher(line).matches()) {
                flush(result, buffer);
                result.add(line);
                continue;
            }
            if (buffer.length() > 0 && needsSpace(buffer.charAt(buffer.length() - 1), line.charAt(0))) {
                buffer.append(' ');
            }
            buffer.append(line);
        }
        flush(result, buffer);
        return result;
    }

    private void flush(List<String> result, StringBuilder buffer) {
        if (buffer.length() > 0) {
            result.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    /** 一段 OCR 文本 → 节点（Markdown 标记还原成语义角色，供标题面包屑与分块使用） */
    private NodeItem toNode(String paragraph) {
        Matcher heading = MD_HEADING.matcher(paragraph);
        if (heading.matches()) {
            String content = heading.group(2).strip();
            return content.isEmpty() ? null
                    : new NodeItem.HeadingItem(heading.group(1).length(), content);
        }
        Matcher list = MD_LIST.matcher(paragraph);
        if (list.matches()) {
            String content = list.group(2).strip();
            if (content.isEmpty()) {
                return null;
            }
            String marker = list.group(1).startsWith("-") || list.group(1).equals("*")
                    || list.group(1).equals("+") ? "-" : "1.";
            return new NodeItem.ListItem(content, marker);
        }
        String plain = paragraph.strip();
        return plain.isEmpty() ? null : NodeItem.paragraph(plain);
    }

    /** CJK 折行直接拼接，英法德等语言需要补空格（与 {@code PdfTikaBackend} 同一规则） */
    private boolean needsSpace(char previous, char next) {
        return !isCjk(previous) && !isCjk(next);
    }

    private boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }
}

package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.image.ImageExtractor;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.model.ProvenanceItem;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 解析器：PDFBox 逐页取文本（带行级字号，用于标题层级兜底）+ 内嵌图片抽取。
 * <p>
 * 为什么用 PDFBox 而不是 Tika 一把梭：缓存与引用面板需要 {@code prov.pageIndex}，
 * 而 Tika 的输出会丢掉分页边界；逐页 {@code PDFTextStripper} 天然带页码，
 * 又能顺手拿到 MediaBox 尺寸与 XObject 图片。Tika 只负责 MIME 探测与 {@code tika-fallback}。
 * <p>
 * 版面能力边界：只给页码，不给元素级 bbox，不做阅读顺序重建，也不识别表格线 ——
 * 这些由 {@link OpenDataLoaderPdfBackend}（order 10）接管，本类承担同格式降级目标：
 * 结构化解析器报错、或它抽出的文本量明显少于页面文本时，PDF 仍旧有这一条不依赖外部构件的路可走。
 * <p>
 * 标题/段落/列表靠字号与行距启发式，在带水印、多栏、表格密集的 PDF 上会刷出远比真实结构多的标题
 * （真实业务样本：结构化 12 个标题 vs 本类 90 个），因此它只当兜底用，不当质量基准。
 * <ul>
 *   <li>标题：行字号相对正文基准字号的倍数（阈值与 {@code PoiHeadingStyles} 一致）+ 短行 + 非句末标点；</li>
 *   <li>段落：按行垂直间距与标题/列表边界切分，PDF 里被折行的句子重新拼成一段；</li>
 *   <li>页眉页脚：在多页重复出现的顶/底带文本，标成 {@code PAGE_HEADER}/{@code PAGE_FOOTER}（导出不渲染）；</li>
 *   <li>图片：DCT/JPX 直接取原始字节（不重编码，保真且省 CPU），其余渲染成 PNG；smask/模板图跳过。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class PdfTikaBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(PdfTikaBackend.class);

    /** 列表项起始符号（项目符号或序号） */
    private static final Pattern BULLET = Pattern.compile(
            "^([\\u2022\\u00b7\\u25aa\\u25a0\\u25cb\\u25cf\\u2023\\u2043\\u30fb\\uFF65*\\-\\u2013\\u2014]|\\d{1,3}[.)\\uFF0E]|\\(\\d{1,3}\\)|[\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u4e03\\u516b\\u4e5d\\u5341][.、])\\s*");

    /** 句末标点：以这些字符结尾的行更可能是段落结束而非折行 */
    private static final String SENTENCE_ENDINGS = "。！？；!?.;:：”’\"')";

    /** 顶/底页眉页脚带占页高比例 */
    private static final float FURNITURE_BAND = 0.12f;

    @Override
    public String key() {
        return "tika-pdf";
    }

    @Override
    public String label() {
        return "PDF(PDFBox)";
    }

    @Override
    public int order() {
        return 20;
    }

    /**
     * v2：修正文档主标题判定。原先「首个一级标题之外的所有一级标题都算 TITLE」（一份 16 页的
     * 真实文档刷出 58 个 TITLE），而 {@link DocumentKind#TITLE} 的契约是一个文档至多一个，
     * 导出的 H1 与分块面包屑都会跟着失真。<br>
     * v3：水印预剔除不再只服结构化解析器。本解析器按行剔水印，而真实企业样本里的水印（画在共享
     * Form 里由 {@code cm} 斜排）会被抽取器拆成散字贴进正文行，整行比对根本拦不住（实测 8 行残留）；
     * 改为拿 {@link PdfWatermarkFilter} 抹掉水印绘制后的字节重解，抽取阶段就看不到水印。
     * 两次都改了抽取语义，旧缓存必须自然失效。
     */
    @Override
    public int version() {
        return 3;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PDF;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        QunxuDocument document = context.newDocument();
        ConvertOptions options = context.options();
        byte[] bytes = context.bytes();
        // 页数要先算得出才能定水印扫描的页限额，所以先按原字节开一次；命中水印就换改写后的字节重开
        PDDocument pdf = Loader.loadPDF(bytes);
        try {
            int totalPages = pdf.getNumberOfPages();
            int limit = totalPages;
            if (options.getMaxPages() != null && options.getMaxPages() > 0) {
                limit = Math.min(totalPages, options.getMaxPages());
                if (limit < totalPages) {
                    document.warn("PDF 共 " + totalPages + " 页，按 maxPages 只解析前 " + limit + " 页");
                }
            }
            document.getMeta().setPageCount(totalPages);

            // 水印按绘制状态前置识别，留到标题推断里再拦会把层级带偏（与 opendataloader 同一口径）
            PdfWatermarkFilter.Result watermark = PdfWatermarkFilter.scan(bytes, limit);
            if (watermark.stripped() != null) {
                // 只删整行不够：企业样本里的水印被拆成散字贴在正文行里（实测），得从抽取源头就拿掉水印字节
                pdf.close();
                pdf = Loader.loadPDF(watermark.parseBytes(bytes));
            }
            List<List<PdfLine>> pageLines = new ArrayList<>();
            int watermarkDropped = 0;
            for (int page = 1; page <= limit; page++) {
                if (!options.pageAllowed(page)) {
                    break;
                }
                PDPage pdPage = safeGetPage(pdf, page - 1);
                float pageWidth = 0;
                float pageHeight = 0;
                if (pdPage != null) {
                    PDRectangle box = mediaBox(pdPage);
                    pageWidth = box == null ? 0 : box.getWidth();
                    pageHeight = box == null ? 0 : box.getHeight();
                }
                document.addPage(page, pageWidth, pageHeight);
                List<PdfLine> lines = extractLines(pdf, page);
                int beforeFilter = lines.size();
                lines.removeIf(line -> PdfWatermarkFilter.isHidden(watermark, line.text));
                watermarkDropped += beforeFilter - lines.size();
                for (PdfLine line : lines) {
                    line.pageHeight = pageHeight;
                }
                pageLines.add(lines);
            }
            appendPages(document, pageLines, context);
            if (watermarkDropped > 0) {
                log.info("tika-pdf 剔除 {} 行水印文字（按内容流绘制状态判定，不计入告警）", watermarkDropped);
            }
            if (context.extractImages()) {
                extractPictures(document, pdf, limit, context);
            }
            if (!document.hasIndexableText()) {
                // 按可索引文本量判，不能按 isEmpty()：扫描件整页位图会产出 PICTURE 节点，非空但无文字
                document.warn("PDF 无文本层（疑似扫描件），未抽到任何正文文字，需 OCR 解析器");
            }
        } finally {
            pdf.close();
        }
        return document;
    }

    // ==================== 文本 ====================

    /** 取单页文本行（含行字号与纵向位置） */
    private List<PdfLine> extractLines(PDDocument pdf, int page) throws IOException {
        LineStripper stripper = new LineStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        try {
            stripper.getText(pdf);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页文本抽取失败：{}", page, e.getMessage());
        }
        return stripper.lines();
    }

    /**
     * 把行拼成段落/标题/列表：先算全文档正文基准字号，再逐页扫描，
     * 最后统一识别跨页重复的页眉页脚（需要在拼块之前知道每行的带区归属）。
     */
    private void appendPages(QunxuDocument document, List<List<PdfLine>> pageLines, ParseContext context) {
        float bodySize = bodyFontSize(pageLines);
        boolean titleEmitted = false;
        Map<String, Integer> furnitureTop = new HashMap<>();
        Map<String, Integer> furnitureBottom = new HashMap<>();
        collectFurniture(pageLines, furnitureTop, furnitureBottom);

        for (int p = 0; p < pageLines.size(); p++) {
            int pageNumber = p + 1;
            List<PdfLine> lines = pageLines.get(p);
            StringBuilder paragraph = new StringBuilder();
            PdfLine lastParagraphLine = null;
            for (PdfLine line : lines) {
                String text = line.text.trim();
                if (text.isEmpty()) {
                    continue;
                }
                String furniture = furnitureKind(line, furnitureTop, furnitureBottom, pageLines.size());
                if (furniture != null) {
                    flushParagraph(document, paragraph, pageNumber);
                    lastParagraphLine = null;
                    DocumentKind kind = "header".equals(furniture)
                            ? DocumentKind.PAGE_HEADER : DocumentKind.PAGE_FOOTER;
                    document.append(new NodeItem.TextItem(kind, text)).prov(ProvenanceItem.ofPage(pageNumber));
                    continue;
                }
                Integer level = headingLevel(line, bodySize);
                if (level != null) {
                    flushParagraph(document, paragraph, pageNumber);
                    lastParagraphLine = null;
                    // 主标题只给全文第一个一级标题（与结构化解析器同一口径）
                    DocumentKind kind = level == 1 && !titleEmitted
                            ? DocumentKind.TITLE : DocumentKind.SECTION_HEADER;
                    titleEmitted = true;
                    document.append(new NodeItem.HeadingItem(kind, level, text))
                            .prov(ProvenanceItem.ofPage(pageNumber));
                    continue;
                }
                Matcher bullet = BULLET.matcher(text);
                if (bullet.find() && text.length() > bullet.end()) {
                    flushParagraph(document, paragraph, pageNumber);
                    lastParagraphLine = null;
                    String marker = isNumericMarker(bullet.group(1)) ? "1." : "-";
                    document.append(new NodeItem.ListItem(text.substring(bullet.end()).trim(), marker))
                            .prov(ProvenanceItem.ofPage(pageNumber));
                    continue;
                }
                // 折行归并：与上一行同段需满足「字号接近 + 垂直间距小」
                if (paragraph.length() > 0 && !continuesParagraph(lastParagraphLine, line, bodySize)) {
                    flushParagraph(document, paragraph, pageNumber);
                }
                if (paragraph.length() > 0) {
                    // CJK 折行不该插空格，英法德等语言需要
                    if (needsSpace(paragraph.charAt(paragraph.length() - 1), text.charAt(0))) {
                        paragraph.append(' ');
                    }
                }
                paragraph.append(text);
                lastParagraphLine = line;
            }
            flushParagraph(document, paragraph, pageNumber);
        }
    }

    private void flushParagraph(QunxuDocument document, StringBuilder paragraph, int pageNumber) {
        if (paragraph.length() == 0) {
            return;
        }
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        document.append(NodeItem.paragraph(text)).prov(ProvenanceItem.ofPage(pageNumber));
    }

    /**
     * 正文基准字号：按字符数加权的众数（只统计足够长的行，避免标题把基准拉高）。
     */
    private float bodyFontSize(List<List<PdfLine>> pageLines) {
        Map<Float, Integer> weights = new HashMap<>();
        for (List<PdfLine> lines : pageLines) {
            for (PdfLine line : lines) {
                if (line.text.length() < 10 || line.fontSize <= 0) {
                    continue;
                }
                float key = Math.round(line.fontSize * 2) / 2f;
                weights.merge(key, line.text.length(), Integer::sum);
            }
        }
        float best = 0;
        int bestWeight = 0;
        for (Map.Entry<Float, Integer> entry : weights.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
        }
        if (best <= 0) {
            // 全是短行（表格型 PDF / 目录页）时退化为最小字号
            float min = Float.MAX_VALUE;
            for (List<PdfLine> lines : pageLines) {
                for (PdfLine line : lines) {
                    if (line.fontSize > 0) {
                        min = Math.min(min, line.fontSize);
                    }
                }
            }
            best = min == Float.MAX_VALUE ? 0 : min;
        }
        return best;
    }

    /**
     * 标题判定：字号明显大于正文、行短、且不以句末标点收尾。
     * <p>
     * 阈值与 {@code PoiHeadingStyles} 的字号兜底保持一致（1.7/1.4/1.15），
     * 让同一份内容从 Word 导出成 PDF 后层级不至于一档之差。
     */
    private Integer headingLevel(PdfLine line, float bodySize) {
        if (bodySize <= 0 || line.fontSize <= 0) {
            return null;
        }
        String text = line.text.trim();
        if (text.length() > 80 || text.length() < 2) {
            return null;
        }
        if (text.indexOf('\n') >= 0) {
            return null;
        }
        String last = text.substring(text.length() - 1);
        if (SENTENCE_ENDINGS.contains(last)) {
            return null;
        }
        float ratio = line.fontSize / bodySize;
        if (ratio >= 1.7f) {
            return 1;
        }
        if (ratio >= 1.4f) {
            return 2;
        }
        if (ratio >= 1.15f) {
            // 三级标题需要额外证据：短行且整页行数不多时更可靠
            return text.length() <= 50 ? 3 : null;
        }
        return null;
    }

    /** 是否与上一行同属一段 */
    private boolean continuesParagraph(PdfLine previous, PdfLine current, float bodySize) {
        if (previous == null) {
            return true;
        }
        String previousText = previous.text.trim();
        String last = previousText.isEmpty() ? "" : previousText.substring(previousText.length() - 1);
        if (!SENTENCE_ENDINGS.contains(last) && previousText.length() >= 24) {
            // 上一行没写完（未以句末标点收尾且不太短）：默认是折行
            return near(previous, current, bodySize);
        }
        return false;
    }

    private boolean near(PdfLine previous, PdfLine current, float bodySize) {
        float reference = bodySize > 0 ? bodySize : Math.max(previous.fontSize, current.fontSize);
        if (reference <= 0) {
            return true;
        }
        float gap = Math.abs(current.y - previous.y);
        return gap <= reference * 1.8f;
    }

    /** CJK 折行直接拼接，西文需要补空格 */
    private boolean needsSpace(char previous, char next) {
        return !isCjk(previous) && !isCjk(next);
    }

    private boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }

    private boolean isNumericMarker(String marker) {
        String value = marker.trim();
        return !value.isEmpty() && (Character.isDigit(value.charAt(0))
                || "一二三四五六七八九十".indexOf(value.charAt(0)) >= 0);
    }

    // ==================== 页眉页脚 ====================

    /** 统计每页首/末行文本的重复次数（跨页重复即为页面装饰） */
    private void collectFurniture(List<List<PdfLine>> pageLines, Map<String, Integer> top,
                                  Map<String, Integer> bottom) {
        for (List<PdfLine> lines : pageLines) {
            PdfLine first = inBand(lines, true);
            PdfLine last = inBand(lines, false);
            if (first != null) {
                top.merge(normalize(first.text), 1, Integer::sum);
            }
            if (last != null) {
                bottom.merge(normalize(last.text), 1, Integer::sum);
            }
        }
    }

    private PdfLine inBand(List<PdfLine> lines, boolean head) {
        if (lines.isEmpty()) {
            return null;
        }
        PdfLine candidate = head ? lines.get(0) : lines.get(lines.size() - 1);
        String text = candidate.text.trim();
        if (text.isEmpty() || text.length() > 60 || candidate.pageHeight <= 0) {
            return null;
        }
        float ratio = candidate.y / candidate.pageHeight;
        boolean inBand = head ? ratio <= FURNITURE_BAND : ratio >= 1 - FURNITURE_BAND;
        return inBand ? candidate : null;
    }

    /** 返回 "header"/"footer"，非装饰返回 null */
    private String furnitureKind(PdfLine line, Map<String, Integer> top, Map<String, Integer> bottom,
                                 int pageCount) {
        if (line.text == null || pageCount < 3) {
            return null;
        }
        // 单页至少一半重复，且至少 3 页，才认定为页眉页脚（避免误杀章节首行的引言）
        int threshold = Math.max(3, pageCount / 2);
        String key = normalize(line.text);
        if (line.pageHeight > 0) {
            float ratio = line.y / line.pageHeight;
            if (ratio <= FURNITURE_BAND && top.getOrDefault(key, 0) >= threshold) {
                return "header";
            }
            if (ratio >= 1 - FURNITURE_BAND && bottom.getOrDefault(key, 0) >= threshold) {
                return "footer";
            }
        }
        return null;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                // 页码里的数字与罗马数字变化不参与相等判定
                .replaceAll("[0-9ivxlcdm]+", "#");
    }

    // ==================== 图片 ====================

    private void extractPictures(QunxuDocument document, PDDocument pdf, int limit, ParseContext context) {
        ImageExtractor extractor = context.images();
        if (extractor == null) {
            return;
        }
        int pages = Math.min(limit, pdf.getNumberOfPages());
        int storedBefore = document.countOf(DocumentKind.PICTURE);
        for (int page = 1; page <= pages; page++) {
            PDPage pdPage = safeGetPage(pdf, page - 1);
            if (pdPage == null) {
                continue;
            }
            PDResources resources = pdPage.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : safeXObjectNames(resources)) {
                PDImageXObject image = safeImage(resources, name);
                if (image == null) {
                    continue;
                }
                ImageRef ref = savePicture(image, name, page, extractor);
                if (ref != null) {
                    document.append(NodeItem.picture(ref, ref.fileName()))
                            .prov(ProvenanceItem.ofPage(page));
                }
            }
        }
        if (document.countOf(DocumentKind.PICTURE) == storedBefore && extractor.attempted() > 0) {
            document.warn("PDF 内嵌图片均未通过过滤（尺寸过小或超出上限）");
        }
    }

    private ImageRef savePicture(PDImageXObject image, COSName name, int page, ImageExtractor extractor) {
        try {
            if (image.isStencil() || image.getMask() != null) {
                // 模板图/软遮罩不是独立可见图像，落地只会产出黑块
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            String suffix = image.getSuffix() == null ? "png" : image.getSuffix().toLowerCase();
            byte[] data;
            if ("jpg".equals(suffix) || "jpeg".equals(suffix) || "jp2".equals(suffix)) {
                // 有损压缩图直接取原始流：重编码会掉画质并白白消耗 CPU。
                // PDFBox 3 取消了 PDImageXObject.getCOSStream()，图像 XObject 的 COSObject 运行时即 COSStream
                COSStream stream = image.getCOSObject() instanceof COSStream cs ? cs : null;
                data = readAll(stream == null ? null : stream.createRawInputStream());
                if (data == null || data.length == 0) {
                    data = png(image.getImage());
                    suffix = "png";
                }
            } else {
                data = png(image.getImage());
                suffix = "png";
            }
            if (data == null || data.length == 0) {
                return null;
            }
            String hint = "p" + page + "-" + (name == null || name.getName() == null
                    ? "xobject" : name.getName().replaceAll("[^A-Za-z0-9_.-]", ""));
            return extractor.extract(data, hint + "." + suffix, mimeOf(suffix), width, height,
                    "pdf page " + page);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页图片抽取失败（{}）：{}", page, name, e.getMessage());
            return null;
        }
    }

    private byte[] png(BufferedImage image) throws IOException {
        if (image == null) {
            return null;
        }
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_INT_ARGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.min(8 * 1024 * 1024, Math.max(1024, image.getWidth() * image.getHeight())));
        if (!ImageIO.write(rgb, "png", out)) {
            return null;
        }
        return out.toByteArray();
    }

    private String mimeOf(String suffix) {
        return switch (suffix) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "jp2" -> "image/jp2";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/" + suffix;
        };
    }

    private byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        try (input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    // ==================== PDFBox 容错 ====================

    private PDPage safeGetPage(PDDocument pdf, int index) {
        try {
            return pdf.getPages().get(index);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页不可读：{}", index + 1, e.getMessage());
            return null;
        }
    }

    private PDRectangle mediaBox(PDPage page) {
        try {
            PDRectangle box = page.getMediaBox();
            return box == null ? page.getBBox() : box;
        } catch (Exception e) {
            return null;
        }
    }

    private Iterable<COSName> safeXObjectNames(PDResources resources) {
        try {
            Iterable<COSName> names = resources.getXObjectNames();
            return names == null ? List.of() : names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private PDImageXObject safeImage(PDResources resources, COSName name) {
        try {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                return image;
            }
        } catch (Exception e) {
            log.debug("PDF XObject {} 读取失败：{}", name, e.getMessage());
        }
        return null;
    }

    // ==================== 行抽取 ====================

    /** 单行文本 + 行内最大字号 + 纵向位置（自页顶向下，单位 pt） */
    private static final class PdfLine {
        private final String text;
        private final float fontSize;
        private final float y;
        private float pageHeight;

        private PdfLine(String text, float fontSize, float y) {
            this.text = text;
            this.fontSize = fontSize;
            this.y = y;
        }
    }

    /**
     * 行级抽取器：不写 output，只累积「一行文本 + 该行最大字号」。
     * <p>
     * PDFTextStripper 的行/段事件（{@code writeString(text, positions)} /
     * {@code writeLineSeparator()} / {@code endArticle()}）正好是需要的切分点，
     * 因此这里接管它们而不是拼接最终字符串 —— 拿不到字号的纯字符串导出无法判层级。
     */
    private static final class LineStripper extends PDFTextStripper {

        private final List<PdfLine> collected = new ArrayList<>();
        private final StringBuilder buffer = new StringBuilder();
        private float maxSize;
        private float topY = Float.MAX_VALUE;

        LineStripper() throws IOException {
            setSortByPosition(true);
        }

        List<PdfLine> lines() {
            flush();
            return collected;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isEmpty()) {
                return;
            }
            buffer.append(text);
            if (textPositions != null) {
                for (TextPosition position : textPositions) {
                    if (position == null) {
                        continue;
                    }
                    float size = position.getFontSizeInPt() > 0
                            ? position.getFontSizeInPt() : position.getFontSize();
                    if (size > maxSize) {
                        maxSize = size;
                    }
                    String unicode = position.getUnicode();
                    if (unicode != null && !unicode.isBlank() && position.getYDirAdj() < topY) {
                        topY = position.getYDirAdj();
                    }
                }
            }
        }

        @Override
        protected void writeString(String text) {
            if (text != null) {
                buffer.append(text);
            }
        }

        @Override
        protected void writeWordSeparator() {
            buffer.append(' ');
        }

        @Override
        protected void writeLineSeparator() {
            flush();
        }

        @Override
        protected void writeParagraphSeparator() {
            flush();
        }

        @Override
        protected void writeParagraphEnd() {
            flush();
        }

        @Override
        protected void endArticle() {
            flush();
        }

        private void flush() {
            String value = buffer.toString();
            if (!value.isBlank()) {
                collected.add(new PdfLine(value, maxSize, topY == Float.MAX_VALUE ? 0 : topY));
            }
            buffer.setLength(0);
            maxSize = 0;
            topY = Float.MAX_VALUE;
        }
    }
}

package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.image.ImageExtractor;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.model.ProvenanceItem;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;

/**
 * PowerPoint OOXML（pptx）解析器：每页幻灯片映射为一个「页」，页内形状按 z-order 保序抽取。
 * <p>
 * 这是新增格式里性价比最高的一类：pptx 的结构本来就是「页 → 形状 → 段落」，
 * 天然对应中间格式的 {@code prov.pageIndex + 标题树}，所以标题层级、表格、图片都能直接落位。
 * <ul>
 *   <li>title / centerTitle / subTitle 占位符 → {@code SECTION_HEADER(level=1)}；</li>
 *   <li>body 及其它文本形状 → 段落（{@code isBullet()} 的段落记为列表项，indentLevel 决定 marker 缩进）；</li>
 *   <li>{@code XSLFTable} → 表格（gridSpan/rowSpan 即合并，首行当表头）；</li>
 *   <li>{@code XSLFPictureShape} → 图片（走 {@link ImageExtractor}，外链图无数据则只留名字）；</li>
 *   <li>演讲备注（notes）→ 该页尾部的段落，以 {@code 备注：} 前缀区分正文。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class PowerPointDocumentBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(PowerPointDocumentBackend.class);

    @Override
    public String key() {
        return "poi-pptx";
    }

    @Override
    public String label() {
        return "PowerPoint(pptx)";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PPTX;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        ConvertOptions options = context.options();
        QunxuDocument document = context.newDocument();
        try (InputStream is = context.openStream(); XMLSlideShow ppt = new XMLSlideShow(is)) {
            List<XSLFSlide> slides = ppt.getSlides();
            document.getMeta().setPageCount(slides.size());
            for (int i = 0; i < slides.size(); i++) {
                int page = i + 1;
                if (!options.pageAllowed(page)) {
                    document.warn("按 maxPages=" + options.getMaxPages() + " 截断，仅解析前 " + (page - 1) + " 页");
                    break;
                }
                XSLFSlide slide = slides.get(i);
                document.addPage(page, 0, 0);
                for (XSLFShape shape : slide.getShapes()) {
                    appendShape(document, shape, page, context);
                }
                appendNotes(document, slide, page);
            }
            if (document.isEmpty()) {
                document.warn("pptx 未解析出文本内容（可能全是图片）");
            }
        }
        return document;
    }

    private void appendShape(QunxuDocument document, XSLFShape shape, int page, ParseContext context) {
        if (shape instanceof XSLFTable table) {
            NodeItem.TableItem item = toTable(table);
            document.append(item).prov(ProvenanceItem.ofPage(page));
            return;
        }
        if (shape instanceof XSLFPictureShape picture) {
            NodeItem.PictureItem item = toPicture(picture, context);
            if (item != null) {
                document.append(item).prov(ProvenanceItem.ofPage(page));
            }
            return;
        }
        if (!(shape instanceof XSLFTextShape textShape)) {
            return;
        }
        Placeholder placeholder = safePlaceholder(shape);
        List<XSLFTextParagraph> paragraphs = textShape.getTextParagraphs();
        if (paragraphs == null || paragraphs.isEmpty()) {
            String all = safeText(textShape);
            if (all != null && !all.isBlank()) {
                appendText(document, all.trim(), page, placeholder, false, 0);
            }
            return;
        }
        for (XSLFTextParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            appendText(document, text.trim(), page, placeholder, paragraph.isBullet(),
                    paragraph.getIndentLevel());
        }
    }

    private void appendText(QunxuDocument document, String text, int page, Placeholder placeholder,
                            boolean bullet, int indentLevel) {
        if (isFurniture(placeholder)) {
            // 页码/日期/页脚属页面家具，不进正文与导出
            document.append(new NodeItem.TextItem(DocumentKind.PAGE_FOOTER, text))
                    .prov(ProvenanceItem.ofPage(page));
            return;
        }
        if (isTitlePlaceholder(placeholder)) {
            document.append(new NodeItem.HeadingItem(DocumentKind.SECTION_HEADER, 1, text))
                    .prov(ProvenanceItem.ofPage(page));
            return;
        }
        if (bullet) {
            String indent = "  ".repeat(Math.min(3, Math.max(0, indentLevel)));
            document.append(new NodeItem.ListItem(indent + text, "-")).prov(ProvenanceItem.ofPage(page));
            return;
        }
        document.append(NodeItem.paragraph(text)).prov(ProvenanceItem.ofPage(page));
    }

    private boolean isTitlePlaceholder(Placeholder placeholder) {
        if (placeholder == null) {
            return false;
        }
        return switch (placeholder) {
            case TITLE, CENTERED_TITLE, SUBTITLE, VERTICAL_TEXT_TITLE -> true;
            default -> false;
        };
    }

    /** 页码、日期、页脚等自动占位符 */
    private boolean isFurniture(Placeholder placeholder) {
        if (placeholder == null) {
            return false;
        }
        return switch (placeholder) {
            case DATETIME, SLIDE_NUMBER, FOOTER, HEADER -> true;
            default -> false;
        };
    }

    private Placeholder safePlaceholder(XSLFShape shape) {
        try {
            return shape.isPlaceholder() ? shape.getPlaceholder() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeText(XSLFTextShape shape) {
        try {
            return shape.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void appendNotes(QunxuDocument document, XSLFSlide slide, int page) {
        try {
            if (slide.getNotes() == null) {
                return;
            }
            for (XSLFShape shape : slide.getNotes().getShapes()) {
                if (!(shape instanceof XSLFTextShape textShape)) {
                    continue;
                }
                String text = safeText(textShape);
                if (text == null || text.isBlank()) {
                    continue;
                }
                for (String line : text.split("\\r?\\n")) {
                    if (!line.isBlank()) {
                        document.append(NodeItem.paragraph("备注：" + line.trim()))
                                .prov(ProvenanceItem.ofPage(page));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("读取 pptx 备注失败（第 {} 页）：{}", page, e.getMessage());
        }
    }

    private NodeItem.PictureItem toPicture(XSLFPictureShape shape, ParseContext context) {
        String name = shape.getShapeName();
        if (context.images() == null || !context.extractImages()) {
            return null;
        }
        try {
            XSLFPictureData data = shape.getPictureData();
            if (data == null || data.getData() == null || data.getData().length == 0) {
                // 外链图片：包内没有字节，只保留名字
                return new NodeItem.PictureItem(null, name);
            }
            ImageRef ref = context.images().extract(data.getData(), data.getFileName(), data.getContentType(),
                    null, null, name);
            return new NodeItem.PictureItem(ref, ref == null ? name : ref.fileName());
        } catch (RuntimeException e) {
            log.debug("落地 pptx 图片失败：{}", e.getMessage());
            return new NodeItem.PictureItem(null, name);
        }
    }

    /** pptx 表格 → TableItem（gridSpan/rowSpan 保留，被合并覆盖的格子跳过） */
    private NodeItem.TableItem toTable(XSLFTable table) {
        NodeItem.TableItem item = new NodeItem.TableItem(null);
        int colCount = table.getNumberOfColumns();
        int dataRow = -1;
        boolean headerTaken = false;
        for (org.apache.poi.xslf.usermodel.XSLFTableRow row : table.getRows()) {
            dataRow++;
            int gridCol = 0;
            // 首行整体当表头（与 MsWordDocumentBackend 一致）；早先只把首个格子当表头，
            // 会把同一行剩下的格子挤进数据区且 rowIndex 落在 0，表格行列数与改造前对不上
            boolean headerRow = dataRow == 0;
            List<XSLFTableCell> cells = row.getCells();
            for (XSLFTableCell cell : cells) {
                int colSpan = Math.max(1, cell.getGridSpan());
                int rowSpan = Math.max(1, cell.getRowSpan());
                String raw = safeText(cell);
                String text = raw == null ? "" : raw.trim().replace('\n', ' ');
                if (cell.isMerged()) {
                    // 被前一个格子的 gridSpan/rowSpan 覆盖，不重复输出
                    gridCol += colSpan;
                    continue;
                }
                if (headerRow) {
                    item.getHeaders().add(text);
                    headerTaken = true;
                    gridCol += colSpan;
                    continue;
                }
                item.addCell(new NodeItem.TableCell(text, Math.max(0, dataRow - (headerTaken ? 1 : 0)),
                        gridCol, rowSpan, colSpan));
                gridCol += colSpan;
            }
            item.setColCount(Math.max(item.getColCount(), Math.max(colCount, gridCol)));
        }
        return item;
    }
}

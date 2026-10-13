package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.support.PoiHeadingStyles;
import tech.qunxu.module.document.support.Sha256Util;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word OOXML（docx）解析器：{@code XWPFDocument.getBodyElements()} 保序遍历。
 * <p>
 * 之所以按 bodyElements 而不是 {@code getParagraphs()/getTables()} 遍历：后者会把段落与表格拆成两组，
 * 文档顺序丢失，表格会全部堆到文末 —— 中间格式必须保序，否则导出与分块的上下文就错了。
 * <ul>
 *   <li>标题层级：{@link PoiHeadingStyles}（样式名 → outlineLvl → 字号兜底）；</li>
 *   <li>列表项：段落有 numID 即列表项，marker 按 numFmt 给 {@code -} 或 {@code 1.}；</li>
 *   <li>表格：{@code gridSpan} 横向合并、{@code vMerge} 纵向合并（按网格列位置近似配对，见方法注释）；</li>
 *   <li>图片：run 内联/锚定图按出现位置就地成块，正文无图时再补 {@code getAllPictures()}（多为页眉 logo）。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class MsWordDocumentBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(MsWordDocumentBackend.class);

    @Override
    public String key() {
        return "poi-docx";
    }

    @Override
    public String label() {
        return "Word(docx)";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.DOCX;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        QunxuDocument document = context.newDocument();
        try (InputStream is = context.openStream();
             XWPFDocument word = new XWPFDocument(is)) {

            double bodyFontSize = PoiHeadingStyles.bodyFontSize(word);
            Set<String> referenced = new HashSet<>();
            for (IBodyElement element : word.getBodyElements()) {
                BodyElementType type = element.getElementType();
                if (type == BodyElementType.PARAGRAPH && element instanceof XWPFParagraph paragraph) {
                    appendParagraph(document, paragraph, word, bodyFontSize, context, referenced);
                } else if (type == BodyElementType.TABLE && element instanceof XWPFTable table) {
                    appendTable(document, table, context, referenced);
                }
            }
            appendOrphanPictures(document, word, referenced, context);

            if (document.isEmpty()) {
                document.warn("docx 未解析出正文内容");
            }
        }
        return document;
    }

    // ==================== 段落 ====================

    private void appendParagraph(QunxuDocument document, XWPFParagraph paragraph,
                                 XWPFDocument word, double bodyFontSize,
                                 ParseContext context, Set<String> referenced) {
        String text = paragraph.getParagraphText();
        boolean blank = text == null || text.isBlank();

        // 1) 先落地该段落里的内嵌图（位置优先于文本，图片常独占一段）
        int picturesBefore = document.countOf(DocumentKind.PICTURE);
        for (XWPFPicture picture : collectPictures(paragraph)) {
            ImageRef ref = savePicture(picture, context, referenced);
            if (ref != null) {
                document.append(NodeItem.picture(ref, ref.fileName()));
            }
        }
        boolean addedPicture = document.countOf(DocumentKind.PICTURE) > picturesBefore;
        if (blank) {
            return;
        }
        // 2) 标题
        Integer level = PoiHeadingStyles.headingLevel(paragraph, safeStyles(word), bodyFontSize);
        if (level != null) {
            DocumentKind kind = isTitleStyle(paragraph) ? DocumentKind.TITLE : DocumentKind.SECTION_HEADER;
            document.append(new NodeItem.HeadingItem(kind, level, text.trim()));
            return;
        }
        // 3) 列表项
        String marker = listMarker(paragraph);
        if (marker != null) {
            document.append(new NodeItem.ListItem(text.trim(), marker));
            return;
        }
        // 4) 图片已单独成块时，剩余说明性文字按图注处理，避免与图片块重复
        if (addedPicture && text.trim().length() <= 60) {
            document.append(new NodeItem.TextItem(DocumentKind.CAPTION, text.trim()));
            return;
        }
        document.append(NodeItem.paragraph(text.trim()));
    }

    private List<XWPFPicture> collectPictures(XWPFParagraph paragraph) {
        List<XWPFPicture> pictures = new ArrayList<>();
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null) {
            return pictures;
        }
        for (XWPFRun run : runs) {
            try {
                List<XWPFPicture> embedded = run.getEmbeddedPictures();
                if (embedded != null) {
                    pictures.addAll(embedded);
                }
            } catch (RuntimeException e) {
                log.debug("读取 run 内嵌图片失败：{}", e.getMessage());
            }
        }
        return pictures;
    }

    /** 正文一张图都没有时，才把包内媒体当正文图片补上（否则多为页眉页脚 logo） */
    private void appendOrphanPictures(QunxuDocument document, XWPFDocument word,
                                      Set<String> referenced, ParseContext context) {
        if (context.images() == null || !context.extractImages() || document.countOf(DocumentKind.PICTURE) > 0) {
            return;
        }
        try {
            for (XWPFPictureData data : word.getAllPictures()) {
                if (data == null || data.getData() == null) {
                    continue;
                }
                String sha = Sha256Util.sha256(data.getData());
                if (referenced.contains(sha)) {
                    continue;
                }
                ImageRef ref = context.images().extract(data.getData(), data.getFileName(),
                        contentType(data), null, null, data.getFileName());
                if (ref != null) {
                    referenced.add(sha);
                    document.append(NodeItem.picture(ref, ref.fileName()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("扫描 docx 包内图片失败：{}", e.getMessage());
            document.warn("包内图片扫描失败：" + e.getMessage());
        }
    }

    private ImageRef savePicture(XWPFPicture picture, ParseContext context, Set<String> referenced) {
        if (picture == null || context.images() == null || !context.extractImages()) {
            return null;
        }
        try {
            XWPFPictureData data = picture.getPictureData();
            if (data == null || data.getData() == null) {
                return null;
            }
            String sha = Sha256Util.sha256(data.getData());
            ImageRef ref = context.images().extract(data.getData(), data.getFileName(), contentType(data),
                    null, null, data.getFileName());
            if (ref != null) {
                referenced.add(sha);
            }
            return ref;
        } catch (RuntimeException e) {
            log.debug("落地 docx 图片失败：{}", e.getMessage());
            return null;
        }
    }

    private String contentType(XWPFPictureData data) {
        try {
            return data.getPackagePart() == null ? null : data.getPackagePart().getContentType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private XWPFStyles safeStyles(XWPFDocument word) {
        try {
            return word.getStyles();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isTitleStyle(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return false;
        }
        String normalized = style.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("title") || normalized.contains("文档标题");
    }

    /** 列表 marker：无编号返回 null；十进制编号给 {@code 1.}，其余给 {@code -} */
    private String listMarker(XWPFParagraph paragraph) {
        BigInteger numId = paragraph.getNumID();
        if (numId == null) {
            return null;
        }
        String numFmt = paragraph.getNumFmt();
        if (numFmt == null) {
            return "-";
        }
        String fmt = numFmt.toLowerCase(Locale.ROOT);
        if (fmt.contains("none")) {
            return null;
        }
        if (fmt.contains("decimal") || fmt.contains("number")) {
            return "1.";
        }
        return "-";
    }

    // ==================== 表格 ====================

    /**
     * 表格：首行当表头，{@code gridSpan} 横向合并，{@code vMerge} 纵向合并。
     * <p>
     * 纵向合并只按网格列位置对齐（OOXML 不存单元格绝对坐标，一行里多个连续
     * {@code continue} 时仅能近似配对）；表格单元格里内嵌的图先收集、在整张表之后成块，
     * 避免把图插进表格中间把表拆成两段。
     */
    private void appendTable(QunxuDocument document, XWPFTable table, ParseContext context,
                             Set<String> referenced) {
        NodeItem.TableItem item = new NodeItem.TableItem(null);
        List<ImageRef> cellPictures = new ArrayList<>();
        // 纵向合并的悬挂列：网格列 -> 本行仍需延长的 origin cell（下一行只跟上一行比，所以逐行重置）
        Map<Integer, NodeItem.TableCell> pendingVertical = new HashMap<>();
        List<XWPFTableRow> rows = table.getRows();
        int dataRow = -1;
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            dataRow++;
            boolean headerRow = dataRow == 0;
            int gridCol = 0;
            Map<Integer, NodeItem.TableCell> nextPending = new HashMap<>();
            for (XWPFTableCell cell : cells) {
                int colSpan = gridSpan(cell);
                MergeType vMerge = verticalMerge(cell);
                if (vMerge == MergeType.CONTINUE) {
                    NodeItem.TableCell origin = pendingVertical.get(gridCol);
                    if (origin != null) {
                        origin.setRowSpan(origin.getRowSpan() + 1);
                        nextPending.put(gridCol, origin);
                        gridCol += colSpan;
                        continue;
                    }
                    // 找不到 origin（首行即 continue，或合并起点在表头行）：当成普通空单元格，不丢数据
                }
                String text = cellText(cell);
                collectCellPictures(cell, context, referenced, cellPictures);
                if (headerRow) {
                    // 首行当表头（与 MarkdownExporter / 旧 Excel 分块的呈现一致）
                    item.getHeaders().add(text);
                } else {
                    NodeItem.TableCell added = new NodeItem.TableCell(text, dataRow - 1, gridCol, 1, colSpan);
                    item.addCell(added);
                    if (vMerge == MergeType.RESTART) {
                        nextPending.put(gridCol, added);
                    }
                }
                gridCol += colSpan;
            }
            pendingVertical = nextPending;
        }
        // 只有一行时全部进了表头：保留空表（GFM 合法，导出与分块都能处理）
        document.append(item);
        for (ImageRef ref : cellPictures) {
            document.append(NodeItem.picture(ref, ref.fileName()));
        }
    }

    /** 单元格内嵌图：复用段落级的 run 扫描，落地后按文档顺序补在表格之后 */
    private void collectCellPictures(XWPFTableCell cell, ParseContext context, Set<String> referenced,
                                      List<ImageRef> collector) {
        if (context.images() == null || !context.extractImages()) {
            return;
        }
        try {
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                for (XWPFPicture picture : collectPictures(paragraph)) {
                    ImageRef ref = savePicture(picture, context, referenced);
                    if (ref != null) {
                        collector.add(ref);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("读取表格单元格图片失败：{}", e.getMessage());
        }
    }

    /** 单元格文本：取整格文本（含嵌套段落），换行压成空格，避免破坏表格结构 */
    private String cellText(XWPFTableCell cell) {
        String text;
        try {
            text = cell.getText();
        } catch (RuntimeException e) {
            text = "";
        }
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private int gridSpan(XWPFTableCell cell) {
        try {
            CTTcPr pr = cell.getCTTc() == null ? null : cell.getCTTc().getTcPr();
            if (pr == null || !pr.isSetGridSpan() || pr.getGridSpan() == null
                    || pr.getGridSpan().getVal() == null) {
                return 1;
            }
            return Math.max(1, pr.getGridSpan().getVal().intValue());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private enum MergeType {RESTART, CONTINUE, NONE}

    /**
     * 纵向合并类型。OOXML 里 {@code <w:vMerge/>} 不带 val 时语义为 continue，
     * 只有 {@code <w:vMerge w:val="restart"/>} 才是合并起点。
     */
    private MergeType verticalMerge(XWPFTableCell cell) {
        try {
            CTTcPr pr = cell.getCTTc() == null ? null : cell.getCTTc().getTcPr();
            if (pr == null || !pr.isSetVMerge() || pr.getVMerge() == null) {
                return MergeType.NONE;
            }
            Object val = pr.getVMerge().getVal();
            if (val == null) {
                return MergeType.CONTINUE;
            }
            return String.valueOf(val).toLowerCase(Locale.ROOT).contains("restart")
                    ? MergeType.RESTART : MergeType.CONTINUE;
        } catch (RuntimeException e) {
            return MergeType.NONE;
        }
    }
}

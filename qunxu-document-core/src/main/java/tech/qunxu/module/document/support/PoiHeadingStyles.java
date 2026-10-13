package tech.qunxu.module.document.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 标题层级判定（docx 抽取的关键质量点）。
 * <p>
 * 三道判据，命中即返回，越靠前越可信：
 * <ol>
 *   <li><b>样式名</b>：{@code Heading n} / 中文 {@code 标题 n}（Word 中文版导出的 docx 样式名是中文，
 *       只按英文判会漏掉全部标题）；{@code Title} 视为文档主标题；</li>
 *   <li><b>大纲级别</b>{@code w:outlineLvl}（0 基）：模板自定义样式但设了大纲级别的场景；</li>
 *   <li><b>字号兜底</b>：样式与大纲都没有时，用「明显大于正文众数字号 + 行短 + 加粗」近似判标题。</li>
 * </ol>
 * 判不出层级就返回 null（按正文段落处理）——宁可少识别标题，也不要把普通段落错当标题污染章节树。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class PoiHeadingStyles {

    /** 样式名里的层级数字：Heading 1 / heading1 / 标题 1 / 標題 2 */
    private static final Pattern HEADING_NUMBER =
            Pattern.compile("^(?:heading|标题|標題)\\s*([1-9])$");

    /** 文档主标题样式 */
    private static final Pattern TITLE_STYLE = Pattern.compile("^(?:title|文档标题|大标题)$");

    /** 字号兜底的最大层级（再大也按 3 级算，避免把 28pt 的公司名当成一级章节） */
    private static final int FALLBACK_MAX_LEVEL = 3;

    private PoiHeadingStyles() {
    }

    /**
     * 判定段落标题层级。
     *
     * @param paragraph    待判定段落
     * @param styles       文档样式表（可为 null）
     * @param bodyFontSize 正文众数字号（由 {@link #bodyFontSize(XWPFDocument)} 得出）
     * @return 层级 1~9；null 表示不是标题
     */
    public static Integer headingLevel(XWPFParagraph paragraph, XWPFStyles styles, double bodyFontSize) {
        if (paragraph == null) {
            return null;
        }
        Integer byStyle = levelFromStyle(paragraph.getStyle(), styles);
        if (byStyle != null) {
            return byStyle;
        }
        Integer byOutline = levelFromOutline(paragraph);
        if (byOutline != null) {
            return byOutline;
        }
        return levelFromFontSize(paragraph, bodyFontSize);
    }

    /** 样式 id → 样式名 → 层级（Word 的 pStyle 存的是 styleId，人类可读名在样式表里） */
    public static Integer levelFromStyle(String styleId, XWPFStyles styles) {
        if (styleId == null || styleId.isBlank()) {
            return null;
        }
        String normalized = normalize(styleId);
        Integer direct = matchNormalized(normalized);
        if (direct != null) {
            return direct;
        }
        if (styles != null) {
            try {
                XWPFStyle style = styles.getStyle(styleId);
                if (style != null && style.getName() != null) {
                    return matchNormalized(normalize(style.getName()));
                }
            } catch (RuntimeException ignored) {
                // 样式表缺失/损坏时按无样式处理
            }
        }
        return null;
    }

    private static Integer matchNormalized(String normalized) {
        if (normalized.isEmpty()) {
            return null;
        }
        if (TITLE_STYLE.matcher(normalized).matches()) {
            return 1;
        }
        Matcher matcher = HEADING_NUMBER.matcher(normalized);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", " ").replaceAll("\\s+", " ");
    }

    /** w:outlineLvl（0 基）→ 层级 */
    public static Integer levelFromOutline(XWPFParagraph paragraph) {
        CTPPr pPr = paragraph.getCTP() == null ? null : paragraph.getCTP().getPPr();
        if (pPr == null) {
            return null;
        }
        return levelFromOutline(pPr);
    }

    /** 从属性对象取大纲级别（pptx 等复用） */
    public static Integer levelFromOutline(CTPPrBase pPr) {
        if (pPr == null || !pPr.isSetOutlineLvl()) {
            return null;
        }
        CTDecimalNumber outline = pPr.getOutlineLvl();
        if (outline == null || outline.getVal() == null) {
            return null;
        }
        int level = outline.getVal().intValue();
        // outlineLvl 9 = Body Text
        if (level < 0 || level > 8) {
            return null;
        }
        return level + 1;
    }

    /**
     * 字号兜底：加粗且字号显著大于正文、且文本不长的段落视为标题。
     */
    private static Integer levelFromFontSize(XWPFParagraph paragraph, double bodyFontSize) {
        String text = paragraph.getParagraphText();
        if (text == null || text.isBlank() || text.length() > 80) {
            return null;
        }
        double size = maxFontSize(paragraph);
        if (size <= 0 || bodyFontSize <= 0) {
            return null;
        }
        double ratio = size / bodyFontSize;
        if (ratio < 1.12 || !hasBoldRun(paragraph)) {
            return null;
        }
        if (ratio >= 1.6) {
            return 1;
        }
        if (ratio >= 1.35) {
            return 2;
        }
        return FALLBACK_MAX_LEVEL;
    }

    /** 段落内最大字号（run 未显式设置时返回 0） */
    public static double maxFontSize(XWPFParagraph paragraph) {
        double max = 0;
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null) {
            return 0;
        }
        for (XWPFRun run : runs) {
            Double size = run.getFontSizeAsDouble();
            if (size != null && size > max) {
                max = size;
            }
        }
        return max;
    }

    /** 是否存在加粗 run（至少一个非空 run 加粗） */
    public static boolean hasBoldRun(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null) {
            return false;
        }
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text != null && !text.isBlank() && run.isBold()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 正文众数字号：按字符数加权的 run 字号众数（大段正文决定「正常字号」，
     * 少数超大标题不会把众数带偏）。
     */
    public static double bodyFontSize(XWPFDocument document) {
        if (document == null) {
            return 0;
        }
        Map<Integer, Integer> weightBySize = new HashMap<>();
        collectFontSize(document.getParagraphs(), weightBySize);
        for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
            for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                    collectFontSize(cell.getParagraphs(), weightBySize);
                }
            }
        }
        int bestCount = 0;
        double bestSize = 0;
        for (Map.Entry<Integer, Integer> entry : weightBySize.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestSize = entry.getKey();
            }
        }
        return bestSize;
    }

    private static void collectFontSize(List<XWPFParagraph> paragraphs, Map<Integer, Integer> weightBySize) {
        if (paragraphs == null) {
            return;
        }
        for (XWPFParagraph paragraph : paragraphs) {
            List<XWPFRun> runs = paragraph.getRuns();
            if (runs == null) {
                continue;
            }
            for (XWPFRun run : runs) {
                String text = run.getText(0);
                Double size = run.getFontSizeAsDouble();
                if (text == null || text.isBlank() || size == null || size <= 0) {
                    continue;
                }
                weightBySize.merge((int) Math.round(size), text.length(), Integer::sum);
            }
        }
    }
}

package tech.qunxu.module.document.export;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown 导出器（对应 docling export_to_markdown）。
 * <p>
 * 按文档顺序渲染：标题按 level 加 {@code #}、列表项加 {@code -}、表格渲染为 GFM 表格
 * （合并单元格在 Markdown 无法表达，横向合并复制文本、纵向合并留空）、图片渲染为
 * {@code ![alt](url)}。页眉页脚不参与导出。
 * <p>
 * Sheet 标题固定渲染为 {@code ### Sheet: 名称}，与改造前的 Excel 分块文本保持逐块一致。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class MarkdownExporter implements DocumentExporter {

    @Override
    public String key() {
        return "markdown";
    }

    @Override
    public String export(QunxuDocument document) {
        if (document == null || document.getItems().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (NodeItem item : document.getItems()) {
            DocumentKind kind = item.getKind();
            if (kind == null || kind == DocumentKind.PAGE_HEADER || kind == DocumentKind.PAGE_FOOTER) {
                continue;
            }
            switch (kind) {
                case TITLE -> appendHeading(sb, 1, item.getText());
                case SECTION_HEADER ->
                        appendHeading(sb, ((NodeItem.HeadingItem) item).getLevel(), item.getText());
                case SHEET -> {
                    // 与改造前 ExcelDocumentSplitter 的 Sheet 前缀一致，保证分块文本可逐块 diff
                    sb.append("### Sheet: ").append(safe(item.getText())).append("\n\n");
                }
                case LIST_ITEM -> {
                    String marker = item instanceof NodeItem.ListItem list && list.getMarker() != null
                            && !list.getMarker().isBlank() ? list.getMarker() : "-";
                    sb.append(marker).append(' ').append(safe(item.getText())).append('\n');
                }
                case CODE -> {
                    String language = item instanceof NodeItem.CodeItem code && code.getLanguage() != null
                            ? code.getLanguage() : "";
                    sb.append("```").append(language).append('\n')
                            .append(safe(item.getText())).append('\n')
                            .append("```\n\n");
                }
                case TABLE -> appendTable(sb, (NodeItem.TableItem) item);
                case PICTURE -> {
                    NodeItem.PictureItem picture = (NodeItem.PictureItem) item;
                    String alt = safe(picture.getText());
                    String url = picture.getImage() == null ? "" : nullToEmpty(picture.getImage().url()).trim();
                    if (url.isEmpty()) {
                        // 未落地（noop sink 或被过滤/失败）：只保留替代文本，不产出坏链接语法
                        if (!alt.isEmpty()) {
                            sb.append(alt).append("\n\n");
                        }
                    } else {
                        sb.append("![").append(escapeInline(alt)).append("](").append(url).append(")\n\n");
                    }
                }
                case FORMULA -> sb.append("$$").append(safe(item.getText())).append("$$\n\n");
                default -> {
                    if (item.getText() != null && !item.getText().isBlank()) {
                        sb.append(item.getText().trim()).append("\n\n");
                    }
                }
            }
        }
        return sb.toString().trim() + (sb.isEmpty() ? "" : "\n");
    }

    private void appendHeading(StringBuilder sb, int level, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        sb.append("#".repeat(Math.min(6, Math.max(1, level))))
                .append(' ')
                .append(text.trim())
                .append("\n\n");
    }

    /** GFM 表格：表头行 + 分隔行 + 数据行；合并单元格按占位展开 */
    private void appendTable(StringBuilder sb, NodeItem.TableItem table) {
        if (table.getCaption() != null && !table.getCaption().isBlank()) {
            sb.append(table.getCaption().trim()).append("\n\n");
        }
        List<String> headers = table.getHeaders();
        int cols = Math.max(headers.size(), table.getColCount());
        if (cols == 0) {
            return;
        }
        // 先把带跨度的单元格展开成规则网格（横向合并复制文本，纵向合并留空）
        int rows = table.getRowCount();
        String[][] grid = new String[Math.max(rows, 0)][cols];
        for (NodeItem.TableCell cell : table.getCells()) {
            for (int r = cell.getRowIndex(); r < Math.min(rows, cell.getRowIndex() + cell.getRowSpan()); r++) {
                for (int c = cell.getColIndex(); c < Math.min(cols, cell.getColIndex() + cell.getColSpan()); c++) {
                    grid[r][c] = r == cell.getRowIndex() && c == cell.getColIndex() ? cell.getText() : "";
                }
            }
        }
        List<String> effectiveHeaders = headers;
        int dataStart = 0;
        if (effectiveHeaders.isEmpty() && rows > 0) {
            // 无显式表头时把首行当表头（与 GFM 语法要求一致）
            effectiveHeaders = new ArrayList<>(Arrays.asList(grid[0]));
            dataStart = 1;
        }
        appendRow(sb, effectiveHeaders, cols);
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = dataStart; r < rows; r++) {
            appendRow(sb, Arrays.asList(grid[r]), cols);
        }
        sb.append('\n');
    }

    private void appendRow(StringBuilder sb, List<String> cells, int cols) {
        sb.append("| ");
        for (int c = 0; c < cols; c++) {
            if (c > 0) {
                sb.append(" | ");
            }
            String value = c < cells.size() ? cells.get(c) : "";
            sb.append(escapeCell(value));
        }
        sb.append(" |\n");
    }

    /** Markdown 表格单元格转义：竖线需转义，换行折叠为空格，避免破坏表格结构 */
    public static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 行内文本转义（图片 alt 等场景，竖线与方括号会破坏语法） */
    private static String escapeInline(String value) {
        return value.replace("[", "").replace("]", "");
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}

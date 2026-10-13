package tech.qunxu.module.document.export;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

/**
 * HTML 导出器（docling 无对应，本项目新增能力：前端富预览与文档下载）。
 * <p>
 * 表格保留合并语义（{@code rowspan}/{@code colspan}），图片输出 {@code <img>}，
 * 连续列表项合并进同一个 {@code <ul>}。所有文本做 HTML 转义。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class HtmlExporter implements DocumentExporter {

    @Override
    public String key() {
        return "html";
    }

    @Override
    public String export(QunxuDocument document) {
        StringBuilder sb = new StringBuilder();
        if (document == null || document.getItems().isEmpty()) {
            return "";
        }
        boolean inList = false;
        for (NodeItem item : document.getItems()) {
            DocumentKind kind = item.getKind();
            if (kind == null || kind == DocumentKind.PAGE_HEADER || kind == DocumentKind.PAGE_FOOTER) {
                continue;
            }
            if (kind != DocumentKind.LIST_ITEM && inList) {
                sb.append("</ul>\n");
                inList = false;
            }
            switch (kind) {
                case TITLE -> appendHeading(sb, 1, item.getText());
                case SECTION_HEADER, SHEET -> appendHeading(
                        sb, ((NodeItem.HeadingItem) item).getLevel(), item.getText());
                case LIST_ITEM -> {
                    if (!inList) {
                        sb.append("<ul>\n");
                        inList = true;
                    }
                    sb.append("  <li>").append(escape(item.getText())).append("</li>\n");
                }
                case CODE -> sb.append("<pre><code>")
                        .append(escape(item.getText()))
                        .append("</code></pre>\n");
                case TABLE -> appendTable(sb, (NodeItem.TableItem) item);
                case PICTURE -> {
                    NodeItem.PictureItem picture = (NodeItem.PictureItem) item;
                    String src = picture.getImage() == null ? "" : nullToEmpty(picture.getImage().url());
                    sb.append("<p><img src=\"").append(escape(src))
                            .append("\" alt=\"").append(escape(nullToEmpty(picture.getText())))
                            .append("\" /></p>\n");
                }
                default -> {
                    String text = trim(item.getText());
                    if (!text.isEmpty()) {
                        sb.append("<p>").append(escape(text)).append("</p>\n");
                    }
                }
            }
        }
        if (inList) {
            sb.append("</ul>\n");
        }
        return sb.toString();
    }

    private void appendHeading(StringBuilder sb, int level, String text) {
        String trimmed = trim(text);
        if (trimmed.isEmpty()) {
            return;
        }
        int safeLevel = Math.min(6, Math.max(1, level));
        sb.append("<h").append(safeLevel).append('>')
                .append(escape(trimmed))
                .append("</h").append(safeLevel).append(">\n");
    }

    private void appendTable(StringBuilder sb, NodeItem.TableItem table) {
        int cols = Math.max(table.getHeaders().size(), table.getColCount());
        if (cols == 0) {
            return;
        }
        sb.append("<table>\n");
        if (!table.getHeaders().isEmpty()) {
            sb.append("  <thead><tr>");
            for (int c = 0; c < cols; c++) {
                String value = c < table.getHeaders().size() ? table.getHeaders().get(c) : "";
                sb.append("<th>").append(escape(nullToEmpty(value))).append("</th>");
            }
            sb.append("</tr></thead>\n");
        }
        sb.append("  <tbody>\n");
        for (int r = 0; r < table.getRowCount(); r++) {
            sb.append("    <tr>");
            for (int c = 0; c < cols; c++) {
                NodeItem.TableCell cell = findOrigin(table, r, c);
                if (cell == null) {
                    // 被上方 rowspan 覆盖的位置：HTML 不需要占位单元格
                    continue;
                }
                sb.append("<td");
                if (cell.getRowSpan() > 1) {
                    sb.append(" rowspan=\"").append(cell.getRowSpan()).append('"');
                }
                if (cell.getColSpan() > 1) {
                    sb.append(" colspan=\"").append(cell.getColSpan()).append('"');
                }
                sb.append('>').append(escape(nullToEmpty(cell.getText()))).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("  </tbody>\n</table>\n");
    }

    /** 取该坐标的「原点」单元格；若被其他单元格的跨度覆盖则返回 null */
    private NodeItem.TableCell findOrigin(NodeItem.TableItem table, int row, int col) {
        for (NodeItem.TableCell cell : table.getCells()) {
            int r0 = cell.getRowIndex();
            int c0 = cell.getColIndex();
            if (r0 == row && c0 == col) {
                return cell;
            }
            if (row >= r0 && row < r0 + cell.getRowSpan() && col >= c0 && col < c0 + cell.getColSpan()) {
                return null;
            }
        }
        return null;
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** HTML 文本转义（属性与文本节点通用，引号一并转义） */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

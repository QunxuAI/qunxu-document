package tech.qunxu.module.document.export;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

import java.util.List;

/**
 * 纯文本导出器。
 * <p>
 * 主要用途是把中间格式「降级」回改造前的纯文本形态，喂给 {@code FlatChunker}
 * 的字符/正则/Markdown/行 分割策略，保证旧 splitterName 的分段行为可回归对比。
 * 因此这里刻意不带 Markdown 标记（标题只保留文本 + 换行）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class TextExporter implements DocumentExporter {

    @Override
    public String key() {
        return "text";
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
            if (kind == DocumentKind.TABLE) {
                appendTable(sb, (NodeItem.TableItem) item);
                continue;
            }
            if (kind == DocumentKind.PICTURE) {
                NodeItem.PictureItem picture = (NodeItem.PictureItem) item;
                String alt = picture.getText();
                if (alt != null && !alt.isBlank()) {
                    sb.append(alt.trim()).append('\n');
                }
                continue;
            }
            if (kind == DocumentKind.LIST_ITEM) {
                sb.append("- ").append(trim(item.getText())).append('\n');
                continue;
            }
            String text = trim(item.getText());
            if (!text.isEmpty()) {
                sb.append(text).append('\n');
                // 标题与段落之后补一个空行，保持 Tika 输出的分段观感
                if (kind == DocumentKind.TITLE || kind == DocumentKind.SECTION_HEADER
                        || kind == DocumentKind.SHEET || kind == DocumentKind.PARAGRAPH
                        || kind == DocumentKind.CODE) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    /** 表格按 Markdown 管道行输出（纯文本下仍需保留行列语义，否则检索无法对齐列名） */
    private void appendTable(StringBuilder sb, NodeItem.TableItem table) {
        int cols = Math.max(table.getHeaders().size(), table.getColCount());
        if (cols == 0) {
            return;
        }
        if (!table.getHeaders().isEmpty()) {
            appendRow(sb, table.getHeaders(), cols);
        }
        for (int r = 0; r < table.getRowCount(); r++) {
            String[] row = new String[cols];
            for (int c = 0; c < cols; c++) {
                row[c] = nullToEmpty(table.cellText(r, c));
            }
            appendRow(sb, List.of(row), cols);
        }
        sb.append('\n');
    }

    private void appendRow(StringBuilder sb, List<String> cells, int cols) {
        sb.append("| ");
        for (int c = 0; c < cols; c++) {
            if (c > 0) {
                sb.append(" | ");
            }
            sb.append(MarkdownExporter.escapeCell(c < cells.size() ? cells.get(c) : ""));
        }
        sb.append(" |\n");
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}

package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器：把 md 源还原成中间格式（标题栈建层级、围栏代码块、列表、GFM 表格、图片）。
 * <p>
 * 外链图片<b>不下载</b>：知识库导入不应产生对外网络请求（SSRF 与耗时都不可控），
 * 只保留 alt 文本，导出时以纯文本呈现。
 * <p>
 * 与 {@code MarkdownHeaderDocumentSplitter}（旧分割器之一）的关系：旧的是「纯文本按 # 标题切」，
 * 本 Backend 产出结构化节点，标题层级由 {@code QunxuDocument.append} 的标题栈维护，
 * 旧的 {@code FlatChunker} 分支仍保留原文本切法以零回归。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class MarkdownDocumentBackend implements DocumentBackend {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final Pattern SETTEXT_HEADING = Pattern.compile("^\\s*(=+|-+)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([\\w+-]*)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]\\s+|\\d+[.)，]\\s*)(.*\\S)\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    @Override
    public String key() {
        return "markdown";
    }

    @Override
    public String label() {
        return "Markdown";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.MARKDOWN;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        QunxuDocument document = context.newDocument();
        String text = context.text();
        if (text == null || text.isBlank()) {
            document.warn("Markdown 文件为空");
            return document;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> paragraph = new ArrayList<>();
        List<String> tableBlock = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                flushParagraph(document, paragraph);
                flushTable(document, tableBlock);
                i++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushParagraph(document, paragraph);
                flushTable(document, tableBlock);
                String language = fence.group(2) == null || fence.group(2).isBlank() ? null : fence.group(2);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !FENCE.matcher(lines[i]).matches()) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                String body = code.toString().stripTrailing();
                if (!body.isBlank()) {
                    document.append(NodeItem.code(language, body));
                }
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(document, paragraph);
                flushTable(document, tableBlock);
                int level = heading.group(1).length();
                String title = heading.group(2).trim();
                DocumentKind kind = level == 1 && isFirstHeading(document)
                        ? DocumentKind.TITLE : DocumentKind.SECTION_HEADER;
                document.append(new NodeItem.HeadingItem(kind, level, stripInline(title)));
                i++;
                continue;
            }
            // Setext 标题：上一行文本 + === / --- 分隔线
            if (SETTEXT_HEADING.matcher(line).matches() && !paragraph.isEmpty()) {
                int level = line.trim().charAt(0) == '=' ? 1 : 2;
                String title = String.join(" ", paragraph).trim();
                paragraph.clear();
                document.append(new NodeItem.HeadingItem(level == 1 && isFirstHeading(document)
                        ? DocumentKind.TITLE : DocumentKind.SECTION_HEADER, level, stripInline(title)));
                i++;
                continue;
            }
            if (TABLE_ROW.matcher(line).find()) {
                flushParagraph(document, paragraph);
                tableBlock.add(trimmed);
                i++;
                continue;
            }
            if (!tableBlock.isEmpty()) {
                flushTable(document, tableBlock);
            }
            Matcher list = LIST_ITEM.matcher(line);
            if (list.matches()) {
                flushParagraph(document, paragraph);
                document.append(new NodeItem.ListItem(stripInline(list.group(2).trim()),
                        normalizeMarker(list.group(1))));
                i++;
                continue;
            }
            Matcher quote = QUOTE.matcher(line);
            if (quote.matches()) {
                paragraph.add(quote.group(1).trim());
                i++;
                continue;
            }
            paragraph.add(trimmed);
            i++;
        }
        flushParagraph(document, paragraph);
        flushTable(document, tableBlock);
        if (document.isEmpty()) {
            document.warn("Markdown 未解析出内容");
        }
        return document;
    }

    private boolean isFirstHeading(QunxuDocument document) {
        for (NodeItem item : document.getItems()) {
            if (item.isHeading()) {
                return false;
            }
        }
        return true;
    }

    /** 段落：连续非空行合并为一行文本（与旧的按空行分段一致） */
    private void flushParagraph(QunxuDocument document, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        String text = String.join(" ", paragraph).trim();
        paragraph.clear();
        if (text.isEmpty()) {
            return;
        }
        appendWithPictures(document, stripInline(text));
    }

    /**
     * 行内图片就地成块：图片单独成节点（后续可被独立分块），其余文本作为段落。
     */
    private void appendWithPictures(QunxuDocument document, String text) {
        Matcher matcher = IMAGE.matcher(text);
        int last = 0;
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String before = text.substring(last, matcher.start()).trim();
            if (!before.isEmpty()) {
                document.append(NodeItem.paragraph(before));
            }
            String alt = matcher.group(1) == null ? "" : altClean(matcher.group(1));
            // 不下载外链图：url 留空，导出时只呈现 alt 文本
            document.append(new NodeItem.PictureItem(null, alt.isEmpty() ? "图片" : alt));
            last = matcher.end();
        }
        String tail = text.substring(Math.min(last, text.length())).trim();
        if (!any) {
            document.append(NodeItem.paragraph(text));
        } else if (!tail.isEmpty()) {
            document.append(NodeItem.paragraph(stripInline(tail)));
        }
    }

    private String altClean(String alt) {
        return alt.replace("[", "").replace("]", "").trim();
    }

    /** 去掉行内标记，保留可读文本（粗体/斜体/行内代码/链接文字） */
    private String stripInline(String text) {
        String result = IMAGE.matcher(text).replaceAll("$1");
        result = result.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");
        result = result.replaceAll("(`[^`]+`|\\*\\*[^*]+\\*\\*|__[^_]+__)", "$1");
        result = result.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)", "$1");
        return result.trim();
    }

    private String normalizeMarker(String marker) {
        String value = marker.trim();
        if (value.isEmpty()) {
            return "-";
        }
        if (value.charAt(0) == '-' || value.charAt(0) == '*' || value.charAt(0) == '+') {
            return "-";
        }
        return "1.";
    }

    /** GFM 表格：跳过分隔行，首行当表头，其余进数据区 */
    private void flushTable(QunxuDocument document, List<String> block) {
        if (block.isEmpty()) {
            return;
        }
        List<String> header = null;
        List<List<String>> grid = new ArrayList<>();
        for (String row : block) {
            if (TABLE_DIVIDER.matcher(row).matches()) {
                continue;
            }
            List<String> cells = splitRow(row);
            if (header == null) {
                header = cells;
            } else {
                grid.add(cells);
            }
        }
        block.clear();
        if (header == null) {
            return;
        }
        NodeItem.TableItem table = new NodeItem.TableItem(null);
        table.getHeaders().addAll(header);
        int cols = header.size();
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            cols = Math.max(cols, row.size());
            for (int c = 0; c < row.size(); c++) {
                table.addCell(new NodeItem.TableCell(row.get(c), r, c, 1, 1));
            }
        }
        table.setColCount(cols);
        document.append(table);
    }

    private List<String> splitRow(String row) {
        String value = row.trim();
        if (value.startsWith("|")) {
            value = value.substring(1);
        }
        if (value.endsWith("|")) {
            value = value.substring(0, value.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                current.append(ch == '|' ? "|" : "\\" + ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '|') {
                cells.add(stripInline(current.toString().trim()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0 || !cells.isEmpty()) {
            cells.add(stripInline(current.toString().trim()));
        }
        return cells;
    }
}

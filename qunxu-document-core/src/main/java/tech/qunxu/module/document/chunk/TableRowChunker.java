package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 表格行分块器（改造前 {@code ExcelDocumentSplitter} 的结构化等价物）。
 * <p>
 * 每个 {@code TABLE} 独立成块：按 {@code rowsPerChunk} 行分组，且受 token 预算双重约束
 * （行数 ≤ rowsPerChunk 且 token ≤ 预算），每块重复 Sheet 名/表头，保证块内表格语义完整。
 * 这套「行数 + token」双上限来自改造前的线上教训：只按行数分块时，宽表/大单元格会产生
 * 超过 embedding 单文档上限的块，导致<b>整篇文档</b>索引失败。
 * <p>
 * Sheet 前缀固定为 {@code ### Sheet: 名称}，与改造前逐字节一致（回归要求「分块结果可 diff」），
 * 因此本类同时充当 {@code ExcelDocumentSplitter} 兼容别名的实际实现。
 * <p>
 * 合并单元格的重建规则：把每个单元格的文本放到自己的列位置、跨度内其余列填空串，
 * 行尾不补齐 —— 这样从 {@code tabular} Backend 出来的网格能还原成解析器的原始行宽。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class TableRowChunker implements BaseChunker {

    /** 分块器标识（app 侧 splitterName 与 {@code ExcelDocumentSplitter} 别名都指向它） */
    public static final String KEY = "table-row";

    private final TokenCounter tokens;

    public TableRowChunker() {
        this(new JTokkitTokenCounter());
    }

    public TableRowChunker(TokenCounter tokens) {
        this.tokens = tokens == null ? new JTokkitTokenCounter() : tokens;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "表格行分块";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean prefers(QunxuDocument document) {
        if (document == null) {
            return false;
        }
        // 纯表格文档（没有正文段落）走行分块更合适；混排文档交给结构分块器安排上下文
        return document.countOf(DocumentKind.TABLE) > 0 && document.countOf(DocumentKind.PARAGRAPH) == 0;
    }

    @Override
    public List<Chunk> chunk(QunxuDocument document, ChunkOptions options) {
        List<Chunk> chunks = new ArrayList<>();
        if (document == null || document.isEmpty()) {
            return chunks;
        }
        ChunkOptions effective = options == null ? ChunkOptions.defaults() : options;
        String sheetName = null;
        for (NodeItem item : document.getItems()) {
            if (item.getKind() == DocumentKind.SHEET) {
                sheetName = item.getText();
                continue;
            }
            if (!(item instanceof NodeItem.TableItem table)) {
                continue;
            }
            String prefix = sheetPrefix(sheetName);
            List<String> path = sheetName == null ? List.of() : List.of(sheetName);
            chunks.addAll(groupRows(table, prefix, path, effective, tokens, item.pageIndex(), item.getIndex()));
        }
        return chunks;
    }

    // ==================== 共享装块逻辑（HierarchicalChunker 的 TABLE 分支复用） ====================

    /**
     * 把一张表按行分块。
     *
     * @param table        表格节点
     * @param prefix       块首固定文本（Sheet 标题或标题面包屑 + 表说明），已含结尾换行
     * @param headingPath  写入 Chunk 的溯源标题路径，可为空
     * @param options      分块选项
     * @param tokens       token 计数器
     * @param pageIndex    页码（0 表示未知）
     * @param elementIndex 表格节点下标
     */
    public static List<Chunk> groupRows(NodeItem.TableItem table, String prefix, List<String> headingPath,
                                        ChunkOptions options, TokenCounter tokens,
                                        int pageIndex, int elementIndex) {
        List<Chunk> chunks = new ArrayList<>();
        if (table == null) {
            return chunks;
        }
        ChunkOptions effective = options == null ? ChunkOptions.defaults() : options;
        TokenCounter counter = tokens == null ? new JTokkitTokenCounter() : tokens;
        // 显式表头（tabular Backend 的 ofGrid 已把首行提为 headers）时，数据区就是剩余行，
        // 与改造前「headers = rows.get(0)、start = 1」完全等价；
        // 无显式表头时不擅自把首行当表头 —— 那会凭空少掉一行数据，改变块数
        List<List<String>> rows = toRows(table);
        List<String> explicitHeaders = table.getHeaders();
        boolean hasExplicitHeader = explicitHeaders != null && !explicitHeaders.isEmpty();
        boolean repeatHeader = hasExplicitHeader && effective.isIncludeTableHeader();
        List<String> headers = repeatHeader ? explicitHeaders : List.of();
        List<List<String>> data = rows;

        int budget = Math.min(effective.targetTokens(), effective.ceilingTokens());
        int rowLimit = effective.rowLimit();
        String prefixText = prefix == null ? "" : prefix;
        int prefixTokens = counter.count(prefixText);
        int headerTokens = headers.isEmpty() ? 0 : counter.count(renderHeader(headers));

        int i = 0;
        while (i < data.size()) {
            StringBuilder body = new StringBuilder();
            int bodyTokens = 0;
            int rowCount = 0;
            while (i < data.size() && rowCount < rowLimit) {
                String rowText = renderRow(data.get(i));
                int rowTokens = counter.count(rowText);
                // 至少装一行，否则再加一行会超预算：当前块收尾，剩余行另起一块
                if (rowCount > 0 && prefixTokens + headerTokens + bodyTokens + rowTokens > budget) {
                    break;
                }
                body.append(rowText);
                bodyTokens += rowTokens;
                rowCount++;
                i++;
            }
            String text = prefixText
                    + (headers.isEmpty() ? "" : renderHeader(headers))
                    + body;
            chunks.add(buildChunk(text, headingPath, pageIndex, elementIndex, counter, effective));
            if (rowCount == 0) {
                // 防御：单行本身超预算且一行都装不下时强制消费一行，杜绝死循环
                i++;
            }
        }
        if (chunks.isEmpty()) {
            // 只有表头、没有数据行：仍输出一个含表头的块
            String text = prefixText + (headers.isEmpty() ? "" : renderHeader(headers));
            if (!text.isBlank()) {
                chunks.add(buildChunk(text, headingPath, pageIndex, elementIndex, counter, effective));
            }
        }
        return chunks;
    }

    private static Chunk buildChunk(String text, List<String> headingPath,
                                    int pageIndex, int elementIndex, TokenCounter counter,
                                    ChunkOptions options) {
        String trimmed = truncateToBudget(text, counter, options.ceilingTokens());
        Chunk chunk = Chunk.of(trimmed, DocumentKind.TABLE);
        chunk.pages(pageIndex, pageIndex);
        chunk.element(elementIndex);
        if (headingPath != null) {
            chunk.getHeadingPath().addAll(headingPath);
        }
        chunk.setTokenCount(counter.count(trimmed));
        return chunk;
    }

    /**
     * 按 token 预算截断：超出时按 token/字符比例逐步裁剪。
     * 与改造前 {@code ExcelDocumentSplitter#truncateToTokenBudget} 同一算法（同样的比例裁剪，
     * 避免「新实现更聪明」导致的回归差异）。
     */
    static String truncateToBudget(String text, TokenCounter tokens, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return text;
        }
        TokenCounter counter = tokens == null ? new JTokkitTokenCounter() : tokens;
        String cut = text;
        while (cut.length() > 1) {
            int current = counter.count(cut);
            if (current <= maxTokens) {
                break;
            }
            int target = (int) ((long) cut.length() * maxTokens / current);
            target = Math.max(1, Math.min(cut.length() - 1, target));
            cut = cut.substring(0, target);
        }
        return cut;
    }

    /** Sheet 前缀（与改造前逐字一致） */
    public static String sheetPrefix(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return "";
        }
        return "### Sheet: " + sheetName.trim() + "\n\n";
    }

    /** 表头行 + 分隔行 */
    static String renderHeader(List<String> headers) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        sb.append('|');
        for (int j = 0; j < headers.size(); j++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        return sb.toString();
    }

    static String renderRow(List<String> row) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, row);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row) {
        sb.append("| ");
        for (int j = 0; j < row.size(); j++) {
            if (j > 0) {
                sb.append(" | ");
            }
            sb.append(escapeCell(row.get(j)));
        }
        sb.append(" |\n");
    }

    private static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 把带跨度的单元格展开成行列表（跨度内其余列填空串） */
    static List<List<String>> toRows(NodeItem.TableItem table) {
        List<List<String>> rows = new ArrayList<>();
        List<NodeItem.TableCell> cells = new ArrayList<>(table.getCells());
        cells.sort(Comparator.comparingInt(NodeItem.TableCell::getRowIndex)
                .thenComparingInt(NodeItem.TableCell::getColIndex));
        int currentRow = -1;
        List<String> row = null;
        for (NodeItem.TableCell cell : cells) {
            if (cell.getRowIndex() != currentRow) {
                row = new ArrayList<>();
                rows.add(row);
                currentRow = cell.getRowIndex();
            }
            while (row.size() < cell.getColIndex()) {
                // 纵向合并覆盖的位置：文本只出现在合并起点，其余留空（与 Markdown 表格的占位规则一致）
                row.add("");
            }
            row.add(cell.getText() == null ? "" : cell.getText());
            for (int span = 1; span < Math.max(1, cell.getColSpan()); span++) {
                row.add("");
            }
        }
        return rows;
    }
}

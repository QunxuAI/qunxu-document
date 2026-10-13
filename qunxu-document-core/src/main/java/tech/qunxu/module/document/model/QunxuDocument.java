package tech.qunxu.module.document.model;

import tech.qunxu.module.document.export.HtmlExporter;
import tech.qunxu.module.document.export.JsonExporter;
import tech.qunxu.module.document.export.MarkdownExporter;
import tech.qunxu.module.document.export.TextExporter;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一中间格式（对应 docling 的 DoclingDocument）。
 * <p>
 * 所有解析器（Backend）的唯一产物，所有导出器（Exporter）与分块器（Chunker）的唯一输入：
 * 「格式差异止于 Backend，下游只认本文档」。新增一种文件格式只需新增一个 Backend，
 * 导出、分块、缓存、检索链路零改动。
 * <p>
 * 结构：{@link DocumentMeta}（来源与指纹）+ 页面表 + 扁平节点列表（节点间以 index 构成树）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class QunxuDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    private DocumentMeta meta = new DocumentMeta();
    private final List<PageInfo> pages = new ArrayList<>();
    private final List<NodeItem> items = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /** 追加时的标题栈：用于把后续节点挂到最近一级标题下，形成章节树 */
    private final Deque<NodeItem> headingStack = new ArrayDeque<>();

    public QunxuDocument() {
    }

    public QunxuDocument(DocumentMeta meta) {
        if (meta != null) {
            this.meta = meta;
        }
    }

    // ==================== 构建 ====================

    /**
     * 追加一个节点并自动挂载到章节树：标题节点按 level 出栈后成为新的父级，
     * 非标题节点挂在当前栈顶标题下。
     *
     * @return 传入的节点（便于链式设置 prov 等）
     */
    public <T extends NodeItem> T append(T item) {
        if (item == null) {
            return null;
        }
        item.setIndex(items.size());
        items.add(item);
        int level = item instanceof NodeItem.HeadingItem heading ? heading.getLevel() : 1;
        if (item.isHeading()) {
            while (!headingStack.isEmpty()) {
                NodeItem top = headingStack.peek();
                int topLevel = top instanceof NodeItem.HeadingItem topHeading
                        ? topHeading.getLevel() : 1;
                if (topLevel >= level) {
                    headingStack.pop();
                } else {
                    break;
                }
            }
        }
        NodeItem parent = headingStack.peek();
        if (parent != null) {
            item.setParentIndex(parent.getIndex());
            parent.getChildIndexes().add(item.getIndex());
        }
        if (item.isHeading()) {
            headingStack.push(item);
        }
        return item;
    }

    /** 追加一个纯文本段落 */
    public NodeItem.TextItem appendParagraph(String text) {
        return append(NodeItem.paragraph(text));
    }

    /** 追加一个章节标题 */
    public NodeItem.HeadingItem appendHeading(int level, String text) {
        return append(NodeItem.heading(level, text));
    }

    /** 记录一个页面 */
    public QunxuDocument addPage(int index, float width, float height) {
        pages.add(new PageInfo(index, width, height));
        return this;
    }

    /**
     * 在指定位置插入节点，并保持文档顺序与父子引用一致（OCR 把补出来的文字放回它所属的页）。
     * <p>
     * {@link #append} 只能追加到末尾，而扫描件 OCR 出来的文字必须落在它自己的页上 ——
     * 混排文档（部分页有文字层、部分页是图片）里一律追加会让后半段的阅读顺序彻底错位，
     * 分块的面包屑与页码也跟着错。因此这里做一次全量重编号：插入后所有节点的
     * {@code index}、{@code parentIndex}、{@code childIndexes} 都按新旧位置映射改写。
     * <p>
     * 插入节点没带父级时，继承插入点前一个节点的父级，使补出来的段落仍挂在所属章节下。
     *
     * @param position 插入下标（0 ~ 当前节点数，越界自动钳制）
     * @param inserted 待插入节点（按阅读顺序）
     * @return 实际插入的节点数
     */
    public int insertAt(int position, List<NodeItem> inserted) {
        if (inserted == null || inserted.isEmpty()) {
            return 0;
        }
        int at = Math.max(0, Math.min(position, items.size()));
        Integer inherited = at == 0 ? null : items.get(at - 1).getParentIndex();
        for (NodeItem item : inserted) {
            if (item == null) {
                continue;
            }
            if (item.getParentIndex() == null) {
                item.setParentIndex(inherited);
            }
            // 新节点尚未入列，用 -1 与已有编号区分：它没有「旧位置」，不能进新旧映射表
            item.setIndex(-1);
        }
        items.addAll(at, inserted);
        reindex();
        for (NodeItem item : inserted) {
            if (item == null || item.getParentIndex() == null) {
                continue;
            }
            NodeItem parent = item(item.getParentIndex());
            if (parent != null && !parent.getChildIndexes().contains(item.getIndex())) {
                parent.getChildIndexes().add(item.getIndex());
            }
        }
        return inserted.size();
    }

    /**
     * 找「下一页开始的位置」：返回第一个页码严格大于 {@code page} 的节点下标，
     * 没有则返回末尾下标（即该页及其之前的内容都排在已给出区段里）。
     * 无页码信息（{@code pageIndex < 1}）的节点不参与判定，避免把跨页装饰当成分界。
     */
    public int indexOfPageEnd(int page) {
        for (int i = 0; i < items.size(); i++) {
            NodeItem node = items.get(i);
            int nodePage = node == null ? 0 : node.pageIndex();
            if (nodePage > page) {
                return i;
            }
        }
        return items.size();
    }

    /** 按新旧位置映射改写 index / parentIndex / childIndexes（{@link #insertAt} 的内部步骤） */
    private void reindex() {
        Map<Integer, Integer> moved = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            NodeItem node = items.get(i);
            if (node == null) {
                continue;
            }
            int old = node.getIndex();
            if (old >= 0) {
                moved.putIfAbsent(old, i);
            }
            node.setIndex(i);
        }
        for (NodeItem node : items) {
            if (node == null) {
                continue;
            }
            if (node.getParentIndex() != null) {
                node.setParentIndex(moved.getOrDefault(node.getParentIndex(), node.getParentIndex()));
            }
            List<Integer> children = node.getChildIndexes();
            for (int c = 0; c < children.size(); c++) {
                Integer child = children.get(c);
                if (child != null) {
                    children.set(c, moved.getOrDefault(child, child));
                }
            }
        }
    }

    /** 记录解析告警（不阻断转换，最终随结果返回并持久化） */
    public QunxuDocument warn(String message) {
        if (message != null && !warnings.contains(message)) {
            warnings.add(message);
        }
        return this;
    }

    // ==================== 查询 ====================

    /** 按语义角色过滤节点（保持文档顺序） */
    public List<NodeItem> itemsOf(DocumentKind kind) {
        List<NodeItem> result = new ArrayList<>();
        for (NodeItem item : items) {
            if (item.getKind() == kind) {
                result.add(item);
            }
        }
        return result;
    }

    /** 统计某类节点数量（模块自检与观测用） */
    public int countOf(DocumentKind kind) {
        int n = 0;
        for (NodeItem item : items) {
            if (item.getKind() == kind) {
                n++;
            }
        }
        return n;
    }

    /**
     * 标题类节点总数（TITLE + SECTION_HEADER + SHEET）。
     * 自动选型靠它判定「这份文档有没有结构可利用」：有标题才值得走结构分块。
     */
    public int headingCount() {
        return countOf(DocumentKind.TITLE)
                + countOf(DocumentKind.SECTION_HEADER)
                + countOf(DocumentKind.SHEET);
    }

    /**
     * 是否为纯表格文档（有表格但没有正文段落，典型是 xlsx/xls/csv）。
     * 这类文档按行分块比按章节装填更贴合召回粒度，自动选型时结构分块会让位。
     */
    public boolean isPureTable() {
        return countOf(DocumentKind.TABLE) > 0 && countOf(DocumentKind.PARAGRAPH) == 0;
    }

    /** 按 index 取节点，越界返回 null */
    public NodeItem item(Integer index) {
        if (index == null || index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    /**
     * 节点的标题面包屑（自根到该节点父级的标题文本，不含自身）。
     * 分块时作为上下文前缀注入，使「第 3.2.1 节里的一段」脱离原文也能定位语义。
     */
    public List<String> headingPathOf(NodeItem item) {
        List<String> path = new ArrayList<>();
        if (item == null) {
            return path;
        }
        NodeItem cursor = item;
        int guard = 0;
        while (cursor != null && cursor.getParentIndex() != null && guard++ < items.size() + 1) {
            cursor = item(cursor.getParentIndex());
            if (cursor == null) {
                break;
            }
            if (cursor.isHeading() && cursor.getText() != null && !cursor.getText().isBlank()) {
                path.add(0, cursor.getText().trim());
            }
        }
        return path;
    }

    /** 是否无任何可输出内容 */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 可索引文本量：真正能被检索到的字符数（图片与页眉页脚不计，表格按单元格文本计）。
     * <p>
     * 它和 {@link #isEmpty()} 是两回事，而这个区别决定了这份文档到底能不能用：扫描件解析后
     * {@code items} 里全是 PICTURE，非空但一个可检索的字都没有（图片节点的文本只是文件名）。
     * 下游分块、向量化、全文索引消费的都是文本，所以「有没有内容」必须按这个口径判 ——
     * 按 {@code isEmpty()} 判会让空壳结果一路走到 READY，并把图片文件名当成正文写进向量库。
     */
    public long indexableTextLength() {
        long total = 0;
        for (NodeItem item : items) {
            DocumentKind kind = item.getKind();
            if (kind == DocumentKind.PICTURE || kind == DocumentKind.PAGE_HEADER
                    || kind == DocumentKind.PAGE_FOOTER) {
                continue;
            }
            if (item instanceof NodeItem.TableItem table) {
                for (NodeItem.TableCell cell : table.getCells()) {
                    total += cell.getText() == null ? 0 : cell.getText().length();
                }
            }
            total += item.getText() == null ? 0 : item.getText().length();
        }
        return total;
    }

    /** 是否至少有一个字能被检索到 */
    public boolean hasIndexableText() {
        return indexableTextLength() > 0;
    }

    // ==================== 导出便捷方法 ====================

    /** 导出 Markdown（文档正文存储与整体预览用） */
    public String toMarkdown() {
        return new MarkdownExporter().export(this);
    }

    /** 导出纯文本（兼容旧的字符/正则/Markdown 分割器） */
    public String toText() {
        return new TextExporter().export(this);
    }

    /** 导出 HTML（前端富预览/下载用） */
    public String toHtml() {
        return new HtmlExporter().export(this);
    }

    /** 导出 JSON dict（缓存落库与远程传输格式） */
    public String toJson() {
        return JsonExporter.toJson(this);
    }

    /**
     * 从 JSON dict 还原（缓存命中时走这里，不再触发解析）。
     */
    public static QunxuDocument fromJson(String json) {
        return JsonExporter.parse(json);
    }

    /**
     * 按导出器 key 导出（markdown/html/json/text 或自定义实现）。
     * <p>
     * 走内置静态注册表；在 Spring 环境下若需使用自定义注册的导出器，请调
     * {@code DocumentConverter.export(document, key)}。
     */
    public String exportAs(String exporterKey) {
        return tech.qunxu.module.document.export.Exporters.export(this, exporterKey);
    }

    /** 导出 Markdown（等价 {@code exportAs("markdown")}） */
    public String exportAsMarkdown() {
        return toMarkdown();
    }

    /** 导出纯文本（等价 {@code exportAs("text")}） */
    public String exportAsText() {
        return toText();
    }

    /** 导出 HTML（等价 {@code exportAs("html")}） */
    public String exportAsHtml() {
        return toHtml();
    }

    public DocumentMeta getMeta() {
        return meta;
    }

    public void setMeta(DocumentMeta meta) {
        this.meta = meta == null ? new DocumentMeta() : meta;
    }

    public List<PageInfo> getPages() {
        return pages;
    }

    public List<NodeItem> getItems() {
        return items;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}

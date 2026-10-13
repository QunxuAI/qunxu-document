package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构分块器（新默认，对应 docling 的 {@code HierarchicalChunker}）。
 * <p>
 * 沿章节树做「文档顺序 + 贪心装填」：同一小节内的段落/列表/代码持续装进一块，装满预算才收尾，
 * 因此不会像「每节一块」那样把一个小标题下的三行文字切成一个碎片块。
 * 只有顶层标题（level 1）才是硬边界 —— 跨章的块对检索是负资产。
 * 两类元素走原子通道：
 * <ul>
 *   <li>{@code TABLE} 超预算时交给 {@link TableRowChunker#groupRows} 按行再切（每块重复表头）；</li>
 *   <li>{@code PICTURE} 独立成块（markdown 图片语法 + alt），避免图片说明被正文淹没。</li>
 * </ul>
 * 单个元素本身就超预算时按字符滑窗兜底，步长 {@code max(1, chunkSize - overlapSize)}，
 * 保证起点始终前进（这条约束来自兼容分块的死循环教训）。
 * <p>
 * 每块块首注入标题面包屑（{@code > A / B / C}），使脱离原文的块仍带章节语义；
 * 可用 {@link ChunkOptions#isIncludeHeadingBreadcrumb()} 关闭，关闭后溯源字段 {@code headingPath} 仍写。
 * <p>
 * 页眉页脚（{@code PAGE_HEADER} / {@code PAGE_FOOTER}）不参与分块：它们是每页重复的装饰文本，
 * 进块只会污染向量。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class HierarchicalChunker implements BaseChunker {

    /** 分块器标识（新默认分块器） */
    public static final String KEY = "hierarchical";

    /** 面包屑分隔符（与前端分段预览展示的层级路径一致） */
    private static final String PATH_SEPARATOR = " / ";

    private final TokenCounter tokens;

    public HierarchicalChunker() {
        this(new JTokkitTokenCounter());
    }

    public HierarchicalChunker(TokenCounter tokens) {
        this.tokens = tokens == null ? new JTokkitTokenCounter() : tokens;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "结构分块（推荐）";
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 自动选型：有标题层级才用结构分块 —— 没有标题时既没章节边界也没面包屑，
     * 它会退化成一串无上下文的等长块，不如让位给字符窗口分块。
     * 纯表格文档（xlsx/csv）也让位给 {@link TableRowChunker}：它的「每块重复表头」比章节装填更适合表格召回。
     */
    @Override
    public boolean prefers(QunxuDocument document) {
        if (document == null) {
            return false;
        }
        return !document.isPureTable() && document.headingCount() > 0;
    }

    @Override
    public List<Chunk> chunk(QunxuDocument document, ChunkOptions options) {
        List<Chunk> chunks = new ArrayList<>();
        if (document == null || document.isEmpty()) {
            return chunks;
        }
        ChunkOptions opts = options == null ? ChunkOptions.defaults() : options;
        int budget = Math.min(opts.targetTokens(), opts.ceilingTokens());
        String[] path = new String[7];
        Buffer buffer = null;

        for (NodeItem item : document.getItems()) {
            DocumentKind kind = item.getKind();
            if (kind == null || kind == DocumentKind.PAGE_HEADER || kind == DocumentKind.PAGE_FOOTER) {
                continue;
            }
            if (item.isHeading()) {
                int level = item instanceof NodeItem.HeadingItem heading ? heading.getLevel() : 1;
                if (level <= 1 && buffer != null && buffer.hasBody()) {
                    // 顶层章节边界：先收尾上一章，新章从头装起（只有标题时不收，避免空心块）
                    chunks.addAll(buffer.flush(opts, tokens));
                    buffer = null;
                }
                setPath(path, level, item.getText());
            }
            List<String> section = snapshot(path);

            if (item instanceof NodeItem.TableItem table) {
                String prefix = breadcrumb(section, opts);
                List<Integer> carry = List.of();
                if (buffer != null && buffer.hasContent() && !buffer.hasBody()) {
                    // 缓冲里只有标题行：并进表格块，否则标题与表格会各成一块、内容重复
                    prefix += buffer.content().stripTrailing() + "\n\n";
                    carry = buffer.elements();
                    buffer = null;
                } else {
                    chunks.addAll(close(buffer, opts));
                    buffer = null;
                }
                List<Chunk> produced = TableRowChunker.groupRows(table, prefix + renderCaption(table.getCaption()),
                        section, opts, tokens, table.pageIndex(), table.getIndex());
                attach(produced, carry);
                chunks.addAll(produced);
                continue;
            }
            if (item instanceof NodeItem.PictureItem picture) {
                String prefix = breadcrumb(section, opts);
                List<Integer> carry = List.of();
                if (buffer != null && buffer.hasContent() && !buffer.hasBody()) {
                    prefix += buffer.content().stripTrailing() + "\n\n";
                    carry = buffer.elements();
                    buffer = null;
                } else {
                    chunks.addAll(close(buffer, opts));
                    buffer = null;
                }
                Chunk single = renderPicture(picture, section, prefix, opts);
                if (single != null) {
                    if (!carry.isEmpty()) {
                        attach(List.of(single), carry);
                    }
                    chunks.add(single);
                }
                continue;
            }

            String text = renderItem(item);
            if (text.isEmpty()) {
                continue;
            }
            int crumbTokens = tokens.count(breadcrumb(section, opts));
            int itemTokens = tokens.count(text);
            if (buffer == null) {
                buffer = new Buffer(section);
            }
            if (buffer.hasContent() && crumbTokens + buffer.bodyTokens + itemTokens > budget) {
                chunks.addAll(buffer.flush(opts, tokens));
                buffer = new Buffer(section);
            }
            if (!buffer.hasContent() && crumbTokens + itemTokens > budget) {
                // 单元素超预算：滑窗切分，期间不混入其它元素，保持块内语义连续
                chunks.addAll(slidingWindow(text, item, section, opts));
                continue;
            }
            buffer.append(text, itemTokens, item);
        }
        chunks.addAll(close(buffer, opts));
        mergeTinyTail(chunks, opts);
        return chunks;
    }

    // ==================== 装块细节 ====================

    private List<Chunk> close(Buffer buffer, ChunkOptions opts) {
        List<Chunk> chunks = new ArrayList<>();
        if (buffer != null && buffer.hasContent()) {
            chunks.addAll(buffer.flush(opts, tokens));
        }
        return chunks;
    }

    /** 单个超长元素的字符滑窗（窗口/步长与兼容分块的 chars 策略同口径） */
    private List<Chunk> slidingWindow(String text, NodeItem item, List<String> section, ChunkOptions opts) {
        List<Chunk> chunks = new ArrayList<>();
        String body = text.stripTrailing();
        String crumb = breadcrumb(section, opts);
        int window = Math.max(1, opts.charWindow());
        int step = Math.max(1, opts.charStep());
        List<Integer> elements = List.of(item.getIndex());
        for (int start = 0; start < body.length(); start += step) {
            int end = Math.min(body.length(), start + window);
            chunks.add(buildChunk(crumb + body.substring(start, end), section, item.getKind(),
                    item.pageIndex(), item.pageIndex(), elements, opts, tokens));
            if (end == body.length()) {
                break;
            }
        }
        return chunks;
    }

    /** 图片独立成块：优先 markdown 图片语法，未落地（noop sink / 被过滤）时退化为 alt 文本 */
    private Chunk renderPicture(NodeItem.PictureItem picture, List<String> section, String prefix,
                                ChunkOptions opts) {
        String alt = picture.getText() == null ? "" : picture.getText().trim();
        ImageRef ref = picture.getImage();
        String url = ref == null || ref.url() == null ? "" : ref.url().trim();
        StringBuilder sb = new StringBuilder(prefix);
        if (!url.isEmpty()) {
            sb.append("![").append(alt.replace("[", "").replace("]", "")).append("](").append(url).append(")");
        } else {
            sb.append(alt);
        }
        if (sb.toString().isBlank()) {
            return null;
        }
        Chunk chunk = buildChunk(sb.toString().strip(), section, DocumentKind.PICTURE,
                picture.pageIndex(), picture.pageIndex(), List.of(picture.getIndex()), opts, tokens);
        if (!url.isEmpty()) {
            chunk.getImageUrls().add(url);
        }
        return chunk;
    }

    /** 尾巴太短（检索命中不到）就并入前一块，前提是并完仍不超硬上限 */
    private void mergeTinyTail(List<Chunk> chunks, ChunkOptions opts) {
        int minTokens = opts.getMinChunkTokens();
        if (minTokens <= 0 || chunks.size() < 2) {
            return;
        }
        Chunk last = chunks.get(chunks.size() - 1);
        Chunk prev = chunks.get(chunks.size() - 2);
        if (last.getTokenCount() >= minTokens
                || prev.getTokenCount() + last.getTokenCount() > opts.ceilingTokens()) {
            return;
        }
        prev.setText(prev.getText().stripTrailing() + "\n\n" + last.getText().strip());
        prev.setTokenCount(tokens.count(prev.getText()));
        prev.mergeProvenance(last);
        chunks.remove(chunks.size() - 1);
    }

    /** 把被并入的标题节点下标补到已有块上（溯源不能因为合块而丢节点） */
    private static void attach(List<Chunk> chunks, List<Integer> elements) {
        if (elements.isEmpty()) {
            return;
        }
        for (Chunk chunk : chunks) {
            for (Integer index : elements) {
                if (!chunk.getElementIndexes().contains(index)) {
                    chunk.element(index);
                }
            }
        }
    }

    private static Chunk buildChunk(String text, List<String> section, DocumentKind kind,
                                    int pageStart, int pageEnd, List<Integer> elements,
                                    ChunkOptions opts, TokenCounter tokens) {
        String cut = TableRowChunker.truncateToBudget(text, tokens, opts.ceilingTokens());
        Chunk chunk = Chunk.of(cut, kind == null ? DocumentKind.TEXT_BLOCK : kind);
        chunk.pages(pageStart, pageEnd);
        for (Integer index : elements) {
            chunk.element(index);
        }
        chunk.getHeadingPath().addAll(section);
        chunk.setTokenCount(tokens.count(cut));
        return chunk;
    }

    // ==================== 文本渲染 ====================

    /** 把节点渲染为进入块文本的 Markdown 片段（表格/图片走原子通道，不在此处理） */
    private static String renderItem(NodeItem item) {
        DocumentKind kind = item.getKind();
        String text = item.getText();
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case TITLE -> heading(text, 1);
            case SECTION_HEADER -> heading(text, item instanceof NodeItem.HeadingItem heading ? heading.getLevel() : 1);
            case SHEET -> "### Sheet: " + safe(text) + "\n\n";
            case LIST_ITEM -> {
                String marker = item instanceof NodeItem.ListItem list && list.getMarker() != null
                        && !list.getMarker().isBlank() ? list.getMarker() : "-";
                yield safe(text).isEmpty() ? "" : marker + " " + safe(text) + "\n";
            }
            case CODE -> {
                String language = item instanceof NodeItem.CodeItem code && code.getLanguage() != null
                        ? code.getLanguage().trim() : "";
                yield safe(text).isEmpty() ? "" : "```" + language + "\n" + safe(text) + "\n```\n\n";
            }
            case FORMULA -> safe(text).isEmpty() ? "" : "$$" + safe(text) + "$$\n\n";
            default -> safe(text).isEmpty() ? "" : safe(text) + "\n\n";
        };
    }

    private static String heading(String text, int level) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return "#".repeat(Math.min(6, Math.max(1, level))) + " " + text.trim() + "\n\n";
    }

    private static String renderCaption(String caption) {
        return caption == null || caption.isBlank() ? "" : caption.trim() + "\n\n";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** 面包屑：标题栈快照转成 {@code > A / B / C} 前缀（关闭时返回空串） */
    private static String breadcrumb(List<String> section, ChunkOptions opts) {
        if (!opts.isIncludeHeadingBreadcrumb() || section.isEmpty()) {
            return "";
        }
        return "> " + String.join(PATH_SEPARATOR, section) + "\n\n";
    }

    private static void setPath(String[] path, int level, String text) {
        int index = Math.min(6, Math.max(1, level));
        if (text == null || text.isBlank()) {
            return;
        }
        path[index] = text.trim();
        for (int deeper = index + 1; deeper <= 6; deeper++) {
            path[deeper] = null;
        }
    }

    /** 标题栈快照转成有序路径 */
    private static List<String> snapshot(String[] path) {
        List<String> result = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            if (path[level] != null) {
                result.add(path[level]);
            }
        }
        return result;
    }

    // ==================== 缓冲区 ====================

    /** 正在装填的一块：正文、创建时的标题栈快照、页码与来源节点 */
    private static final class Buffer {

        private final StringBuilder body = new StringBuilder();
        private final List<String> section;
        private final List<Integer> elements = new ArrayList<>();
        private int bodyTokens;
        private int pageStart;
        private int pageEnd;
        private boolean hasBody;
        private DocumentKind kind;

        private Buffer(List<String> section) {
            this.section = section;
        }

        private boolean hasContent() {
            return body.length() > 0;
        }

        /** 是否装进了标题以外的内容：只有标题时不该单独成块，要并给紧随其后的表格/图片 */
        private boolean hasBody() {
            return hasBody;
        }

        private String content() {
            return body.toString();
        }

        private List<Integer> elements() {
            return elements;
        }

        private void append(String text, int itemTokens, NodeItem item) {
            body.append(text);
            bodyTokens += itemTokens;
            elements.add(item.getIndex());
            if (!item.isHeading()) {
                hasBody = true;
            }
            DocumentKind itemKind = item.getKind();
            if (kind == null) {
                kind = itemKind;
            }
            int page = item.pageIndex();
            if (page > 0) {
                if (pageStart == 0) {
                    pageStart = page;
                }
                pageEnd = Math.max(pageEnd, page);
            }
        }

        /**
         * 生成块文本。
         * <p>
         * 装填判定用的是「分段 token 之和」，而 tokenizer 对拼接结果可能略大（次可加性的反例），
         * 因此拼上面包屑后仍可能顶到硬上限 —— 此时按字符窗口切成多块，
         * 而不是截断丢内容。
         */
        private List<Chunk> flush(ChunkOptions opts, TokenCounter tokens) {
            List<Chunk> chunks = new ArrayList<>();
            String prefix = breadcrumb(section, opts);
            String content = body.toString().stripTrailing();
            if (content.isEmpty()) {
                return chunks;
            }
            DocumentKind effective = kind == null ? DocumentKind.TEXT_BLOCK : kind;
            if (tokens.count(prefix + content) <= opts.ceilingTokens()) {
                chunks.add(buildChunk(prefix + content, section, effective, pageStart, pageEnd,
                        elements, opts, tokens));
                return chunks;
            }
            int window = Math.max(1, opts.charWindow());
            int step = Math.max(1, opts.charStep());
            for (int start = 0; start < content.length(); start += step) {
                int end = Math.min(content.length(), start + window);
                chunks.add(buildChunk(prefix + content.substring(start, end), section, effective,
                        pageStart, pageEnd, elements, opts, tokens));
                if (end == content.length()) {
                    break;
                }
            }
            return chunks;
        }
    }
}

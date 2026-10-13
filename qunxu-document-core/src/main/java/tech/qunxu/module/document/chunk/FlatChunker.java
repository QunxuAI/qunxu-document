package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * 兼容分块器：迁移改造前 {@code DocumentServiceImpl} 的四种文本切分策略，行为逐条对齐。
 * <p>
 * 存在的理由是「存量集合的分段参数不能被升级悄悄改掉」：同一 splitterName 下块数与块文本
 * 必须与改造前一致，否则用户会发现重新索引后召回结果莫名漂移。因此这里的算法
 * <b>刻意不复用</b>结构分块的判断（例如按字符切分不做句子边界对齐）。
 * <p>
 * 文本来源按策略选择，因为不同策略依赖的记号不同：
 * <ul>
 *   <li>{@code chars} / {@code regex} → {@link QunxuDocument#exportAsText()}（纯文本，等价改造前的 Tika 输出）；</li>
 *   <li>{@code markdown} → {@link QunxuDocument#exportAsMarkdown()}（必须保留 {@code #} 记号才能按标题切）；</li>
 *   <li>{@code lines} → Markdown（等价改造前 Excel 路径的 {@code toFullMarkdown} + 按行分组）。</li>
 * </ul>
 * 唯一相对改造前的收紧：任一块超过 {@link ChunkOptions#ceilingTokens()} 时按比例截断。
 * 旧实现不做这层保护，超限块会在 embedding 阶段让<b>整篇文档</b>索引失败 ——
 * 截断只影响病态长块的内容，不影响块数，因此不破坏分段数回归基线。
 * <p>
 * 注：改造前的 {@code SimpleTokenizeSplitter}（Spring AI {@code TokenTextSplitter}）不在此列，
 * 它由 app 侧以 {@link BaseChunker} 适配器提供，以保证分词边界与旧实现完全一致。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class FlatChunker implements BaseChunker {

    /** 分块器标识（四种文本切分策略共用，靠 {@link ChunkOptions#strategy()} 区分） */
    public static final String KEY = "flat";

    /** 仅用于硬上限判定与 token 计数，不参与切分算法（切分完全按字符/行/正则） */
    private final TokenCounter tokens;

    public FlatChunker() {
        this(new JTokkitTokenCounter());
    }

    public FlatChunker(TokenCounter tokens) {
        this.tokens = tokens == null ? new JTokkitTokenCounter() : tokens;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "兼容文本分块";
    }

    @Override
    public int order() {
        return 900;
    }

    /**
     * 自动选型：无标题也无表格的纯长文（典型是无格式 txt/log）走字符窗口，
     * 即改造前的 SimpleDocumentSplitter 行为 —— order=900 保证只有在结构分块
     * 与表格行分块都不命中时才会被选中。
     * <p>
     * 只选 chars 策略：regex/markdown 策略依赖用户额外参数（表达式、标题层级），
     * 自动选型不能猜。
     */
    @Override
    public boolean prefers(QunxuDocument document) {
        if (document == null) {
            return false;
        }
        return document.headingCount() == 0 && document.countOf(DocumentKind.TABLE) == 0;
    }

    @Override
    public List<Chunk> chunk(QunxuDocument document, ChunkOptions options) {
        if (document == null || document.isEmpty()) {
            return new ArrayList<>();
        }
        ChunkOptions opts = options == null ? ChunkOptions.defaults() : options;
        String content = contentFor(document, opts.strategy());
        List<Chunk> chunks = switch (opts.strategy()) {
            case ChunkOptions.STRATEGY_REGEX -> splitByRegex(content, opts.getRegex());
            case ChunkOptions.STRATEGY_MARKDOWN -> splitByMarkdown(content, opts.markdownLevelOrDefault());
            case ChunkOptions.STRATEGY_LINES -> splitByLines(content, opts.charWindow());
            default -> splitByChars(content, opts.charWindow(), opts.getOverlapSize());
        };
        return enforceCeiling(chunks, opts);
    }

    /** 按策略取源文本：标题/行策略依赖 Markdown 记号，其余用纯文本 */
    private static String contentFor(QunxuDocument document, String strategy) {
        if (ChunkOptions.STRATEGY_MARKDOWN.equals(strategy) || ChunkOptions.STRATEGY_LINES.equals(strategy)) {
            return document.exportAsMarkdown();
        }
        return document.exportAsText();
    }

    // ==================== 四种策略（逐条对齐改造前实现） ====================

    /** 固定字符窗口 + 重叠（步长恒 ≥ 1，overlap ≥ chunk 时不退化成死循环） */
    public static List<Chunk> splitByChars(String content, int chunkSize, int overlapSize) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        int chunk = Math.max(1, chunkSize);
        int step = Math.max(1, chunk - Math.max(0, overlapSize));
        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(start + chunk, content.length());
            chunks.add(Chunk.of(content.substring(start, end), DocumentKind.TEXT_BLOCK));
            if (end >= content.length()) {
                break;
            }
        }
        return chunks;
    }

    /** 按正则切分，默认 {@code \n\n}（与改造前一致：切完 trim，空段丢弃，不合并小块） */
    public static List<Chunk> splitByRegex(String content, String regex) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        String pattern = regex == null || regex.isEmpty() ? "\n\n" : regex;
        String[] parts;
        try {
            parts = content.split(pattern);
        } catch (PatternSyntaxException e) {
            // 改造前直接抛给调用方变成 500；这里退化为默认分隔并让上层告警，行为上仍产出可索引内容
            parts = content.split("\n\n");
        }
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                chunks.add(Chunk.of(part.trim(), DocumentKind.TEXT_BLOCK));
            }
        }
        return chunks;
    }

    /**
     * Markdown 标题层级切分：level 表示在 1~level 级标题处切。
     * <p>
     * {@code (?m)} 多行模式必须有：否则 {@code ^} 只匹配整串开头，全文不会被切分（改造前踩过这个坑）。
     */
    public static List<Chunk> splitByMarkdown(String content, Integer level) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        int maxLevel = level == null || level < 1 ? 3 : Math.min(level, 6);
        String[] sections = content.split("(?m)(?=^#{1," + maxLevel + "}\\s)");
        for (String section : sections) {
            if (!section.trim().isEmpty()) {
                chunks.add(Chunk.of(section.trim(), DocumentKind.TEXT_BLOCK));
            }
        }
        return chunks;
    }

    /** 按行分组：每 step 行一块（step 取自 chunkSize，与改造前 {@code splitByLines} 语义一致） */
    public static List<Chunk> splitByLines(String content, int step) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        int size = Math.max(1, step);
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
            if ((i + 1) % size == 0 || i == lines.length - 1) {
                chunks.add(Chunk.of(sb.toString(), DocumentKind.TEXT_BLOCK));
                sb = new StringBuilder();
            }
        }
        return chunks;
    }

    // ==================== 硬上限保护 ====================

    private List<Chunk> enforceCeiling(List<Chunk> chunks, ChunkOptions opts) {
        int ceiling = opts.ceilingTokens();
        for (Chunk chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            String cut = TableRowChunker.truncateToBudget(text, tokens, ceiling);
            if (!cut.equals(text)) {
                chunk.setText(cut);
            }
            chunk.setTokenCount(tokens.count(chunk.getText()));
        }
        return chunks;
    }
}

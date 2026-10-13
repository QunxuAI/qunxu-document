package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.QunxuDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分块器注册表：把「用户/集合里存的 splitterName」解析成具体的 {@link BaseChunker}。
 * <p>
 * 需要这层映射是因为存量知识库里存的是改造前的分割器类名字符串，升级不能要求用户重选分割器：
 * 名字必须继续可用，且分段结果不变。映射规则见 {@code ALIASES}。
 * <p>
 * 同时承担「按文档结构自动选型」：名字为 {@link #AUTO_NAME}（或空）时，先看谁的
 * {@link BaseChunker#prefers} 命中（命中者按 order 取最小），无命中则用结构分块。
 * 因此 {@code AutoChunker} 故意不进了 {@code ALIASES} —— 一旦进了别名表，
 * {@link #resolve} 就会命中它，自动选型反而不会发生。
 * <p>
 * 宿主应用新增的 {@link BaseChunker} Bean 会被本表自动收集（L1 扩展），无需改模块。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ChunkerRegistry {

    /** 新默认分块器 */
    public static final String DEFAULT_KEY = HierarchicalChunker.KEY;

    /**
     * 自动选型哨兵名字：不指定具体分块器，按文档结构挑（见 {@link BaseChunker#prefers}）。
     * 当前规则：有标题→结构分块；纯表格→表格行分块；无标题且无表格的长文本→字符窗口。
     */
    public static final String AUTO_NAME = "AutoChunker";

    /**
     * 兼容别名：旧 splitterName → {@code chunkerKey} 或 {@code chunkerKey:strategy}。
     * 带 strategy 的表示「同一个 FlatChunker，不同切法」，避免为四种文本策略各开一个类。
     */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("simpledocumentsplitter", FlatChunker.KEY + ":" + ChunkOptions.STRATEGY_CHARS);
        ALIASES.put("regexdocumentsplitter", FlatChunker.KEY + ":" + ChunkOptions.STRATEGY_REGEX);
        ALIASES.put("markdownheadersplitter", FlatChunker.KEY + ":" + ChunkOptions.STRATEGY_MARKDOWN);
        ALIASES.put("exceldocumentsplitter", TableRowChunker.KEY);
        ALIASES.put("hierarchicalchunker", HierarchicalChunker.KEY);
        ALIASES.put("tablerowchunker", TableRowChunker.KEY);
        ALIASES.put("flatchunker", FlatChunker.KEY);
    }

    private final Map<String, BaseChunker> chunkersByKey = new LinkedHashMap<>();

    public ChunkerRegistry(List<BaseChunker> chunkers) {
        if (chunkers != null) {
            List<BaseChunker> sorted = new ArrayList<>(chunkers);
            sorted.sort(Comparator.comparingInt(BaseChunker::order));
            sorted.forEach(chunker -> {
                if (chunker != null && chunker.key() != null && !chunker.key().isBlank()) {
                    chunkersByKey.putIfAbsent(chunker.key().trim(), chunker);
                }
            });
        }
    }

    /** 已注册的分块器标识 */
    public List<String> keys() {
        return new ArrayList<>(chunkersByKey.keySet());
    }

    /** 按 key 取分块器，未注册返回 null */
    public BaseChunker find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return chunkersByKey.get(key.trim());
    }

    /** 是否为自动选型（留空与显式传 {@link #AUTO_NAME} 同义） */
    public static boolean isAuto(String splitterName) {
        if (splitterName == null || splitterName.isBlank()) {
            return true;
        }
        String name = splitterName.trim().toLowerCase(Locale.ROOT);
        return "auto".equals(name) || "autochunker".equals(name);
    }

    /** 解析 splitterName（兼容别名 + 大小写不敏感），无法识别返回 null */
    public BaseChunker resolve(String splitterName) {
        if (isAuto(splitterName)) {
            // 自动选型不是某个具体分块器的名字，必须走 prefers 路径
            return null;
        }
        String direct = splitterName.trim();
        if (chunkersByKey.containsKey(direct)) {
            return chunkersByKey.get(direct);
        }
        String alias = direct == null ? null : ALIASES.get(direct.toLowerCase(Locale.ROOT));
        if (alias == null) {
            return null;
        }
        return chunkersByKey.get(alias.split(":", 2)[0]);
    }

    /** 别名里带的策略（无则 null，表示分块器自带默认） */
    public String strategyOf(String splitterName) {
        if (splitterName == null || splitterName.isBlank()) {
            return null;
        }
        String alias = ALIASES.get(splitterName.trim().toLowerCase(Locale.ROOT));
        if (alias == null || !alias.contains(":")) {
            return null;
        }
        return alias.substring(alias.indexOf(':') + 1);
    }

    /**
     * 选定分块器：名字能解析就用它；名字为自动哨兵（或空）/未知时按文档结构自动选型。
     * <p>
     * 未知名字不报错误退自动选型，是为了让宿主新增的 splitterName（如 app 侧注册的 token 适配器）
     * 在同一入口下自然命中；调用方需要严格校验时可先用 {@link #resolve} 判空。
     */
    public BaseChunker select(String splitterName, QunxuDocument document) {
        BaseChunker named = resolve(splitterName);
        if (named != null) {
            return named;
        }
        return chunkersByKey.values().stream()
                .filter(chunker -> chunker.prefers(document))
                .min(Comparator.comparingInt(BaseChunker::order))
                .orElseGet(() -> {
                    BaseChunker fallback = find(DEFAULT_KEY);
                    return fallback != null ? fallback
                            : chunkersByKey.values().stream().findFirst().orElse(null);
                });
    }

    /**
     * 分块器 + 策略 → 对外可见的分割器名字。
     * <p>
     * 用旧分割器类名而不是模块内部 key（{@code hierarchical}/{@code flat:chars}）：
     * 前端下拉与 OpenAPI 已经认这套名字，回显「本次实际用了哪个」不需要再造一层词表。
     * 宿主自定义分块器直接用自己的 key（如 app 侧的 {@code SimpleTokenizeSplitter}）。
     */
    public String publicName(BaseChunker chunker, String strategy) {
        if (chunker == null) {
            return null;
        }
        String key = chunker.key();
        if (FlatChunker.KEY.equals(key)) {
            String st = strategy == null || strategy.isBlank()
                    ? ChunkOptions.STRATEGY_CHARS : strategy.trim().toLowerCase(Locale.ROOT);
            return switch (st) {
                case ChunkOptions.STRATEGY_REGEX -> "RegexDocumentSplitter";
                case ChunkOptions.STRATEGY_MARKDOWN -> "MarkdownHeaderSplitter";
                default -> "SimpleDocumentSplitter";
            };
        }
        if (TableRowChunker.KEY.equals(key)) {
            return "ExcelDocumentSplitter";
        }
        if (HierarchicalChunker.KEY.equals(key)) {
            return "HierarchicalChunker";
        }
        return key;
    }

    /** 选型结果的对外名字（供「自动选择」回显实际分块器） */
    public String selectName(String splitterName, QunxuDocument document) {
        return publicName(select(splitterName, document), strategyOf(splitterName));
    }

    /**
     * 分块：按 splitterName 取分块器、把别名带的策略写进选项副本后执行。
     *
     * @return 分块列表；没有可用分块器时返回空列表（调用方据此报「分段失败」而不是 NPE）
     */
    public List<Chunk> chunk(String splitterName, QunxuDocument document, ChunkOptions options) {
        BaseChunker chunker = select(splitterName, document);
        if (chunker == null) {
            return new ArrayList<>();
        }
        ChunkOptions effective = options == null ? ChunkOptions.defaults() : options.copy();
        String strategy = strategyOf(splitterName);
        if (strategy != null) {
            effective.strategy(strategy);
        }
        List<Chunk> chunks = chunker.chunk(document, effective);
        return chunks == null ? new ArrayList<>() : chunks;
    }
}

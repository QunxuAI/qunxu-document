package tech.qunxu.module.document.api;

import tech.qunxu.module.document.backend.DocumentBackend;
import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.cache.ConversionCacheStore;
import tech.qunxu.module.document.cache.ConversionKey;
import tech.qunxu.module.document.cache.SingleFlight;
import tech.qunxu.module.document.export.DocumentExporter;
import tech.qunxu.module.document.image.ImageExtractor;
import tech.qunxu.module.document.image.ImageFilter;
import tech.qunxu.module.document.image.ImageSink;
import tech.qunxu.module.document.model.DocumentMeta;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.post.DocumentPostProcessor;
import tech.qunxu.module.document.source.DocumentSource;
import tech.qunxu.module.document.support.DocumentFormatDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 唯一转换入口（对应 docling 的 {@code DocumentConverter}）：按格式路由到 Backend，产出统一中间格式。
 * <p>
 * <b>没有 pipeline 编排</b>：docling 的 pipeline 抽象的是「进程内多个可独立开关的 ML 推理阶段」
 * （layout → TableFormer → OCR → 阅读顺序），而本轮所有 Backend 都是对结构化容器的单趟确定性抽取，
 * 没有第二阶段可编排。格式差异全部下沉到 {@link DocumentBackend}，顶层只有一次路由；
 * 需要「解析后再补信息」时走 {@link DocumentPostProcessor} 有序链，需要「单个 Backend 内部串多阶段」时
 * 由该 Backend 自己拆 Stage，<b>不外露</b>（详见 docs/document-module.md）。
 * <p>
 * 路由优先级（高 → 低）：
 * <ol>
 *   <li>{@link ConvertOptions#getForceBackend()} —— 调用方指定；</li>
 *   <li>{@code qunxu.document.backends.preferred.<format>} —— 配置切换实现（L2）；</li>
 *   <li>注册表中 {@code order()} 最小且 {@code supports()} 的 Backend；</li>
 *   <li>解析失败时的降级链：同格式的其他实现按 {@code order()} 依次重试 →
 *       {@code tika-fallback} 最后兜底（仍失败则 {@link ConversionStatus#FAILURE}）。</li>
 * </ol>
 * <b>三级自定义扩展</b>（L1 编译期 SPI Bean / L2 配置 preferred / L3 运行时 {@link #register}）
 * 见 {@link DocumentBackend} 的类注释与 {@link DocumentConverterCustomizer}。
 * <p>
 * 线程安全：Backend 与 Exporter 实例无状态，注册表用 {@link CopyOnWriteArrayList} /
 * {@link ConcurrentHashMap}；解析缓存按 {@link ConversionKey} 命中，同键并发由 {@link SingleFlight} 合流。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);

    /** Tika 兜底解析器的 key（内置实现约定值，用于最终降级） */
    public static final String FALLBACK_BACKEND_KEY = "tika-fallback";

    private final List<DocumentBackend> backends = new CopyOnWriteArrayList<>();
    private final Map<String, DocumentBackend> backendsByKey = new ConcurrentHashMap<>();
    private final Map<String, DocumentExporter> exportersByKey = new ConcurrentHashMap<>();
    private final List<DocumentPostProcessor> postProcessors = new CopyOnWriteArrayList<>();

    /** 格式 → 首选 backend key（L2 配置） */
    private final Map<String, String> preferredBackends = new ConcurrentHashMap<>();

    private final ImageSink imageSink;
    private final ImageFilter imageFilter;
    /** 部署级图片开关（{@code qunxu.document.image.enabled}），关闭时任何 options 都不再抽图 */
    private final boolean imageExtractionEnabled;
    private final DocumentFormatDetector detector;
    private final ConversionCacheStore cacheStore;
    private final boolean cacheEnabled;
    private final SingleFlight singleFlight = new SingleFlight();

    public DocumentConverter(List<DocumentBackend> backends,
                             List<DocumentExporter> exporters,
                             List<DocumentPostProcessor> postProcessors,
                             ImageSink imageSink,
                             ImageFilter imageFilter,
                             DocumentFormatDetector detector,
                             ConversionCacheStore cacheStore,
                             boolean cacheEnabled,
                             Map<String, String> preferredBackends) {
        this(backends, exporters, postProcessors, imageSink, imageFilter, detector, cacheStore,
                cacheEnabled, preferredBackends, true);
    }

    public DocumentConverter(List<DocumentBackend> backends,
                             List<DocumentExporter> exporters,
                             List<DocumentPostProcessor> postProcessors,
                             ImageSink imageSink,
                             ImageFilter imageFilter,
                             DocumentFormatDetector detector,
                             ConversionCacheStore cacheStore,
                             boolean cacheEnabled,
                             Map<String, String> preferredBackends,
                             boolean imageExtractionEnabled) {
        if (backends != null) {
            backends.forEach(this::register);
        }
        if (exporters != null) {
            exporters.forEach(this::registerExporter);
        }
        if (postProcessors != null) {
            this.postProcessors.addAll(postProcessors);
            this.postProcessors.sort(Comparator.comparingInt(DocumentPostProcessor::order));
        }
        this.imageSink = imageSink;
        this.imageFilter = imageFilter == null ? new ImageFilter() : imageFilter;
        this.imageExtractionEnabled = imageExtractionEnabled;
        this.detector = detector == null ? new DocumentFormatDetector() : detector;
        this.cacheStore = cacheStore;
        this.cacheEnabled = cacheEnabled && cacheStore != null;
        if (preferredBackends != null) {
            preferredBackends.forEach((format, key) -> {
                if (format != null && key != null) {
                    this.preferredBackends.put(format.trim().toLowerCase(Locale.ROOT), key.trim());
                }
            });
        }
        log.info("文档转换模块就绪：backend={}，exporter={}，postProcessor={}，cache={}，images={}",
                this.backendsByKey.size(), exportersByKey.size(), this.postProcessors.size(),
                cacheEnabled ? "on" : "off", imageExtractionEnabled ? "on" : "off");
    }

    // ==================== 转换 ====================

    /** 用默认选项转换 */
    public ConversionResult convert(DocumentSource source) {
        return convert(source, ConvertOptions.defaults());
    }

    /**
     * 转换一个输入源。
     *
     * @param source  本地流 / 字节 / URL 输入
     * @param options 解析选项（含 {@code forceReparse} 与 {@code forceBackend}）
     * @return 结果，永不为 null；失败时看 {@link ConversionResult#getErrors()}
     */
    public ConversionResult convert(DocumentSource source, ConvertOptions options) {
        if (source == null) {
            return ConversionResult.failure(null, "输入源为空");
        }
        ConvertOptions effective = options == null ? ConvertOptions.defaults() : options;
        long start = System.currentTimeMillis();
        String fileHash;
        byte[] bytes;
        try {
            bytes = source.bytes();
            fileHash = source.sha256();
        } catch (Exception e) {
            log.warn("读取输入源失败：{}", e.getMessage());
            return ConversionResult.failure(null, "读取输入源失败：" + e.getMessage());
        }
        DocumentFormat format = detector.detect(source.name(), bytes);
        DocumentBackend routed;
        try {
            routed = route(format, effective);
        } catch (IllegalStateException e) {
            return ConversionResult.unsupported(effective.getForceBackend(), e.getMessage());
        }
        if (routed == null) {
            return ConversionResult.unsupported(null,
                    "没有可处理 " + format + " 的解析器（文件名：" + source.name() + "）");
        }
        String fingerprint = effective.fingerprint();
        ConversionKey key = ConversionKey.of(fileHash, routed.key(), routed.version(), fingerprint);
        if (useCache(key, effective)) {
            try {
                Optional<QunxuDocument> cached = cacheStore.find(key);
                if (cached.isPresent()) {
                    QunxuDocument document = cached.get();
                    fillMeta(document, routed, 0, source);
                    log.info("解析缓存命中：hash={}，backend={}，items={}",
                            shortHash(fileHash), routed.key(), document.getItems().size());
                    return ConversionResult.cached(document, routed.key());
                }
            } catch (Exception e) {
                log.warn("解析缓存读取失败（退化为重新解析）：{}", e.getMessage());
            }
        }
        // 同内容并发只解析一次（批量导入同一个文件、预览重复点击）
        try {
            return singleFlight.execute(key.canonical(), () -> doParse(source, format, effective, routed, key, start));
        } catch (Exception e) {
            log.error("转换失败：file={}，backend={}", source.name(), routed.key(), e);
            return ConversionResult.failure(routed.key(), "转换失败：" + describe(e));
        }
    }

    private ConversionResult doParse(DocumentSource source, DocumentFormat format, ConvertOptions options,
                                     DocumentBackend routed, ConversionKey key, long start) {
        ImageExtractor images = null;
        if (imageExtractionEnabled && options.isExtractImages() && imageSink != null) {
            images = new ImageExtractor(imageSink, imageFilter, key.fileHash());
        }
        ParseContext context = new ParseContext(source, format, options, images, key.fileHash(), safeSize(source));
        DocumentBackend used = routed;
        QunxuDocument document;
        List<String> errors = new ArrayList<>();
        try {
            document = routed.parse(context);
        } catch (Exception e) {
            log.warn("解析器 {} 处理 {} 失败：{}", routed.key(), format, describe(e));
            errors.add(used.key() + " 解析失败：" + describe(e));
            document = null;
        }
        if (document == null || !document.hasIndexableText()) {
            // 降级链：同格式的其他实现按 order 依次尝试，最后才轮到通用兜底解析器。
            // 触发与接收都按「可索引文本量」而不是「有没有节点」：扫描件会交出一堆 PICTURE 节点，
            // 按 isEmpty() 判会当场认定成功，把只有图片文件名的空壳结果往下递
            for (DocumentBackend fallback : fallbackChain(used, format)) {
                log.warn("降级到兜底解析器 {}（原 {}）", fallback.key(), used.key());
                errors.add(used.key() + (document == null ? " 解析失败，降级到 " : " 无正文，降级到 ")
                        + fallback.key());
                try {
                    QunxuDocument retried = fallback.parse(context);
                    if (retried != null && retried.hasIndexableText()) {
                        document = retried;
                        used = fallback;
                        break;
                    }
                } catch (Exception e) {
                    errors.add(fallback.key() + " 兜底也失败：" + describe(e));
                }
            }
        }
        long parseMs = System.currentTimeMillis() - start;
        if (document == null || document.isEmpty()) {
            ConversionResult result = ConversionResult.failure(used.key(),
                    errors.isEmpty() ? "解析结果为空" : String.join("；", errors));
            result.setParseMs(parseMs);
            return result;
        }
        if (!errors.isEmpty()) {
            errors.forEach(document::warn);
        }
        if (images != null && images.failed() > 0) {
            document.warn("图片落地失败 " + images.failed() + " 张（sink=" + images.sinkKey() + "）");
        }
        document = applyPostProcessors(document, context);
        // 以下判定放在后处理链之后：图片理解（OCR / VLM）就是把文字补到这一步，
        // 先在 Backend 层面判失败会断送后处理把文档救回来的机会
        if (!document.hasIndexableText()) {
            // 有产出但一个可检索的字都没有（扫描件整页位图、纯图形页）：必须判失败而不是静默成功。
            // 否则两件事同时发生：① 图片文件名被当成正文向量化入库（检索时吐出「![0.png](…)」）；
            // ② 这份空壳结果被写进解析缓存长期命中，后续即使接上 OCR 也拿不到新结果
            List<String> reasons = new ArrayList<>(errors);
            // 解析器已报过「无文本层」就把那条原话递出去（带具体格式信息），不另加泛化文案：
            // 这两段重复的话会被 app 侧 describeFailure 全文拼成给用户看的一句话，越长越难读
            document.getWarnings().stream().filter(w -> w.contains("无文本层")).findFirst()
                    .ifPresentOrElse(reasons::add,
                            () -> reasons.add("未抽到可检索文本（图片型文档或纯图形页，需 OCR 解析器）"));
            // OCR 相关告警（未配引擎、整页超限、识别失败）是调用方主动请求后才出现的，必须一并递出去：
            // 否则「开了 OCR 还是失败」与「压根没开 OCR」给出同一句话，运维无从判断钱花在了哪一步。
            // 只取前两条：后处理可能逐页告警，全拼进去会把给用户的失败原因撑成不可读的长串
            document.getWarnings().stream()
                    .filter(w -> w.contains("OCR") && !reasons.contains(w))
                    .limit(2)
                    .forEach(reasons::add);
            ConversionResult result = ConversionResult.failure(used.key(), String.join("；", reasons));
            result.setParseMs(parseMs);
            return result;
        }
        fillMeta(document, used, parseMs, source);
        ConversionResult result = ConversionResult.success(document, used.key(), parseMs);
        if (images != null) {
            log.debug("图片抽取：尝试 {}，落地 {}，过滤 {}，失败 {}（sink={}）",
                    images.attempted(), images.stored(), images.dropped(), images.failed(), images.sinkKey());
        }
        saveToCache(document, key, used);
        return result;
    }

    /** 是否走缓存：强制重解析、键不完整、功能关闭都跳过 */
    private boolean useCache(ConversionKey key, ConvertOptions options) {
        return cacheEnabled && key.valid() && !options.isForceReparse();
    }

    private void saveToCache(QunxuDocument document, ConversionKey key, DocumentBackend used) {
        // 注：forceReparse 只跳过「读」，结果仍需回写，否则下次又要重解析
        if (!cacheEnabled || document == null || document.isEmpty() || used == null || !key.valid()) {
            return;
        }
        // 发生过降级时用实际解析器的 key 存，避免把兜底结果冒充成主解析器的结果长期命中
        ConversionKey actual = ConversionKey.of(key.fileHash(), used.key(), used.version(), key.optionsFingerprint());
        try {
            cacheStore.save(actual, document);
        } catch (Exception e) {
            log.warn("解析缓存写入失败（不影响本次结果）：{}", e.getMessage());
        }
    }

    /** 统一回填元信息（Backend 不必各自记得） */
    private void fillMeta(QunxuDocument document, DocumentBackend backend, long parseMs, DocumentSource source) {
        if (document == null) {
            return;
        }
        DocumentMeta meta = document.getMeta();
        if (meta == null) {
            meta = new DocumentMeta();
            document.setMeta(meta);
        }
        meta.setBackendKey(backend.key());
        meta.setBackendVersion(backend.version());
        if (parseMs >= 0) {
            meta.setParseMs(parseMs);
        }
        if (meta.getSourceName() == null || meta.getSourceName().isBlank()) {
            meta.setSourceName(source.name());
        }
        if (meta.getFileHash() == null || meta.getFileHash().isBlank()) {
            try {
                meta.setFileHash(source.sha256());
            } catch (Exception ignored) {
                // 字节读取失败已在别处记录
            }
        }
        if (meta.getOriginUrl() == null) {
            meta.setOriginUrl(source.originUrl());
        }
        if (meta.getFileSize() <= 0) {
            meta.setFileSize(safeSize(source));
        }
        if (meta.getPageCount() <= 0 && !document.getPages().isEmpty()) {
            meta.setPageCount(document.getPages().size());
        }
    }

    private QunxuDocument applyPostProcessors(QunxuDocument document, ParseContext context) {
        for (DocumentPostProcessor processor : postProcessors) {
            try {
                if (!processor.supports(document, context)) {
                    continue;
                }
                QunxuDocument processed = processor.process(document, context);
                if (processed != null) {
                    document = processed;
                }
            } catch (Exception e) {
                log.warn("后处理 {} 失败，跳过该处理器：{}", processor.key(), describe(e));
                document.warn("后处理 " + processor.key() + " 失败：" + describe(e));
            }
        }
        return document;
    }

    // ==================== 路由 ====================

    /**
     * 选定处理该格式的解析器。
     *
     * @return 解析器；无任何匹配且无兜底时返回 {@code null}
     * @throws IllegalStateException forceBackend 指定的 key 不存在
     */
    public DocumentBackend route(DocumentFormat format, ConvertOptions options) {
        String force = options == null ? null : options.getForceBackend();
        if (force != null && !force.isBlank()) {
            DocumentBackend backend = backendsByKey.get(force.trim());
            if (backend == null) {
                throw new IllegalStateException("指定的解析器不存在：" + force
                        + "（可用：" + String.join(", ", backendsByKey.keySet()) + "）");
            }
            if (format != null && !backend.supports(format)) {
                log.warn("强制解析器 {} 声明不支持格式 {}，仍按指定执行", force, format);
            }
            return backend;
        }
        if (format != null) {
            String preferred = preferredBackends.get(format.name().toLowerCase(Locale.ROOT));
            if (preferred != null) {
                DocumentBackend backend = backendsByKey.get(preferred);
                if (backend != null) {
                    return backend;
                }
                log.warn("配置的首选解析器 {} 未注册，回退按 order 选择", preferred);
            }
        }
        DocumentFormat target = format == null ? DocumentFormat.UNKNOWN : format;
        return backends.stream()
                .filter(backend -> backend.supports(target))
                .filter(backend -> !FALLBACK_BACKEND_KEY.equals(backend.key()))
                .min(Comparator.comparingInt(DocumentBackend::order))
                .orElseGet(() -> supportsFallback(target));
    }

    private DocumentBackend supportsFallback(DocumentFormat format) {
        DocumentBackend fallback = backendsByKey.get(FALLBACK_BACKEND_KEY);
        if (fallback != null && fallback.supports(format)) {
            return fallback;
        }
        // 兜底通常 supports 全量；显式不支持时按 order 再扫一遍
        return backends.stream()
                .filter(backend -> backend.supports(format))
                .min(Comparator.comparingInt(DocumentBackend::order))
                .orElse(null);
    }

    /**
     * 降级链：同一格式的其他解析器按 order 升序排前面，通用兜底解析器（{@link #FALLBACK_BACKEND_KEY}）永远排最后。
     * <p>
     * 只认兜底解析器的话，结构化 PDF 解析器（{@code opendataloader}）一旦抛错就会直接掉到无版面、无页码的
     * 兜底结果，而同一格式明明还有 {@code tika-pdf} 可用；一个格式有多个实现时必须有「同格式次优」这一层。
     * 单实现格式的链长恒为 1（只有兜底），行为与改动前一致。
     */
    private List<DocumentBackend> fallbackChain(DocumentBackend used, DocumentFormat format) {
        List<DocumentBackend> chain = new ArrayList<>();
        for (DocumentBackend candidate : backends) {
            // backends 已按 order 升序；兜底解析器 supports() 恒为真，单独挪到链尾
            if (candidate != used && !FALLBACK_BACKEND_KEY.equals(candidate.key())
                    && candidate.supports(format)) {
                chain.add(candidate);
            }
        }
        DocumentBackend generic = backendsByKey.get(FALLBACK_BACKEND_KEY);
        if (generic != null && generic != used) {
            chain.add(generic);
        }
        return chain;
    }

    // ==================== 注册表（L3 扩展点） ====================

    /** 注册/替换一个解析器（同名 key 覆盖），线程安全 */
    public DocumentConverter register(DocumentBackend backend) {
        if (backend == null || backend.key() == null || backend.key().isBlank()) {
            throw new IllegalArgumentException("解析器及其 key 不能为空");
        }
        String key = backend.key().trim();
        DocumentBackend previous = backendsByKey.put(key, backend);
        if (previous != null) {
            backends.removeIf(item -> item.key().equals(key));
            log.info("解析器 {} 被替换：{} -> {}", key, previous.getClass().getSimpleName(),
                    backend.getClass().getSimpleName());
        }
        backends.add(backend);
        backends.sort(Comparator.comparingInt(DocumentBackend::order));
        return this;
    }

    /** 注销一个解析器（L3：灰度回退、测试桩清理） */
    public DocumentBackend unregister(String key) {
        if (key == null) {
            return null;
        }
        DocumentBackend removed = backendsByKey.remove(key.trim());
        if (removed != null) {
            backends.removeIf(item -> item.key().equals(key.trim()));
            log.info("解析器已注销：{}", key);
        }
        return removed;
    }

    /** 注册/替换一个导出器 */
    public DocumentConverter registerExporter(DocumentExporter exporter) {
        if (exporter != null && exporter.key() != null && !exporter.key().isBlank()) {
            exportersByKey.put(exporter.key().trim(), exporter);
        }
        return this;
    }

    /** 注册一个后处理器并保持按 order 排序 */
    public DocumentConverter registerPostProcessor(DocumentPostProcessor processor) {
        if (processor != null) {
            postProcessors.removeIf(item -> item.key().equals(processor.key()));
            postProcessors.add(processor);
            postProcessors.sort(Comparator.comparingInt(DocumentPostProcessor::order));
        }
        return this;
    }

    /** 按格式指定首选解析器（运行期等价于 L2 配置） */
    public DocumentConverter preferBackend(String format, String backendKey) {
        if (format != null && backendKey != null) {
            preferredBackends.put(format.trim().toLowerCase(Locale.ROOT), backendKey.trim());
        }
        return this;
    }

    // ==================== 导出 / 观测 ====================

    /**
     * 按导出器 key 渲染文档。
     *
     * @param exporterKey markdown/html/json/text 或自定义 key
     * @throws IllegalArgumentException key 未注册
     */
    public String export(QunxuDocument document, String exporterKey) {
        if (document == null) {
            return "";
        }
        String key = exporterKey == null || exporterKey.isBlank() ? "markdown" : exporterKey.trim();
        DocumentExporter exporter = exportersByKey.get(key);
        if (exporter == null) {
            throw new IllegalArgumentException("未注册的导出格式：" + key + "（可用："
                    + String.join(", ", exportersByKey.keySet()) + "）");
        }
        return exporter.export(document);
    }

    /** 已注册导出 key 列表 */
    public List<String> exporterKeys() {
        return new ArrayList<>(exportersByKey.keySet());
    }

    /** 已注册解析器（按 order 排序） */
    public List<DocumentBackend> backends() {
        return new ArrayList<>(backends);
    }

    /** 解析器概览（自检接口/日志用）：key[version,order] -> 支持的格式 */
    public Map<String, String> describeBackends() {
        Map<String, String> result = new LinkedHashMap<>();
        for (DocumentBackend backend : backends) {
            List<String> formats = new ArrayList<>();
            for (DocumentFormat format : DocumentFormat.values()) {
                if (backend.supports(format)) {
                    formats.add(format.name().toLowerCase(Locale.ROOT));
                }
            }
            result.put(backend.key() + "[" + backend.version() + ",order=" + backend.order() + "]",
                    String.join("/", formats));
        }
        return result;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /** 部署级图片开关（options 无法越过它） */
    public boolean isImageExtractionEnabled() {
        return imageExtractionEnabled;
    }

    public DocumentFormatDetector getDetector() {
        return detector;
    }

    public ImageFilter getImageFilter() {
        return imageFilter;
    }

    /** 缓存与合流统计（自检用） */
    public String stats() {
        return "cacheEnabled=" + cacheEnabled + ", inFlight=" + singleFlight.inFlightCount()
                + ", backends=" + backendsByKey.keySet();
    }

    private long safeSize(DocumentSource source) {
        try {
            return source.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(12, hash.length()));
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}

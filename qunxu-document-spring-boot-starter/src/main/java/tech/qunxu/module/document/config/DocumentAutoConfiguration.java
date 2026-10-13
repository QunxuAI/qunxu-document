package tech.qunxu.module.document.config;

import tech.qunxu.module.document.api.DocumentConverter;
import tech.qunxu.module.document.api.DocumentConverterCustomizer;
import tech.qunxu.module.document.backend.DocumentBackend;
import tech.qunxu.module.document.backend.MarkdownDocumentBackend;
import tech.qunxu.module.document.backend.MsWordDocumentBackend;
import tech.qunxu.module.document.backend.OpenDataLoaderPdfBackend;
import tech.qunxu.module.document.backend.PdfTikaBackend;
import tech.qunxu.module.document.backend.PlainTextDocumentBackend;
import tech.qunxu.module.document.backend.PowerPointDocumentBackend;
import tech.qunxu.module.document.backend.TabularDocumentBackend;
import tech.qunxu.module.document.backend.TikaFallbackBackend;
import tech.qunxu.module.document.cache.ConversionCacheStore;
import tech.qunxu.module.document.cache.InMemoryConversionCache;
import tech.qunxu.module.document.chunk.BaseChunker;
import tech.qunxu.module.document.chunk.ChunkerRegistry;
import tech.qunxu.module.document.chunk.FlatChunker;
import tech.qunxu.module.document.chunk.HierarchicalChunker;
import tech.qunxu.module.document.chunk.JTokkitTokenCounter;
import tech.qunxu.module.document.chunk.TableRowChunker;
import tech.qunxu.module.document.chunk.TokenCounter;
import tech.qunxu.module.document.export.DocumentExporter;
import tech.qunxu.module.document.export.HtmlExporter;
import tech.qunxu.module.document.export.JsonExporter;
import tech.qunxu.module.document.export.MarkdownExporter;
import tech.qunxu.module.document.export.TextExporter;
import tech.qunxu.module.document.image.ImageFilter;
import tech.qunxu.module.document.image.ImageSink;
import tech.qunxu.module.document.image.LocalDirImageSink;
import tech.qunxu.module.document.image.NoopImageSink;
import tech.qunxu.module.document.ocr.PageOcr;
import tech.qunxu.module.document.ocr.PdfPageRasterizer;
import tech.qunxu.module.document.post.DocumentPostProcessor;
import tech.qunxu.module.document.post.OcrPostProcessor;
import tech.qunxu.module.document.source.SsrfGuard;
import tech.qunxu.module.document.source.UrlDownloader;
import tech.qunxu.module.document.source.UrlOptions;
import tech.qunxu.module.document.support.DocumentFormatDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文档解析模块自动配置。
 * <p>
 * 模块内<b>零 {@code @Component}</b>：所有 Bean 都在这里用 {@code @Bean + @ConditionalOnMissingBean}
 * 声明，经 {@code META-INF/spring/...AutoConfiguration.imports} 装配。原因是宿主应用的
 * {@code @ComponentScan("tech.qunxu")} 会扫到本模块子包，若模块自带 stereotype 注解
 * 就会与自动配置重复注册（同名不同实例，路由表出现两份 backend）。
 * <p>
 * 宿主应用可覆盖的关键点（全部走 SPI，不改模块）：
 * <ul>
 *   <li>{@link ImageSink} —— {@code sink=storage} 时由宿主提供基于对象存储的实现（OSS/MinIO）；</li>
 *   <li>{@link PageOcr} —— 宿主提供识别引擎（模型表里的 OCR 模型）后，扫描件才补得出文字；</li>
 *   <li>{@link ConversionCacheStore} —— 宿主提供持久化实现后，进程内实现自动退让；</li>
 *   <li>{@link DocumentBackend} / {@link DocumentExporter} / {@link BaseChunker} —— 追加 Bean 即扩展（L1）；</li>
 *   <li>{@link DocumentConverterCustomizer} —— 不改代码地在运行期增删解析器（L3）。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "qunxu.document", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    // ==================== 配置属性 ====================

    /**
     * 配置属性 Bean：以 {@code @Bean + @ConfigurationProperties} 绑定（前缀 {@link DocumentModuleProperties#PREFIX}），
     * 属性类本身是 core 里的纯 Java POJO（零 Spring 注解），这里把绑定留在 starter 侧。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = DocumentModuleProperties.PREFIX)
    public DocumentModuleProperties documentModuleProperties() {
        return new DocumentModuleProperties();
    }

    // ==================== 输入源 ====================

    @Bean
    @ConditionalOnMissingBean
    public UrlOptions documentUrlOptions(DocumentModuleProperties properties) {
        DocumentModuleProperties.Url url = properties.getUrl();
        return new UrlOptions(url.getAllowHosts(), url.getMaxBytes(), url.getConnectTimeoutSeconds(),
                url.getReadTimeoutSeconds(), url.getMaxRedirects(), url.isAllowPrivateNetwork());
    }

    @Bean
    @ConditionalOnMissingBean
    public SsrfGuard ssrfGuard(UrlOptions options) {
        if (options.allowHosts() == null || options.allowHosts().isEmpty()) {
            log.info("未配置 qunxu.document.url.allow-hosts，URL 输入源处于禁用状态");
        }
        return new SsrfGuard(options);
    }

    @Bean
    @ConditionalOnMissingBean
    public UrlDownloader urlDownloader(UrlOptions options, SsrfGuard guard) {
        return new UrlDownloader(options, guard);
    }

    // ==================== 图片落地 ====================

    @Bean
    @ConditionalOnMissingBean
    public ImageFilter imageFilter(DocumentModuleProperties properties) {
        DocumentModuleProperties.Image image = properties.getImage();
        return new ImageFilter(image.getMinWidth(), image.getMinHeight(), image.getMinBytes(),
                image.getMaxBytes(), image.getMaxImagesPerDoc());
    }

    /**
     * 图片落地器。
     * <p>
     * {@code sink=local} 与 {@code sink=noop} 由模块自带实现满足；
     * {@code sink=storage}（默认）必须由宿主提供 {@link ImageSink} Bean —— 只有宿主知道该用哪套
     * 对象存储（本地目录 / OSS / MinIO），模块反过来依赖存储层就破坏了独立性。
     */
    @Bean
    @ConditionalOnMissingBean(ImageSink.class)
    public ImageSink imageSink(DocumentModuleProperties properties) {
        DocumentModuleProperties.Image image = properties.getImage();
        String sink = image.getSink() == null ? "" : image.getSink().trim().toLowerCase(Locale.ROOT);
        if ("local".equals(sink)) {
            Path root = image.getLocalRoot() == null || image.getLocalRoot().isBlank()
                    ? null : Path.of(image.getLocalRoot().trim());
            return new LocalDirImageSink(root, image.getBasePath(), image.getUrlPrefix());
        }
        if (!"noop".equals(sink)) {
            log.warn("qunxu.document.image.sink={} 需要宿主应用提供 ImageSink Bean（如 FileStorageImageSink），"
                    + "当前未找到，内嵌图片只做统计与过滤、不落地", sink);
        }
        return new NoopImageSink();
    }

    // ==================== 解析器（Backend） ====================

    @Bean
    @ConditionalOnMissingBean
    public MsWordDocumentBackend msWordDocumentBackend() {
        return new MsWordDocumentBackend();
    }

    @Bean
    @ConditionalOnMissingBean
    public PowerPointDocumentBackend powerPointDocumentBackend() {
        return new PowerPointDocumentBackend();
    }

    @Bean
    @ConditionalOnMissingBean
    public TabularDocumentBackend tabularDocumentBackend() {
        return new TabularDocumentBackend();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownDocumentBackend markdownDocumentBackend() {
        return new MarkdownDocumentBackend();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlainTextDocumentBackend plainTextDocumentBackend() {
        return new PlainTextDocumentBackend();
    }

    @Bean
    @ConditionalOnMissingBean
    public PdfTikaBackend pdfTikaBackend() {
        return new PdfTikaBackend();
    }

    /**
     * 结构化 PDF 解析器：order 小于 {@code tika-pdf}，因此在位时自动接管 PDF。
     * <p>
     * 它依赖外部构件（veraPDF 等，只发布在第三方仓库），宿主排包或私有仓库不通时类会缺失，
     * 因此按类是否存在装配 —— 缺席时 PDF 自动回到 {@code tika-pdf}，链路不因缺一个可选依赖而失败。
     * 该 Bean 实现 {@link AutoCloseable}，容器销毁时释放库内的全局 HTTP 客户端。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.opendataloader.pdf.api.OpenDataLoaderPDF")
    public OpenDataLoaderPdfBackend openDataLoaderPdfBackend() {
        return new OpenDataLoaderPdfBackend();
    }

    /** 兜底解析器：key 必须是 tika-fallback，转换器按该 key 做失败降级 */
    @Bean
    @ConditionalOnMissingBean
    public TikaFallbackBackend tikaFallbackBackend() {
        return new TikaFallbackBackend();
    }

    // ==================== 导出器 ====================

    @Bean
    @ConditionalOnMissingBean
    public MarkdownExporter markdownExporter() {
        return new MarkdownExporter();
    }

    @Bean
    @ConditionalOnMissingBean
    public HtmlExporter htmlExporter() {
        return new HtmlExporter();
    }

    @Bean
    @ConditionalOnMissingBean
    public TextExporter textExporter() {
        return new TextExporter();
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonExporter jsonExporter() {
        return new JsonExporter();
    }

    // ==================== 分块器 ====================

    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter() {
        // 与 Spring AI 的 JTokkitTokenCountEstimator 同口径（CL100K_BASE），预算判定才可比
        return new JTokkitTokenCounter();
    }

    @Bean
    @ConditionalOnMissingBean
    public HierarchicalChunker hierarchicalChunker(TokenCounter tokenCounter) {
        return new HierarchicalChunker(tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public TableRowChunker tableRowChunker(TokenCounter tokenCounter) {
        return new TableRowChunker(tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlatChunker flatChunker(TokenCounter tokenCounter) {
        return new FlatChunker(tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkOptionsFactory chunkOptionsFactory(DocumentModuleProperties properties) {
        return new ChunkOptionsFactory(properties);
    }

    /**
     * 分块器注册表：收集模块内置与宿主新增（L1）的全部 {@link BaseChunker}，
     * 并把改造前的旧 splitterName 映射到新实现，避免存量集合被升级改掉分段结果。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChunkerRegistry chunkerRegistry(ObjectProvider<BaseChunker> chunkers) {
        return new ChunkerRegistry(chunkers.orderedStream().toList());
    }

    // ==================== 后处理 ====================

    @Bean
    @ConditionalOnMissingBean
    public PdfPageRasterizer pdfPageRasterizer(DocumentModuleProperties properties) {
        DocumentModuleProperties.Ocr ocr = properties.getOcr();
        return new PdfPageRasterizer(new PdfPageRasterizer.OcrConfig()
                .setDpi(ocr.getDpi())
                .setMaxSide(ocr.getMaxSide())
                .setFormat(ocr.getFormat())
                .setJpegQuality(ocr.getJpegQuality())
                .setMaxBytes(ocr.getMaxBytes()));
    }

    /**
     * 扫描件 OCR 后处理器。
     * <p>
     * 没有 {@link PageOcr} 实现时<b>仍然注册</b>：这样请求了 {@code doOcr} 的调用能在结果的
     * warnings 里看到「未配置识别引擎」这一确切原因，而不是静默拿到一份无文本层的失败文档
     * （与 {@link #imageSink} 缺实现时降级为 Noop 只告警是同一取向）。部署侧要彻底关掉
     * 该能力用 {@code qunxu.document.ocr.enabled=false}。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "qunxu.document.ocr", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public OcrPostProcessor ocrPostProcessor(DocumentModuleProperties properties,
                                             ObjectProvider<PageOcr> pageOcr,
                                             PdfPageRasterizer rasterizer) {
        PageOcr engine = pageOcr.getIfAvailable();
        if (engine == null) {
            log.warn("未提供 PageOcr 实现（宿主模型层的 OCR 引擎），请求扫描件 OCR 时将只告警不识别");
        } else {
            log.info("扫描件 OCR 已就绪：引擎 {}，单篇页数上限 {}，时长预算 {}s", engine.key(),
                    properties.getOcr().getMaxPages(), properties.getOcr().getTimeoutSeconds());
        }
        return new OcrPostProcessor(engine, rasterizer, properties.getOcr().getMaxPages(),
                properties.getOcr().getTimeoutSeconds());
    }

    // ==================== 缓存 ====================

    /**
     * 解析缓存：宿主提供持久化实现（DB + 对象存储）后本进程内实现自动退让。
     * <p>
     * 进程内实现只保证 single-flight 合流与短期重复解析免单，重启即失效 —— 它不该被当成持久缓存，
     * 因此容量默认很小（64 份中间格式 JSON 就够撑几百 MB）。
     */
    @Bean
    @ConditionalOnMissingBean(ConversionCacheStore.class)
    public ConversionCacheStore conversionCacheStore(DocumentModuleProperties properties) {
        return new InMemoryConversionCache(properties.getCache().getMemoryMaxEntries());
    }

    // ==================== 转换器 ====================

    @Bean
    @ConditionalOnMissingBean
    public DocumentFormatDetector documentFormatDetector() {
        return new DocumentFormatDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentConverter documentConverter(DocumentModuleProperties properties,
                                               ObjectProvider<DocumentBackend> backends,
                                               ObjectProvider<DocumentExporter> exporters,
                                               ObjectProvider<DocumentPostProcessor> postProcessors,
                                               ObjectProvider<ImageSink> imageSink,
                                               ObjectProvider<ImageFilter> imageFilter,
                                               ObjectProvider<DocumentFormatDetector> detector,
                                               ObjectProvider<ConversionCacheStore> cacheStore,
                                               ObjectProvider<DocumentConverterCustomizer> customizers) {
        List<DocumentBackend> registered = backends.orderedStream().toList();
        DocumentConverter converter = new DocumentConverter(
                registered,
                exporters.orderedStream().toList(),
                postProcessors.orderedStream().toList(),
                imageSink.getIfAvailable(),
                imageFilter.getIfAvailable(),
                detector.getIfAvailable(),
                cacheStore.getIfAvailable(),
                properties.getCache().isEnabled(),
                properties.getBackends().getPreferred(),
                properties.getImage().isEnabled());
        // L3：定制器按 order 执行，用于灰度替换 / 测试桩 / 新格式热插
        customizers.orderedStream()
                .sorted(Comparator.comparingInt(DocumentConverterCustomizer::order))
                .forEach(customizer -> customizer.customize(converter));
        if (registered.isEmpty()) {
            log.warn("未注册任何 DocumentBackend，文档转换将全部失败");
        }
        return converter;
    }
}

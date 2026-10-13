package tech.qunxu.module.document.tool;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.ConversionResult;
import tech.qunxu.module.document.api.ConversionStatus;
import tech.qunxu.module.document.api.DocumentConverter;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.backend.DocumentBackend;
import tech.qunxu.module.document.backend.MarkdownDocumentBackend;
import tech.qunxu.module.document.backend.MsWordDocumentBackend;
import tech.qunxu.module.document.backend.OpenDataLoaderPdfBackend;
import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.backend.PdfTikaBackend;
import tech.qunxu.module.document.backend.PlainTextDocumentBackend;
import tech.qunxu.module.document.backend.PowerPointDocumentBackend;
import tech.qunxu.module.document.backend.TabularDocumentBackend;
import tech.qunxu.module.document.backend.TikaFallbackBackend;
import tech.qunxu.module.document.cache.InMemoryConversionCache;
import tech.qunxu.module.document.chunk.Chunk;
import tech.qunxu.module.document.chunk.ChunkOptions;
import tech.qunxu.module.document.chunk.ChunkerRegistry;
import tech.qunxu.module.document.chunk.FlatChunker;
import tech.qunxu.module.document.chunk.HierarchicalChunker;
import tech.qunxu.module.document.chunk.JTokkitTokenCounter;
import tech.qunxu.module.document.chunk.TableRowChunker;
import tech.qunxu.module.document.config.ChunkOptionsFactory;
import tech.qunxu.module.document.config.DocumentModuleProperties;
import tech.qunxu.module.document.export.DocumentExporter;
import tech.qunxu.module.document.export.HtmlExporter;
import tech.qunxu.module.document.export.JsonExporter;
import tech.qunxu.module.document.export.MarkdownExporter;
import tech.qunxu.module.document.export.TextExporter;
import tech.qunxu.module.document.image.ImageFilter;
import tech.qunxu.module.document.image.ImageSink;
import tech.qunxu.module.document.image.LocalDirImageSink;
import tech.qunxu.module.document.image.NoopImageSink;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.ocr.OcrRequest;
import tech.qunxu.module.document.ocr.PageOcr;
import tech.qunxu.module.document.ocr.PdfPageRasterizer;
import tech.qunxu.module.document.post.DocumentPostProcessor;
import tech.qunxu.module.document.post.OcrPostProcessor;
import tech.qunxu.module.document.source.DocumentSource;
import tech.qunxu.module.document.source.SsrfGuard;
import tech.qunxu.module.document.source.UrlDownloader;
import tech.qunxu.module.document.source.UrlOptions;
import tech.qunxu.module.document.support.DocumentFormatDetector;
import tech.qunxu.module.document.support.Sha256Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模块自检与样本查看器（不注册为 Bean，不参与运行时；测试框架经 {@link #runSelfCheck()} 复用本类）。
 * <p>
 * 不带参数时这个 {@code main} 是模块的可执行回归入口：
 * <ul>
 *   <li>不带参数 → 跑内置合成样本自检（路由、导出、分块、缓存命中、JSON 往返、
 *       L2/L3 扩展点、图片格式不冒充、SSRF 三道闸门、扫描件 OCR 页级兜底），全部通过时退出码 0；</li>
 *   <li>带文件参数 → 逐个打印 {@link QunxuDocument} 结构摘要与四种导出、三种分块结果；</li>
 *   <li>{@code --make-samples} → 先用 {@link SampleDocumentFactory} 现造 docx/pptx/xlsx/pdf/csv/md/txt
 *       再逐个打印（手头没有真样本时的冒烟入口）；</li>
 *   <li>{@code --url=<地址>} 配合 {@code --allow-hosts=a.com,.b.com} 验证 URL 输入源。</li>
 * </ul>
 * 运行方式（在 {@code qunxu-document-core} 模块目录下，未引入 exec-maven-plugin，直接用 java 起）：
 * <pre>
 * mvn -q -DskipTests compile
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * java -classpath "target/classes:$(cat target/cp.txt)" \
 *     tech.qunxu.module.document.tool.DocumentSampleRunner 样本.docx 样本.pdf
 * </pre>
 * 可选旗标：{@code --make-samples}、{@code --sink=local|noop|storage}（默认 local）、
 * {@code --out=目录}、{@code --backend=解析器 key}（跳过路由直接指定，用于同格式多个实现的效果对比）、
 * {@code --url=地址 --allow-hosts=a.com,.b.com [--allow-private=true]}。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class DocumentSampleRunner {

    private static int checks;
    private static int failures;
    /** {@code --backend=} 强制解析器（对比同格式多个实现的产出时用） */
    private static String forceBackend;
    /** {@code SampleDocumentFactory.WATERMARK_TEXT} 里的五个词，查泄漏时按独立单词逐个查 */
    private static final Pattern WATERMARK_WORD = Pattern.compile(
            "\\b(internal|copy|do|not|distribute)\\b", Pattern.CASE_INSENSITIVE);

    private DocumentSampleRunner() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                flags.put(eq < 0 ? arg : arg.substring(0, eq), eq < 0 ? "true" : arg.substring(eq + 1));
            } else if (!arg.isBlank()) {
                files.add(arg);
            }
        }
        String sink = flags.getOrDefault("--sink", "local");
        forceBackend = flags.get("--backend");
        Path out = Path.of(flags.getOrDefault("--out", System.getProperty("java.io.tmpdir") + "/qunxu-document-selfcheck"));
        DocumentModuleProperties properties = new DocumentModuleProperties();
        properties.getImage().setSink(sink);
        properties.getImage().setLocalRoot(out.toString());
        DocumentConverter converter = buildConverter(properties);
        printRegistry(converter);

        if (flags.containsKey("--make-samples")) {
            // 没有真样本时用 POI/PDFBox 现造一轮，覆盖 docx 标题层级与合并、pptx 占位符、PDF 逐页与内嵌图
            Path dir = out.resolve("samples");
            List<Path> generated = SampleDocumentFactory.generate(dir);
            for (Path generatedFile : generated) {
                reportFile(converter, generatedFile);
            }
        }
        if (!files.isEmpty()) {
            for (String file : files) {
                reportFile(converter, Path.of(file));
            }
        }
        if (flags.containsKey("--url")) {
            reportUrl(converter, flags.get("--url"), flags.get("--allow-hosts"),
                    Boolean.parseBoolean(flags.getOrDefault("--allow-private", "false")));
        }
        if (files.isEmpty() && !flags.containsKey("--url") && !flags.containsKey("--make-samples")) {
            int failed = runSelfCheck();
            System.out.println();
            System.out.printf("自检结果：%d 项检查，%s%n", checks, failed == 0 ? "全部通过" : failed + " 项失败");
            if (failed > 0) {
                System.exit(1);
            }
        }
    }

    /**
     * 无参数自检入口（供测试框架与 CI 复用）：跑内置合成样本自检，返回失败项数（0 即全部通过）。
     * <p>
     * 与 {@code main} 无参模式的唯一差异是不调用 {@code System.exit}，方便 JUnit 断言；
     * 每次调用会重置检查计数，输出仍走 {@code System.out}。
     */
    public static int runSelfCheck() {
        checks = 0;
        failures = 0;
        Path out = Path.of(System.getProperty("java.io.tmpdir") + "/qunxu-document-selfcheck");
        DocumentModuleProperties properties = new DocumentModuleProperties();
        properties.getImage().setSink("local");
        properties.getImage().setLocalRoot(out.toString());
        selfCheck(properties);
        return failures;
    }

    /** 最近一次 {@link #runSelfCheck()} 的检查项总数（供断言自检规模，防止新增能力后无人补断言） */
    public static int checkCount() {
        return checks;
    }

    // ==================== 装配（脱离 Spring，与自动配置同一套实现） ====================

    private static DocumentConverter buildConverter(DocumentModuleProperties properties) {
        return buildConverter(properties, List.of());
    }

    /**
     * 装配（脱离 Spring，与自动配置同一套实现）。
     *
     * @param postProcessors 后处理链（OCR 自检用，与自动配置里 {@code postProcessors} 的收集方式一致）
     */
    private static DocumentConverter buildConverter(DocumentModuleProperties properties,
                                                   List<DocumentPostProcessor> postProcessors) {
        List<DocumentBackend> backends = new ArrayList<>(List.of(
                new MsWordDocumentBackend(),
                new PowerPointDocumentBackend(),
                new TabularDocumentBackend(),
                new MarkdownDocumentBackend(),
                new PlainTextDocumentBackend(),
                new PdfTikaBackend(),
                new TikaFallbackBackend()));
        // 结构化 PDF 解析器依赖第三方仓库的构件，本地缺包时自检不应整体失败
        try {
            backends.add(new OpenDataLoaderPdfBackend());
        } catch (Throwable t) {
            System.out.println("  跳过 opendataloader（依赖不可用：" + t + "）");
        }
        List<DocumentExporter> exporters = List.of(
                new MarkdownExporter(), new HtmlExporter(), new TextExporter(), new JsonExporter());
        ImageSink imageSink = "noop".equalsIgnoreCase(properties.getImage().getSink())
                ? new NoopImageSink()
                : new LocalDirImageSink(Path.of(properties.getImage().getLocalRoot()),
                        properties.getImage().getBasePath(), null);
        return new DocumentConverter(backends, exporters, postProcessors, imageSink, new ImageFilter(),
                new DocumentFormatDetector(), new InMemoryConversionCache(64), true,
                properties.getBackends().getPreferred(), properties.getImage().isEnabled());
    }

    /** 按部署配置装配 OCR 后处理器（与 {@code DocumentAutoConfiguration} 同一组闸门参数） */
    private static OcrPostProcessor ocrProcessor(PageOcr engine, DocumentModuleProperties properties) {
        DocumentModuleProperties.Ocr ocr = properties.getOcr();
        PdfPageRasterizer rasterizer = new PdfPageRasterizer(new PdfPageRasterizer.OcrConfig()
                .setDpi(ocr.getDpi())
                .setMaxSide(ocr.getMaxSide())
                .setFormat(ocr.getFormat())
                .setJpegQuality(ocr.getJpegQuality())
                .setMaxBytes(ocr.getMaxBytes()));
        return new OcrPostProcessor(engine, rasterizer, ocr.getMaxPages(), ocr.getTimeoutSeconds());
    }

    private static ChunkerRegistry chunkers() {
        JTokkitTokenCounter counter = new JTokkitTokenCounter();
        return new ChunkerRegistry(List.of(new HierarchicalChunker(counter), new TableRowChunker(counter),
                new FlatChunker(counter)));
    }

    private static void printRegistry(DocumentConverter converter) {
        System.out.println("== 解析器注册表 ==");
        converter.describeBackends().forEach((key, formats) -> System.out.println("  " + key + " -> " + formats));
        System.out.println("== 导出器 ==");
        System.out.println("  " + String.join(", ", converter.exporterKeys()));
        System.out.println("== 分块器 ==");
        System.out.println("  " + String.join(", ", chunkers().keys()));
        System.out.println("== 转换器状态 ==");
        System.out.println("  " + converter.stats());
    }

    // ==================== 样本查看 ====================

    private static void reportFile(DocumentConverter converter, Path path) throws IOException {
        System.out.println();
        System.out.println("======== " + path.getFileName() + " (" + Files.size(path) + " bytes) ========");
        byte[] bytes = Files.readAllBytes(path);
        long start = System.currentTimeMillis();
        ConversionResult result = converter.convert(
                DocumentSource.fromPath(path.toString(), () -> Files.newInputStream(path)),
                viewOptions());
        System.out.printf("status=%s backend=%s parseMs=%d(%dms) cache=%s%n", result.getStatus(),
                result.getBackendKey(), result.getParseMs(), System.currentTimeMillis() - start,
                result.isFromCache());
        if (!result.isSuccess() || result.getDocument() == null) {
            System.out.println("  错误：" + result.getErrors());
            return;
        }
        printDocument(result.getDocument());
    }

    /** 查看样本时的选项：带 {@code --backend=} 时跳过路由直接指定解析器 */
    private static ConvertOptions viewOptions() {
        ConvertOptions options = ConvertOptions.defaults();
        if (forceBackend != null && !forceBackend.isBlank()) {
            options.forceBackend(forceBackend.trim());
        }
        return options;
    }

    private static void printDocument(QunxuDocument document) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (NodeItem item : document.getItems()) {
            kinds.merge(String.valueOf(item.getKind()), 1, Integer::sum);
        }
        System.out.println("  kind 分布：" + kinds);
        System.out.printf("  meta：backend=%s v%d，pages=%d，hash=%s%n", document.getMeta().getBackendKey(),
                document.getMeta().getBackendVersion(), document.getMeta().getPageCount(),
                abbreviate(document.getMeta().getFileHash()));
        List<String> headings = new ArrayList<>();
        for (NodeItem item : document.getItems()) {
            if (item.isHeading()) {
                int level = item instanceof NodeItem.HeadingItem heading ? heading.getLevel() : 1;
                headings.add(" ".repeat((level - 1) * 2) + "L" + level + " " + item.getText());
            }
        }
        if (!headings.isEmpty()) {
            System.out.println("  标题层级：");
            headings.forEach(h -> System.out.println("    " + h));
        }
        for (NodeItem item : document.getItems()) {
            if (item instanceof NodeItem.TableItem table) {
                System.out.printf("  表格[%d]：%d 行 x %d 列，表头 %d 列，合并单元格 %d 个%n",
                        table.getIndex(), table.getRowCount(), table.getColCount(),
                        table.getHeaders().size(), mergedCells(table));
            }
            if (item instanceof NodeItem.PictureItem picture) {
                System.out.printf("  图片[%d]：stored=%s %s%s%n", picture.getIndex(),
                        picture.getImage() != null && picture.getImage().stored(),
                        picture.getImage() == null ? "无引用" : picture.getImage().url(),
                        picture.getText() == null ? "" : " alt=" + picture.getText());
            }
        }
        if (!document.getWarnings().isEmpty()) {
            System.out.println("  告警：" + document.getWarnings());
        }
        String markdown = document.exportAsMarkdown();
        String html = document.exportAsHtml();
        String text = document.exportAsText();
        String json = document.toJson();
        System.out.printf("  导出：markdown=%d 字符，html=%d 字符，text=%d 字符，json=%d 字符%n",
                markdown.length(), html.length(), text.length(), json.length());
        System.out.println("  markdown 预览：" + preview(markdown));
        ChunkerRegistry registry = chunkers();
        ChunkOptionsFactory factory = new ChunkOptionsFactory(new DocumentModuleProperties());
        for (String name : List.of(HierarchicalChunker.KEY, TableRowChunker.KEY, FlatChunker.KEY)) {
            ChunkOptions options = factory.create(512, 128, 10, null,
                    FlatChunker.KEY.equals(name) ? ChunkOptions.STRATEGY_CHARS : null);
            List<Chunk> chunks = registry.chunk(name, document, options);
            int max = chunks.stream().mapToInt(Chunk::getTokenCount).max().orElse(0);
            System.out.printf("  分块[%s]：%d 块，最大 %d tokens，首块面包屑=%s%n", name, chunks.size(), max,
                    chunks.isEmpty() ? "-" : String.join(" / ", chunks.get(0).getHeadingPath()));
            if (!chunks.isEmpty()) {
                System.out.println("    首块预览：" + preview(chunks.get(0).getText()));
            }
        }
    }

    private static int mergedCells(NodeItem.TableItem table) {
        int merged = 0;
        for (NodeItem.TableCell cell : table.getCells()) {
            if (cell.getRowSpan() > 1 || cell.getColSpan() > 1) {
                merged++;
            }
        }
        return merged;
    }

    private static void reportUrl(DocumentConverter converter, String url, String allowHosts,
                                  boolean allowPrivate) throws IOException {
        System.out.println();
        System.out.println("======== URL 输入源：" + url + " ========");
        List<String> hosts = allowHosts == null || allowHosts.isBlank()
                ? List.of() : List.of(allowHosts.split(","));
        UrlOptions options = new UrlOptions(hosts, 52_428_800L, 10, 60, 3, allowPrivate);
        UrlDownloader downloader = new UrlDownloader(options, new SsrfGuard(options));
        ConversionResult result = converter.convert(DocumentSource.fromUrl(url, downloader),
                viewOptions());
        System.out.printf("status=%s backend=%s parseMs=%d originUrl=%s%n", result.getStatus(),
                result.getBackendKey(), result.getParseMs(),
                result.getDocument() == null ? "-" : result.getDocument().getMeta().getOriginUrl());
        if (result.getDocument() != null && !result.getDocument().isEmpty()) {
            printDocument(result.getDocument());
        } else {
            System.out.println("  错误：" + result.getErrors());
        }
    }

    // ==================== 内置自检 ====================

    private static void selfCheck(DocumentModuleProperties properties) {
        System.out.println();
        System.out.println("======== 内置自检 ========");
        DocumentConverter converter = buildConverter(properties);
        ChunkerRegistry registry = chunkers();
        ChunkOptionsFactory factory = new ChunkOptionsFactory(new DocumentModuleProperties());

        byte[] csv = ("姓名,部门,城市\n张三,研发,上海\n李四,产品,北京\n王五,研发,深圳\n")
                .getBytes(StandardCharsets.UTF_8);
        ConversionResult csvResult = converter.convert(DocumentSource.fromBytes("staff.csv", csv),
                ConvertOptions.defaults());
        check("csv 走 tabular 解析器", "tabular".equals(csvResult.getBackendKey()), csvResult.getBackendKey());
        QunxuDocument csvDoc = csvResult.getDocument();
        check("csv 首行提为表头", csvDoc != null && csvDoc.countOf(DocumentKind.TABLE) == 1
                && !csvDoc.itemsOf(DocumentKind.TABLE).isEmpty()
                && ((NodeItem.TableItem) csvDoc.itemsOf(DocumentKind.TABLE).get(0)).getHeaders().size() == 3, null);
        List<Chunk> tableChunks = registry.chunk("ExcelDocumentSplitter", csvDoc,
                factory.create(null, null, 2, null, null));
        check("表格按 rowsPerChunk=2 切成 2 块", tableChunks.size() == 2, "实际 " + tableChunks.size());
        check("每块都带 Sheet 或表头前缀", tableChunks.stream()
                .allMatch(chunk -> chunk.getText().contains("姓名")), null);
        ConversionResult again = converter.convert(DocumentSource.fromBytes("staff.csv", csv),
                ConvertOptions.defaults());
        check("同内容二次转换命中缓存", again.isFromCache(), String.valueOf(again.getStatus()));

        byte[] md = ("# 一级标题\n\n段落一。\n\n## 二级标题\n\n- 列表项一\n- 列表项二\n\n"
                + "```java\nint i = 1;\n```\n\n### 三级标题\n\n段落二。\n").getBytes(StandardCharsets.UTF_8);
        ConversionResult mdResult = converter.convert(DocumentSource.fromBytes("note.md", md),
                ConvertOptions.defaults());
        check("md 走 markdown 解析器", "markdown".equals(mdResult.getBackendKey()), mdResult.getBackendKey());
        QunxuDocument mdDoc = mdResult.getDocument();
        if (mdDoc == null || mdDoc.isEmpty()) {
            check("md 解析产出内容", false, String.valueOf(mdResult.getErrors()));
        } else {
            check("md 识别 3 个标题（首标题计为 TITLE）", headings(mdDoc) == 3, String.valueOf(headings(mdDoc)));
            check("md 识别代码块", mdDoc.countOf(DocumentKind.CODE) == 1, null);
            List<Chunk> hierarchical = registry.chunk(HierarchicalChunker.KEY, mdDoc,
                    factory.create(512, 128, null, null, null));
            check("结构分块产出面包屑", !hierarchical.isEmpty()
                    && hierarchical.get(0).getText().startsWith("> "), null);

            List<Chunk> flat = registry.chunk("SimpleDocumentSplitter", mdDoc, factory.create(20, 5, null, null, null));
            check("兼容别名走 chars 策略（窗口 20 字符）", flat.size() > 1
                    && flat.stream().allMatch(chunk -> chunk.getText().length() <= 20), null);
            List<Chunk> regex = registry.chunk("RegexDocumentSplitter", mdDoc,
                    factory.create(512, 0, null, "\n\n", null));
            check("正则切分按空行分块", regex.size() > 1, "实际 " + regex.size());
        }

        byte[] txt = "第一行\n第二行\n\n第三段\n".getBytes(StandardCharsets.UTF_8);
        ConversionResult txtResult = converter.convert(DocumentSource.fromBytes("plain.txt", txt),
                ConvertOptions.defaults());
        check("txt 走 plaintext 解析器", "plaintext".equals(txtResult.getBackendKey()), txtResult.getBackendKey());

        // 自动选型（splitterName = AutoChunker 或留空）：选型结论必须能被用户看见，规则不能只靠口头约定
        QunxuDocument txtDoc = txtResult.getDocument();
        String autoForMd = mdDoc == null ? null : registry.selectName(ChunkerRegistry.AUTO_NAME, mdDoc);
        check("自动选型：有标题文档用结构分块", "HierarchicalChunker".equals(autoForMd), String.valueOf(autoForMd));
        String autoForTxt = txtDoc == null ? null : registry.selectName(ChunkerRegistry.AUTO_NAME, txtDoc);
        check("自动选型：无标题纯文本用简单分割器",
                "SimpleDocumentSplitter".equals(autoForTxt), String.valueOf(autoForTxt));
        String autoForCsv = registry.selectName(null, csvDoc);
        check("自动选型：纯表格用按行分块（留空等同 Auto）",
                "ExcelDocumentSplitter".equals(autoForCsv), String.valueOf(autoForCsv));
        if (mdDoc != null) {
            List<Chunk> autoChunks = registry.chunk(ChunkerRegistry.AUTO_NAME, mdDoc,
                    factory.create(512, 128, null, null, null));
            List<Chunk> manualChunks = registry.chunk(HierarchicalChunker.KEY, mdDoc,
                    factory.create(512, 128, null, null, null));
            check("自动选型与显式选结构分块的块数一致",
                    autoChunks.size() == manualChunks.size(),
                    autoChunks.size() + " vs " + manualChunks.size());
        }

        ConversionResult png = converter.convert(
                DocumentSource.fromBytes("scan.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3}),
                ConvertOptions.defaults());
        check("图片格式不被兜底解析冒充成功", png.getStatus() == ConversionStatus.UNSUPPORTED_FORMAT,
                String.valueOf(png.getStatus()));

        QunxuDocument round = mdDoc == null ? null : QunxuDocument.fromJson(mdDoc.toJson());
        check("JSON 往返保持节点数与角色", round != null && mdDoc != null
                && round.getItems().size() == mdDoc.getItems().size()
                && round.countOf(DocumentKind.SECTION_HEADER) == mdDoc.countOf(DocumentKind.SECTION_HEADER), null);
        check("JSON 往返保持父子引用", mdDoc != null && round != null && keepsTree(mdDoc, round), null);

        l3Extension(converter);
        l2Preferred(converter);
        ssrfGates();
        structuredPdf(converter, properties);
        scannedPdf(converter, properties);
        scannedOcr(properties);
        ocrFingerprint();
        watermarkAndOverlay(converter, properties);
    }

    /**
     * 水印与隐形文字层：两个方向的断言必须同时在。
     * <p>
     * 正向：斜排大字 + 低透明的水印不能留在文档里，尤其不能占住主标题位，也不能因为字号最大
     * 就把真实标题整体压深一级（样本与无水印的 sample.pdf 同构，所以层级可以直接对拍）。<br>
     * 反向（更重要）：搜索型扫描件把 OCR 结果以 {@code Tr 3} 隐形文字垫在整页位图下面，那份隐形文字
     * 是它唯一的正文。只按“看不见”删水印会把它一起删掉，而无正文现在是要判失败的 ——
     * 等于把一批能用的扫描件推成导入报错。
     */
    private static void watermarkAndOverlay(DocumentConverter converter, DocumentModuleProperties properties) {
        byte[] watermarked;
        byte[] searchable;
        byte[] clean;
        try {
            watermarked = sampleBytes(properties, "watermarked.pdf");
            searchable = sampleBytes(properties, "searchable.pdf");
            clean = sampleBytes(properties, "sample.pdf");
        } catch (Exception e) {
            check("生成水印/文字层样本", false, e.getMessage());
            return;
        }
        ConvertOptions options = ConvertOptions.defaults().extractImages(false);
        ConversionResult mark = converter.convert(DocumentSource.fromBytes("watermarked.pdf", watermarked), options);
        QunxuDocument markDoc = mark.getDocument();
        String markText = markDoc == null ? String.join("；", mark.getErrors()) : tight(markDoc.toText());
        check("水印样本本身解析成功", markDoc != null && mark.isSuccess(),
                mark.getStatus() + "/" + mark.getBackendKey());
        check("斜排低透水印被剔除", !markText.contains("donotdistribute"), markText);
        check("正文段落不因水印规则误删", markText.contains("parsingpipeline"), markText);
        if (markDoc != null) {
            List<NodeItem> titles = markDoc.itemsOf(DocumentKind.TITLE);
            check("主标题没被水印占位", !titles.isEmpty() && tight(titles.get(0).getText()).startsWith("chapter"),
                    titles.isEmpty() ? "无主标题" : tight(titles.get(0).getText()));
        }

        // 水印占了字号档位就会把真实标题压深一级：只删节点不补层级，文档名与面包屑仍然是错的。
        // 拿同构的无水印样本对拍标题骨架，比断言“没有水印”能多拦住这一类回归。
        ConversionResult plain = converter.convert(DocumentSource.fromBytes("sample.pdf", clean), options);
        if (markDoc != null && plain.getDocument() != null) {
            String expect = headingSignature(plain.getDocument());
            String actual = headingSignature(markDoc);
            check("剔水印后标题层级与无水印样本一致", expect.equals(actual), "期望 " + expect + "，实际 " + actual);
        } else {
            check("剔水印后标题层级与无水印样本一致", false, "无水印样本未解析出文档");
        }

        // 真实企业 PDF 的水印多数不画在页面流里，而是整块做成一个共享 Form、由 cm 旋转后 Do 进来
        // （实测样本即是），而且 Form 里常按词分开画。这类水印会被结构化解析器并进邻近的表格单元格，
        // 删节点那一层完全够不上 —— 只有预剔除跟进 Form 内容流才能拿掉。所以样本里特意带一张表格，
        // 并加上“本样本真的产出了表格”这条断言：否则样本退化成无表格时，上面的剔除断言会空过。
        byte[] stamped = null;
        try {
            stamped = sampleBytes(properties, "watermark-form.pdf");
        } catch (Exception e) {
            check("生成 Form 水印样本", false, e.getMessage());
        }
        if (stamped != null) {
            ConversionResult form = converter.convert(
                    DocumentSource.fromBytes("watermark-form.pdf", stamped), options);
            QunxuDocument formDoc = form.getDocument();
            String formText = formDoc == null ? String.join("；", form.getErrors()) : formDoc.toText();
            // 按独立单词查整串水印：解析器会把水印拆成 DIS / IN 这样的短片段（够不上碎片下限）、
            // 或整词贴进正文行与单元格，所以上游只查两个长词会让大半边泄漏变成空过
            boolean formClean = !hasWatermarkWord(formText);
            check("Form 水印（cm 旋转 + 低透明 + 按词分开）被剔除",
                    formDoc != null && form.isSuccess() && formClean, form.getStatus() + "/" + formText);
            if (formDoc != null) {
                List<NodeItem> formTitles = formDoc.itemsOf(DocumentKind.TITLE);
                check("Form 水印没占住主标题位", !formTitles.isEmpty()
                        && tight(formTitles.get(0).getText()).startsWith("chapter"),
                        formTitles.isEmpty() ? "无主标题" : tight(formTitles.get(0).getText()));
                check("Form 水印样本确实产出表格（不然剔除断言会空过）",
                        formDoc.countOf(DocumentKind.TABLE) >= 1,
                        String.valueOf(formDoc.countOf(DocumentKind.TABLE)));
                check("Form 水印样本正文完好",
                        tight(formText).contains("parsingpipeline") && tight(formText).contains("cellr"), formText);
            }
            // 只按整行剔水印的 tika-pdf 也必须吃同一份预剔除：企业样本里这类水印会被抽成带空格的单字
            // 或 DIS / IN 这样的短片段，整行比对与碎片下限都拦不住（实测残留 8 行）
            ConversionResult formTika = converter.convert(DocumentSource.fromBytes("watermark-form.pdf", stamped),
                    ConvertOptions.defaults().extractImages(false).forceBackend("tika-pdf"));
            String tikaText = formTika.getDocument() == null ? String.join("；", formTika.getErrors())
                    : formTika.getDocument().toText();
            check("Form 水印在 tika-pdf 上也被剔除（会被拆成短片段贴进正文行）",
                    formTika.isSuccess() && !hasWatermarkWord(tikaText), formTika.getStatus() + "/" + tikaText);
        }

        ConversionResult layer = converter.convert(DocumentSource.fromBytes("searchable.pdf", searchable), options);
        QunxuDocument layerDoc = layer.getDocument();
        String layerText = layerDoc == null ? String.join("；", layer.getErrors()) : tight(layerDoc.toText());
        check("隐形文字层扫描件仍能入库", layerDoc != null && layer.isSuccess(),
                layer.getStatus() + "/" + layer.getErrors());
        check("隐形 OCR 文字层没被当水印删掉", layerText.contains("pumpbearing"), layerText);
    }

    /** 标题骨架签名：kind + level + 紧凑文案，用于比对加水印前后的结构差异 */
    private static String headingSignature(QunxuDocument document) {
        List<String> headings = new ArrayList<>();
        for (NodeItem item : document.getItems()) {
            if (item.isHeading()) {
                int level = item instanceof NodeItem.HeadingItem heading ? heading.getLevel() : 1;
                headings.add(item.getKind() + "/L" + level + "/" + tight(item.getText()));
            }
        }
        return String.join(" | ", headings);
    }

    /** 从生成目录里取样本字节 */
    private static byte[] sampleBytes(DocumentModuleProperties properties, String name) throws Exception {
        Path sample = SampleDocumentFactory.generate(Path.of(properties.getImage().getLocalRoot(), "samples"))
                .stream().filter(path -> name.equals(path.getFileName().toString()))
                .findFirst().orElseThrow(() -> new IOException("缺样本 " + name));
        return Files.readAllBytes(sample);
    }

    /** 比全文案用：去掉全部空白并转小写，避开分栏/换行带来的差异 */
    private static String tight(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    /** 水印词是否以独立单词出现（大小写不敏感）。样本里的水印就是这五个词，正文故意一个都没用 */
    private static boolean hasWatermarkWord(String text) {
        return text != null && WATERMARK_WORD.matcher(text).find();
    }

    /**
     * 扫描件（无文本层 PDF）：必须判失败。
     * <p>
     * 这类文件的解析产出非空（整页位图 → PICTURE 节点），按「有没有节点」判会静默成功：
     * 图片文件名被当成正文向量化入库，且这份空壳结果被写进解析缓存长期命中，
     * 后续即使接上 OCR 也拿不到新结果。四项断言分别钉住状态、原因、原因可定位、不进缓存。
     */
    private static void scannedPdf(DocumentConverter converter, DocumentModuleProperties properties) {
        byte[] bytes;
        try {
            Path sample = SampleDocumentFactory.generate(Path.of(properties.getImage().getLocalRoot(), "samples"))
                    .stream().filter(path -> "scanned.pdf".equals(path.getFileName().toString()))
                    .findFirst().orElseThrow();
            bytes = Files.readAllBytes(sample);
        } catch (Exception e) {
            check("生成扫描件样本", false, e.getMessage());
            return;
        }
        // 开图片抽取：扫描件唯一的产出就是图片，不开的话测不到「有节点但无文字」这条真实路径
        ConvertOptions options = ConvertOptions.defaults().extractImages(true);
        ConversionResult result = converter.convert(DocumentSource.fromBytes("scanned.pdf", bytes), options);
        check("扫描件判失败而非静默成功", result.getStatus() == ConversionStatus.FAILURE,
                result.getStatus() + "/" + result.getBackendKey());
        String reason = String.join("；", result.getErrors());
        check("失败原因写明无文本层与需 OCR", reason.contains("无文本层") && reason.contains("OCR"), reason);
        check("失败原因可定位到具体解析器", reason.contains("opendataloader"), reason);
        ConversionResult again = converter.convert(DocumentSource.fromBytes("scanned.pdf", bytes), options);
        check("扫描件空壳结果不写解析缓存", !again.isFromCache(), String.valueOf(again.getStatus()));
    }

    /**
     * 扫描件 OCR（页级兜底）：钱只花在“缺文字的页”上，且只花一次。
     * <p>
     * 六件事必须同时在，缺一条就是生产事故：
     * ① 不请求不做（默认关，{@code doOcr=false} 时引擎调用数为 0）；
     * ② 有文字层的页不再重复识别（混合文档不为已抽到的字二次付费）；
     * ③ 补出的文字落回自己那一页（混排文档按追加会让阅读顺序与面包屑全错）；
     * ④ 识别结果随中间格式进缓存（否则每次重切分重付一遍）；
     * ⑤ 单页失败逐页隔离，不让一页拖垮整篇；
     * ⑥ 页数上限与栅格超限都要写告警，“只 OCR 了一半”必须是可看见的事实而不是静默交半份正文。
     */
    private static void scannedOcr(DocumentModuleProperties properties) {
        byte[] scanned;
        byte[] searchable;
        try {
            scanned = sampleBytes(properties, "scanned.pdf");
            searchable = sampleBytes(properties, "searchable.pdf");
        } catch (Exception e) {
            check("生成 OCR 样本", false, e.getMessage());
            return;
        }

        StubPageOcr engine = new StubPageOcr();
        DocumentConverter converter = buildConverter(properties, List.of(ocrProcessor(engine, properties)));
        ConvertOptions options = ConvertOptions.defaults().extractImages(true)
                .doOcr(true).ocrEngine("stub:mock-ocr").language("zh-CN");
        ConversionResult result = converter.convert(DocumentSource.fromBytes("ocr-scanned.pdf", scanned), options);
        QunxuDocument document = result.getDocument();
        check("扫描件开启 OCR 后解析成功", result.isSuccess() && document != null,
                result.getStatus() + "/" + result.getErrors());
        check("无文字层的页逐页送识别（2 页样本调 2 次）", engine.calls == 2, "实际 " + engine.calls + " 次");
        check("语言参数透传到识别引擎", "zh-CN".equals(engine.lastLanguage), String.valueOf(engine.lastLanguage));
        if (document != null) {
            String text = document.toText();
            check("OCR 文字按页归位（第一页在第二页之前）",
                    text.indexOf("第一页正文") >= 0 && text.indexOf("第二页正文") > text.indexOf("第一页正文"),
                    preview(text));
            check("OCR 段落带所属页码", document.getItems().stream()
                    .filter(item -> item.getKind() == DocumentKind.PARAGRAPH)
                    .allMatch(item -> item.pageIndex() >= 1), null);
            check("Markdown 标记还原成语义节点（标题与列表）",
                    document.headingCount() >= 1 && document.countOf(DocumentKind.LIST_ITEM) >= 1,
                    "headings=" + document.headingCount() + " list=" + document.countOf(DocumentKind.LIST_ITEM));
            ConversionResult cached = converter.convert(
                    DocumentSource.fromBytes("ocr-scanned.pdf", scanned), options);
            check("OCR 结果进解析缓存，二次转换不再花钱", cached.isFromCache() && engine.calls == 2,
                    "cache=" + cached.isFromCache() + " calls=" + engine.calls);
        }

        // 有文字层的页不能重复付费：隐形文字层样本是“能直接入库的扫描件”，开 OCR 也应一页都不识别
        StubPageOcr layered = new StubPageOcr();
        DocumentConverter layerConverter = buildConverter(properties, List.of(ocrProcessor(layered, properties)));
        ConversionResult layer = layerConverter.convert(
                DocumentSource.fromBytes("ocr-searchable.pdf", searchable),
                ConvertOptions.defaults().extractImages(false).doOcr(true).ocrEngine("stub:mock-ocr"));
        check("有文字层的页不送识别（页级兜底）", layer.isSuccess() && layered.calls == 0,
                layer.getStatus() + "/calls=" + layered.calls);

        // 页数上限：超上限只处理前 N 页，并且必须把这半件事告诉调用方
        StubPageOcr capped = new StubPageOcr();
        DocumentConverter capConverter = buildConverter(properties, List.of(ocrProcessor(capped, properties)));
        ConversionResult capped1 = capConverter.convert(DocumentSource.fromBytes("ocr-cap.pdf", scanned),
                ConvertOptions.defaults().extractImages(true).doOcr(true).ocrMaxPages(1));
        check("页数上限只识别前 N 页", capped.calls == 1, "实际 " + capped.calls + " 次");
        check("超出页数上限写进告警", capped1.getDocument() != null && capped1.getDocument().getWarnings()
                .stream().anyMatch(w -> w.contains("上限")),
                capped1.getDocument() == null ? String.valueOf(capped1.getErrors())
                        : String.valueOf(capped1.getDocument().getWarnings()));

        // 逐页隔离：一个模型调用失败只该少一页正文，不该让整篇报错
        StubPageOcr flaky = new StubPageOcr();
        flaky.failSecondPage = true;
        DocumentConverter flakyConverter = buildConverter(properties, List.of(ocrProcessor(flaky, properties)));
        ConversionResult partial = flakyConverter.convert(DocumentSource.fromBytes("ocr-flaky.pdf", scanned),
                ConvertOptions.defaults().extractImages(true).doOcr(true).ocrEngine("stub:mock-ocr"));
        check("单页识别失败不拖垮整篇", partial.isSuccess() && partial.getDocument() != null
                && partial.getDocument().toText().contains("第一页正文"),
                partial.getStatus() + "/" + partial.getErrors());
        check("识别失败页数以告警可追溯", partial.getDocument() != null && partial.getDocument().getWarnings()
                .stream().anyMatch(w -> w.contains("识别失败")), null);

        // 没引擎时不能静默成功：除了原有失败原因，必须多一条“已请求但未配引擎”，
        // 否则运维看到的是同一句话，分不清“没开 OCR”与“开了但配错”
        DocumentConverter noEngine = buildConverter(properties,
                List.of(ocrProcessor(null, properties)));
        ConversionResult missing = noEngine.convert(DocumentSource.fromBytes("ocr-none.pdf", scanned),
                ConvertOptions.defaults().extractImages(true).doOcr(true));
        check("未配识别引擎时仍判失败", !missing.isSuccess(), String.valueOf(missing.getStatus()));
        String missingReason = String.join("；", missing.getErrors());
        check("失败原因写明未配置引擎", missingReason.contains("未配置识别引擎"), missingReason);

        // 不请求就不花钱（与上面 scannedPdf 用例的默认选项共用口径）
        StubPageOcr idle = new StubPageOcr();
        DocumentConverter idleConverter = buildConverter(properties, List.of(ocrProcessor(idle, properties)));
        idleConverter.convert(DocumentSource.fromBytes("ocr-idle.pdf", scanned),
                ConvertOptions.defaults().extractImages(true));
        check("未请求 OCR 时引擎一次不调", idle.calls == 0, "实际 " + idle.calls + " 次");
    }

    /**
     * OCR 开关与缓存指纹：两个方向都不能错。
     * 默认关的调用方指纹必须逐字节不变（否则加这几个字段本身就把宿主存量解析缓存打成永久未命中），
     * 而开了 OCR 后换引擎必须变指纹（否则换了模型的效果永远命中旧缓存）。
     */
    private static void ocrFingerprint() {
        // 基线串写死在此，相当于把“只有这四个字段进默认指纹”的纪律钉住：
        // 任何人往后一加会改变结果的开关，这条就会拦住他要求同时交代缓存失效影响
        String baseline = Sha256Util.sha256("extractImages=true|maxPages=|language=|sheetNames=|");
        String defaultFp = ConvertOptions.defaults().fingerprint();
        check("默认选项指纹不受 OCR 字段影响", baseline.equals(defaultFp), abbreviate(defaultFp));
        String on = ConvertOptions.defaults().doOcr(true).fingerprint();
        check("开启 OCR 后指纹变化", !on.equals(defaultFp), null);
        String otherEngine = ConvertOptions.defaults().doOcr(true).ocrEngine("siliconflow/PaddleOCR-VL")
                .fingerprint();
        check("换 OCR 引擎指纹再变（不会命中旧模型的缓存）", !otherEngine.equals(on), null);
        check("OCR 页数上限也进指纹", !ConvertOptions.defaults().doOcr(true).ocrMaxPages(5).fingerprint()
                .equals(ConvertOptions.defaults().doOcr(true).ocrMaxPages(6).fingerprint()), null);
        check("强制重解析不进指纹", ConvertOptions.defaults().forceReparse(true).fingerprint().equals(defaultFp),
                null);
    }

    /** OCR 桩：按页返回可核对的文本并记录调用次数（“钱花在哪”只能靠计数断言） */
    private static final class StubPageOcr implements PageOcr {

        private int calls;
        private String lastLanguage;
        private boolean failSecondPage;

        @Override
        public String key() {
            return "stub-ocr";
        }

        @Override
        public String recognize(OcrRequest request) {
            calls++;
            lastLanguage = request.language();
            if (request.imageBytes() == null || request.imageBytes().length == 0) {
                throw new IllegalStateException("栅格图字节为空（页 " + request.pageNumber() + "）");
            }
            if (failSecondPage && request.pageNumber() == 2) {
                throw new IllegalStateException("模拟识别服务超时");
            }
            if (request.pageNumber() == 1) {
                return "# 识别出的标题\n\n第一页正文内容。";
            }
            return "第二页正文内容。\n- 项目一\n- 项目二";
        }
    }

    /** JSON 往返后 parent/children 必须仍指向同名节点（缓存还原的正确性底线） */
    private static boolean keepsTree(QunxuDocument origin, QunxuDocument restored) {
        for (NodeItem item : origin.getItems()) {
            NodeItem copy = restored.item(item.getIndex());
            if (copy == null || !Objects.equals(copy.getText(), item.getText())) {
                return false;
            }
            if (Objects.equals(item.getParentIndex(), copy.getParentIndex())) {
                continue;
            }
            NodeItem originParent = origin.item(item.getParentIndex());
            NodeItem copyParent = restored.item(copy.getParentIndex());
            if (originParent == null || copyParent == null
                    || !Objects.equals(originParent.getText(), copyParent.getText())) {
                return false;
            }
        }
        return true;
    }

    /** 标题类节点总数（TITLE 与 SECTION_HEADER 合算，markdown 首标题会被当作文档主标题） */
    private static int headings(QunxuDocument document) {
        return document.countOf(DocumentKind.TITLE) + document.countOf(DocumentKind.SECTION_HEADER)
                + document.countOf(DocumentKind.SHEET);
    }

    /** L3：运行期注册桩解析器（order 最小且全量支持），验证它能压过内置实现 */
    private static void l3Extension(DocumentConverter converter) {
        DocumentBackend stub = new DocumentBackend() {
            @Override
            public String key() {
                return "stub-zzz";
            }

            @Override
            public int order() {
                return -100;
            }

            @Override
            public boolean supports(DocumentFormat format) {
                return format == DocumentFormat.TEXT;
            }

            @Override
            public QunxuDocument parse(ParseContext context) {
                QunxuDocument document = context.newDocument();
                document.append(NodeItem.paragraph("桩解析器产物"));
                return document;
            }
        };
        byte[] txt = "桩测文本内容\n第二行\n".getBytes(StandardCharsets.UTF_8);
        ConversionResult baseline = converter.convert(DocumentSource.fromBytes("stub.txt", txt),
                ConvertOptions.defaults());
        converter.register(stub);
        ConversionResult hit = converter.convert(DocumentSource.fromBytes("stub.txt", txt),
                ConvertOptions.defaults().forceReparse(true));
        check("L3 注册的解析器压过内置实现", "plaintext".equals(baseline.getBackendKey())
                && "stub-zzz".equals(hit.getBackendKey()),
                baseline.getBackendKey() + " -> " + hit.getBackendKey());
        converter.unregister(stub.key());
        ConversionResult restored = converter.convert(DocumentSource.fromBytes("stub.txt", txt),
                ConvertOptions.defaults().forceReparse(true));
        check("L3 注销后恢复内置实现", "plaintext".equals(restored.getBackendKey()),
                restored.getBackendKey());
    }

    /** L2：配置的首选实现优先级高于内置 order */
    private static void l2Preferred(DocumentConverter converter) {
        byte[] txt = "一段文本\n另一段文本\n".getBytes(StandardCharsets.UTF_8);
        ConversionResult before = converter.convert(DocumentSource.fromBytes("pref.txt", txt),
                ConvertOptions.defaults());
        converter.preferBackend(DocumentFormat.TEXT.name(), DocumentConverter.FALLBACK_BACKEND_KEY);
        ConversionResult after = converter.convert(DocumentSource.fromBytes("pref.txt", txt),
                ConvertOptions.defaults().forceReparse(true));
        check("L2 配置覆盖内置路由", "plaintext".equals(before.getBackendKey())
                && DocumentConverter.FALLBACK_BACKEND_KEY.equals(after.getBackendKey()),
                before.getBackendKey() + " -> " + after.getBackendKey());
        converter.preferBackend(DocumentFormat.TEXT.name(), "plaintext");
    }

    /**
     * 结构化 PDF 解析：首选实现、元素级 bbox、页码与页面表必须在位；
     * 主解析器报错时必须退回同格式的 {@code tika-pdf}，而不是掉到无版面的 {@code tika-fallback}。
     * <p>
     * 降级测试用同 key 的报错桩位<b>替换</b>真实实现，跑完再装回去：链上只剩一个 PDF 实现时
     * 才能确定性地看到 {@code tika-pdf} 顶上来。
     */
    private static void structuredPdf(DocumentConverter converter, DocumentModuleProperties properties) {
        Path sample;
        try {
            sample = SampleDocumentFactory.generate(Path.of(properties.getImage().getLocalRoot(), "samples"))
                    .stream().filter(path -> "sample.pdf".equals(path.getFileName().toString()))
                    .findFirst().orElseThrow();
        } catch (Exception e) {
            check("生成 PDF 样本", false, e.getMessage());
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sample);
        } catch (IOException e) {
            check("读取 PDF 样本", false, e.getMessage());
            return;
        }
        ConvertOptions options = ConvertOptions.defaults().extractImages(false);
        DocumentBackend routed = converter.route(DocumentFormat.PDF, options);
        check("PDF 首选结构化解析器", routed != null && "opendataloader".equals(routed.key()),
                routed == null ? "无" : routed.key());
        ConversionResult result = converter.convert(DocumentSource.fromBytes("sample.pdf", bytes), options);
        QunxuDocument document = result.getDocument();
        if (document == null || document.isEmpty()) {
            check("结构化解析产出内容", false, String.valueOf(result.getErrors()));
        } else {
            check("结构化解析给出元素级 bbox", document.getItems().stream()
                    .anyMatch(item -> !item.getProv().isEmpty() && item.getProv().get(0).bbox() != null), null);
            check("每个元素都带页码", document.getItems().stream()
                    .allMatch(item -> item.pageIndex() >= 1), null);
            check("页面表按 MediaBox 填尺寸", !document.getPages().isEmpty()
                    && document.getPages().get(0).width() > 0 && document.getPages().get(0).height() > 0, null);
            check("结构化解析识别标题", document.headingCount() >= 1,
                    String.valueOf(document.headingCount()));
        }
        if (routed == null) {
            return;
        }
        DocumentBackend broken = new DocumentBackend() {
            @Override
            public String key() {
                return routed.key();
            }

            @Override
            public int order() {
                return routed.order();
            }

            @Override
            public boolean supports(DocumentFormat format) {
                return format == DocumentFormat.PDF;
            }

            @Override
            public QunxuDocument parse(ParseContext context) throws Exception {
                throw new IllegalStateException("模拟结构化解析器故障");
            }
        };
        converter.register(broken);
        ConversionResult degraded = converter.convert(DocumentSource.fromBytes("sample.pdf", bytes),
                options.forceReparse(true));
        check("主解析器报错时退回同格式的 tika-pdf", "tika-pdf".equals(degraded.getBackendKey())
                && degraded.isSuccess(), degraded.getBackendKey() + "/" + degraded.getStatus());
        check("降级事实写进告警可追溯", degraded.getDocument() != null
                && degraded.getDocument().getWarnings().stream().anyMatch(w -> w.contains(routed.key())), null);
        converter.unregister(broken.key());
        converter.register(routed);
    }

    /** SSRF 闸门：白名单为空即禁用；放行域名但目标为内网时仍拒绝 */
    private static void ssrfGates() {
        try {
            new SsrfGuard(UrlOptions.defaults()).validate("http://localhost/a.pdf");
            check("白名单为空时拒绝", false, "未抛异常");
        } catch (IllegalStateException e) {
            check("白名单为空时拒绝并给出配置项名", SsrfGuard.DISABLED_MESSAGE.equals(e.getMessage()), e.getMessage());
        }
        UrlOptions openLocal = new UrlOptions(List.of("localhost"), 52_428_800L, 10, 60, 3, true);
        try {
            new SsrfGuard(openLocal).validate("http://localhost:8000/a.pdf");
            check("白名单放行 + 显式允许内网时可用", true, null);
        } catch (Exception e) {
            check("白名单放行 + 显式允许内网时可用", false, e.getMessage());
        }
        UrlOptions denyPrivate = new UrlOptions(List.of("localhost"), 52_428_800L, 10, 60, 3, false);
        try {
            new SsrfGuard(denyPrivate).validate("http://localhost:8000/a.pdf");
            check("域名放行但解析到回环地址时仍拒绝", false, "未抛异常");
        } catch (Exception e) {
            check("域名放行但解析到回环地址时仍拒绝", true, null);
        }
        try {
            new SsrfGuard(new UrlOptions(List.of(".example.com"), 52_428_800L, 10, 60, 3, true))
                    .validate("ftp://a.example.com/x.pdf");
            check("非 http(s) 协议被拒", false, "未抛异常");
        } catch (Exception e) {
            check("非 http(s) 协议被拒", true, null);
        }
    }

    // ==================== 输出与断言 ====================

    private static void check(String name, boolean ok, String actual) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.printf("  [%s] %s%s%n", ok ? "PASS" : "FAIL", name,
                ok || actual == null ? "" : "（实际：" + actual + "）");
    }

    private static String preview(String text) {
        String oneLine = text == null ? "" : text.replace("\n", "⏎");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private static String abbreviate(String hash) {
        return hash == null || hash.isBlank() ? "-" : hash.substring(0, Math.min(12, hash.length()));
    }
}

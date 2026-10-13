package tech.qunxu.module.document.backend;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.image.ImageExtractor;
import tech.qunxu.module.document.model.BBox;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.model.ProvenanceItem;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * PDF 结构化解析器（OpenDataLoader）：版面级能力的来源，{@code tika-pdf} 拿不到的东西都从这里拿。
 * <p>
 * 相比 {@link PdfTikaBackend} 的行启发式，本实现多出四项：<b>阅读顺序</b>（XY-cut 重建，双栏/多栏不再串行）、
 * <b>元素级 bbox</b>（引用面板与后续版面级功能的地基）、<b>表格结构</b>（含合并单元格与表头判定）、
 * <b>标题层级</b>（来自结构树/字号推断，不靠我们自己设阈值）。
 * <p>
 * 为什么走「写临时目录 → 回读 JSON」而不是库内的内存 API（{@code DocumentProcessor.extractContents}）：
 * 前者是官方对外承诺的稳定面（CLI 与 Java 快速开始都走这条），后者的返回类型
 * {@code List<List<IObject>>} 是 veraPDF 的内部实体，版本升级即破。多一次临时文件读写的代价
 * 相对解析耗时（约 0.02s/页）可忽略，换来的是映射层只依赖 JSON 字段名。
 * <p>
 * <b>并发</b>：{@code DocumentProcessor} 内部存在静态可变状态（如 {@code closePdfResources()}
 * 注册的清理步骤），官方没有给出线程安全承诺。这里用类级锁把整段解析串行化 ——
 * 知识库导入是批量异步任务，串行不会成为吞吐瓶颈，而换来的是「不会偶发脏结果」。
 * <p>
 * 映射原则：一种 JSON {@code type} 对应一个中间格式角色，未识别的类型按普通段落处理，
 * 保证 {@code isPureTable()}／{@code headingCount()} 这些下游判定不因新类型而改变语义。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class OpenDataLoaderPdfBackend implements DocumentBackend, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderPdfBackend.class);

    /** 串行化 processFile：库内静态状态无并发承诺（见类注释） */
    private static final ReentrantLock PARSE_LOCK = new ReentrantLock();

    /**
     * 收掉 OpenDataLoader 的 JUL 噪音日志。
     * <p>
     * 该库用 {@code java.util.logging} 且默认 INFO 级，每解析一个 PDF 就打「信息: Number of pages」
     * 「信息: Processing N pages with 1 threads」等数行；而 JUL 并不会自动进 SLF4J —— Spring Boot
     * 只带 {@code jul-to-slf4j} 依赖，桥接要宿主显式 {@code SLF4JBridgeHandler.install()}。本项目没装，
     * 于是这些日志绕过 logback 直写 stderr：既进不了日志文件，也没法用日志配置压级别，
     * 批量导入时会按页刷量。这里只降到 WARNING（库的真问题都在 WARN/ERROR），
     * 宿主已自行设过该 logger 级别时不覆盖。
     * <p>
     * 必须用静态字段持有 logger 引用：JUL 的配置随 logger 对象一起被回收（JDK 文档里
     * “Logger Configuration, Garbage Collection, and You” 那个陷阱），只写
     * {@code Logger.getLogger(…).setLevel(…)} 不引用返回值时，一次 GC 后 INFO 就会重新冒出来。
     */
    private static final java.util.logging.Logger LIBRARY_LOGGER = initLibraryLogger();

    private static java.util.logging.Logger initLibraryLogger() {
        java.util.logging.Logger libraryLogger = java.util.logging.Logger.getLogger("org.opendataloader");
        if (libraryLogger.getLevel() == null) {
            libraryLogger.setLevel(java.util.logging.Level.WARNING);
        }
        return libraryLogger;
    }

    /** 输入用固定 ASCII 名，产物名即可预测（{@code input.json} / {@code input_images/}），不受原始文件名乱码影响 */
    private static final String INPUT_PDF = "input.pdf";
    private static final String OUTPUT_JSON = "input.json";

    /** 无替代文本时 OpenDataLoader 写的是字面量 missing，不能当成 alt 内容用 */
    private static final String ALT_MISSING = "missing";

    /** 列表项正文需要并段，但嵌套列表与表格要留作独立节点 */
    private static final Set<String> LIST_ITEM_SKIP = Set.of("list", "table");

    /** 单元格内允许出现图片，嵌套表格的文本不并入当前单元格 */
    private static final Set<String> CELL_SKIP = Set.of("table");

    /** 递归收集文本时可能承载子内容的数组字段 */
    private static final String[] TEXT_CONTAINERS = {"kids", "list items", "toc items"};

    private volatile boolean closed;

    @Override
    public String key() {
        return "opendataloader";
    }

    @Override
    public String label() {
        return "PDF(OpenDataLoader)";
    }

    /**
     * v2：水印预剔除跟进 Form XObject，并按字库解码字串。真实企业受控文件的水印绝大多数不画在页面流里，
     * 而是整块做成一个被多页共用的 Form、由 {@code cm} 旋转后 {@code Do} 进来；不跟进 Form 时，结构化解析器
     * 会把水印散字合并进邻近的表格单元格（实测 16 页样本泄漏 6 处），既删不掉节点也救不回版式。而这类串的
     * 编码是 CID 字库（实测 SimSun + GBK-EUC-H），按默认解码读到的是乱码，永远与已判定水印比不中。
     * 抽取语义变了，旧缓存必须自然失效。
     */
    @Override
    public int version() {
        return 2;
    }

    /** 小于 {@code tika-pdf}（20）：同格式下结构化解析优先，失败时由降级链退回 */
    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PDF;
    }

    /**
     * 释放 OpenDataLoader 的 HTTP 客户端线程池。
     * <p>
     * 库把它设计成「进程退出时调一次」，因此只允许在 Spring 销毁 Bean 时触发；
     * 每次解析都调会反复销毁再重建全局客户端。我们不开 hybrid 模式，通常无事发生。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            OpenDataLoaderPDF.shutdown();
        } catch (Exception e) {
            log.debug("OpenDataLoader 资源释放失败：{}", e.getMessage());
        }
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        QunxuDocument document = context.newDocument();
        byte[] bytes = context.bytes();
        int maxPage = readPageSizes(document, bytes, context.options());
        // 先扫水印再解：结构化解析器按整页字号聚类推断层级，水印留在文件里就会被库判成一级标题，
        // 并把落在它外接框里的真实标题一并吞掉（实测）。能抹掉就给库一份没水印的字节，
        // 抹不掉才退化到只删产出节点。文档指纹与页尺寸仍按原字节，不受改写影响。
        PdfWatermarkFilter.Result watermark = PdfWatermarkFilter.scan(bytes, maxPage);
        byte[] parseBytes = watermark.parseBytes(bytes);
        Path workDir = Files.createTempDirectory("qunxu-odl-pdf-");
        try {
            Path pdf = workDir.resolve(INPUT_PDF);
            Files.write(pdf, parseBytes);
            process(workDir, pdf, context.extractImages());
            Path json = resolveJson(workDir);
            if (json == null) {
                throw new IllegalStateException("OpenDataLoader 未产出 JSON 结果");
            }
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(json), StandardCharsets.UTF_8));
            State state = new State(document, workDir, context.images(), maxPage, watermark);
            visitChildren(state, root == null ? null : root.getJSONArray("kids"));
            if (state.watermarkDropped > 0) {
                log.info("OpenDataLoader 剔除 {} 个水印节点（按内容流绘制状态判定，不计入告警）", state.watermarkDropped);
            }
            restoreHeadingLevels(state);
            requireTrustworthyText(document, parseBytes, maxPage);
            return document;
        } finally {
            deleteTree(workDir);
        }
    }

    // ==================== 调用库 ====================

    private void process(Path workDir, Path pdf, boolean extractImages) throws Exception {
        Config config = new Config();
        config.setOutputFolder(workDir.toString());
        config.setGenerateJSON(true);
        config.setGenerateMarkdown(false);
        config.setGenerateHtml(false);
        config.setGenerateText(false);
        config.setGeneratePDF(false);
        config.setGenerateTaggedPDF(false);
        // 图片只在需要落地时导出（external 才产文件，embedded 只给 base64，二者都不需要时直接 off 省 IO）
        config.setImageOutput(extractImages ? Config.IMAGE_OUTPUT_EXTERNAL : Config.IMAGE_OUTPUT_OFF);
        config.setImageFormat(Config.IMAGE_FORMAT_PNG);
        // 页眉页脚不进中间格式（库默认行为）：它们本来就会被分块阶段丢掉，取回来只会污染向量召回。
        // 代价是库会整块丢弃它判为装饰的区域，版式特殊的 PDF 有吞正文的风险 —— 由可信度闸门兜底。
        config.setIncludeHeaderFooter(false);
        // tableMethod / readingOrder / useStructTree 保持库默认（cluster 表格与 XY-cut 顺序已在真实业务 PDF 上验证）
        PARSE_LOCK.lock();
        try {
            OpenDataLoaderPDF.processFile(pdf.toString(), config);
        } finally {
            PARSE_LOCK.unlock();
        }
    }

    /** 产物名规则是「输入同名 + .json」，这里按名取，取不到再退化为目录里第一个 json */
    private Path resolveJson(Path workDir) throws IOException {
        Path expected = workDir.resolve(OUTPUT_JSON);
        if (Files.isRegularFile(expected)) {
            return expected;
        }
        try (Stream<Path> files = Files.list(workDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .min(Comparator.comparing(Path::toString))
                    .orElse(null);
        }
    }

    // ==================== 结果可信度 ====================

    /**
     * 结构化结果的可信度闸门。
     * <p>
     * 库默认 {@code includeHeaderFooter=false}：它认定的页眉页脚区域会被<b>整块丢弃</b>，版式特殊的
     * PDF（正文也挤在页面上半部、多页版式一致）会把正文一起判成装饰。这种时候「有产出的错结果」
     * 比「没产出」危险得多 —— 缓存一旦写入，下游看不出正文少了，而 {@code tika-pdf} 明明抽得到。
     * 因此用 PDFBox 的纯文本量做对照，结构化文本明显偏少时抛错，交给降级链重解。
     * <p>
     * 阈值取 30% 而不是 50%：库默认会过滤背景水印与极小字号，这些本来就不该进正文，
     * 允许一定的合理减少；真正出问题的是整段丢失。对照抽取只在结果可疑时才跑，正常文档零额外开销。
     * <p>
     * 无文本层（扫描件）不抛错：抛错会白走一轮降级（同格式其他 backend 同样抽不到字），
     * 且错误文案会被当成「解析器故障」而非「需要 OCR」。只把事实写进告警，
     * 失败与「不写缓存」由 {@code DocumentConverter} 按可索引文本量统一裁决（所有格式共用一个口径）。
     */
    private void requireTrustworthyText(QunxuDocument document, byte[] bytes, int maxPage) throws IOException {
        long structured = document.indexableTextLength();
        long plain = countPlainChars(bytes, maxPage);
        if (plain <= 0) {
            // 没有文本层（扫描件 / 纯图形页）：结构化结果只剩图片属正常，但要给出可诊断的原因
            document.warn("PDF 无文本层（疑似扫描件），未抽到任何正文文字，需 OCR 解析器");
            return;
        }
        if (structured * 10 < plain * 3) {
            throw new IllegalStateException("OpenDataLoader 结构化文本 " + structured
                    + " 字符远少于页面文本 " + plain + " 字符（版面把正文误判为页眉页脚），改用 tika-pdf");
        }
    }

    /** PDFBox 纯文本量（不排序、不按版面拼装，只要一个可对账的字符数） */
    private long countPlainChars(byte[] bytes, int maxPage) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(false);
            stripper.setStartPage(1);
            stripper.setEndPage(maxPage <= 0 ? pdf.getNumberOfPages() : maxPage);
            return stripper.getText(pdf).replaceAll("\\s+", "").length();
        } catch (Exception e) {
            log.debug("页面文本量对照失败，跳过可信度校验：{}", e.getMessage());
            return 0;
        }
    }

    // ==================== 页面尺寸 ====================

    /**
     * OpenDataLoader 的 JSON 不含页面尺寸，用 PDFBox 只读 MediaBox 补齐页面表，
     * 否则 bbox 无法与页面比例对应（引用面板要按 bbox 在页面上画高亮框）。
     *
     * @return 允许解析的最大页码（0 表示不限）
     */
    private int readPageSizes(QunxuDocument document, byte[] bytes, ConvertOptions options) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int total = pdf.getNumberOfPages();
            document.getMeta().setPageCount(total);
            Integer maxPages = options.getMaxPages();
            int limit = maxPages == null || maxPages <= 0 ? total : Math.min(total, maxPages);
            if (limit < total) {
                document.warn("PDF 共 " + total + " 页，按 maxPages 只解析前 " + limit + " 页");
            }
            for (int page = 1; page <= limit; page++) {
                float width = 0;
                float height = 0;
                try {
                    PDRectangle box = pdf.getPages().get(page - 1).getMediaBox();
                    if (box != null) {
                        width = box.getWidth();
                        height = box.getHeight();
                    }
                } catch (Exception e) {
                    log.debug("PDF 第 {} 页尺寸读取失败：{}", page, e.getMessage());
                }
                document.addPage(page, width, height);
            }
            return limit;
        } catch (Exception e) {
            // 页面表缺失不影响结构化结果，只在 bbox 定位时降级为「无尺寸」
            log.warn("PDF 页面尺寸批量读取失败：{}", e.getMessage());
            return 0;
        }
    }

    // ==================== 节点映射 ====================

    private void visitChildren(State state, JSONArray children) {
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            JSONObject node = children.getJSONObject(i);
            if (node != null) {
                visitNode(state, node);
            }
        }
    }

    private void visitNode(State state, JSONObject node) {
        if (!state.pageAllowed(node)) {
            return;
        }
        String type = node.getString("type");
        switch (type == null ? "" : type) {
            case "heading" -> {
                String text = gatherText(node, Set.of());
                if (!text.isEmpty()) {
                    appendHeading(state, node.getIntValue("heading level"), text, node);
                }
            }
            case "list" -> visitList(state, node);
            case "table" -> visitTable(state, node);
            case "image" -> addPicture(state, node);
            case "toc" -> visitChildren(state, node.getJSONArray("toc items"));
            default -> {
                String content = trimToEmpty(node.getString("content"));
                if (!content.isEmpty()) {
                    appendText(state, textKind(type), content, node);
                }
                // 段落里可能内嵌图片/表格/列表，子节点仍按各自类型展开，保证不丢版面信息
                visitChildren(state, node.getJSONArray("kids"));
            }
        }
    }

    /** 未识别的文本类型一律按段落，避免新枚举值把下游的段落统计判定打乱 */
    private DocumentKind textKind(String type) {
        return switch (type == null ? "" : type) {
            case "caption" -> DocumentKind.CAPTION;
            case "formula" -> DocumentKind.FORMULA;
            case "header" -> DocumentKind.PAGE_HEADER;
            case "footer" -> DocumentKind.PAGE_FOOTER;
            default -> DocumentKind.PARAGRAPH;
        };
    }

    private void appendHeading(State state, int level, String text, JSONObject node) {
        if (isWatermark(state, text)) {
            return;
        }
        // 主标题只认「还没出过任何标题」时的第一个一级标题：先出现二级标题的文档里，
        // 后面的水印行常被库判成 level 1，不能让它当成文档名
        DocumentKind kind = level <= 1 && !state.headingEmitted
                ? DocumentKind.TITLE : DocumentKind.SECTION_HEADER;
        state.headingEmitted = true;
        state.document.append(new NodeItem.HeadingItem(kind, Math.max(1, level), text))
                .prov(provenanceOf(node));
    }

    private void appendText(State state, DocumentKind kind, String text, JSONObject node) {
        if (isWatermark(state, text)) {
            return;
        }
        state.document.append(new NodeItem.TextItem(kind, text)).prov(provenanceOf(node));
    }

    /**
     * 水印文字不产出节点。库会把斜排大字水印当成正文结构（字号最大时直接给 level 1 标题），
     * 一旦占到主标题位，文档名与面包屑全跟错。判定见 {@link PdfWatermarkFilter}。
     */
    private boolean isWatermark(State state, String text) {
        if (!PdfWatermarkFilter.isHidden(state.watermark, text)) {
            return false;
        }
        state.watermarkDropped++;
        return true;
    }

    /**
     * 剔掉水印后把标题层级补回来。
     * <p>
     * 库按整页字号聚类推断层级：斜排大字水印占住最大那一档，真实标题就被整体压深一级——节点虽然
     * 删了，层级仍是错的（主标题位空着、面包屑多一层）。只在确实剔过水印时平移，按「现存最浅层级
     * 即为一级」归一，并把平移后的第一个一级标题补成主标题（重复执行时最浅层级已为一级，自然短路）。
     */
    private void restoreHeadingLevels(State state) {
        if (state.watermarkDropped == 0) {
            return;
        }
        int shallowest = Integer.MAX_VALUE;
        int headings = 0;
        for (NodeItem item : state.document.getItems()) {
            if (item instanceof NodeItem.HeadingItem heading) {
                headings++;
                shallowest = Math.min(shallowest, heading.getLevel());
            }
        }
        if (headings == 0 || shallowest <= 1) {
            return;
        }
        int shift = shallowest - 1;
        boolean titleFilled = false;
        for (NodeItem item : state.document.getItems()) {
            if (!(item instanceof NodeItem.HeadingItem heading)) {
                continue;
            }
            heading.setLevel(heading.getLevel() - shift);
            if (!titleFilled && heading.getLevel() <= 1) {
                heading.setKind(DocumentKind.TITLE);
                titleFilled = true;
            }
        }
        log.info("OpenDataLoader 因水印占位上提 {} 个标题共 {} 级", headings, shift);
    }

    /**
     * 列表：{@code list items} 不是 {@code kids}，且列表项正文会被拆成「项本身 + 若干续段」，
     * 这里把续段并回同一列表项，嵌套列表仍单独成项（保持阅读顺序）。
     */
    private void visitList(State state, JSONObject list) {
        JSONArray items = list.getJSONArray("list items");
        if (items == null || items.isEmpty()) {
            items = list.getJSONArray("kids");
        }
        if (items == null) {
            return;
        }
        String marker = markerOf(list.getString("numbering style"));
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null || !state.pageAllowed(item)) {
                continue;
            }
            String type = item.getString("type");
            if ("list".equals(type)) {
                visitList(state, item);
                continue;
            }
            String text = gatherText(item, LIST_ITEM_SKIP);
            if (!text.isEmpty() && !isWatermark(state, text)) {
                state.document.append(new NodeItem.ListItem(text, marker)).prov(provenanceOf(item));
            }
            visitNestedInListItem(state, item);
        }
    }

    /** 列表项内嵌的列表/表格/图片按各自类型继续展开 */
    private void visitNestedInListItem(State state, JSONObject item) {
        JSONArray kids = item.getJSONArray("kids");
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            JSONObject child = kids.getJSONObject(i);
            if (child == null) {
                continue;
            }
            String type = child.getString("type");
            if ("list".equals(type)) {
                visitList(state, child);
            } else if ("table".equals(type)) {
                visitTable(state, child);
            } else if ("image".equals(type)) {
                addPicture(state, child);
            }
        }
    }

    private String markerOf(String numberingStyle) {
        String style = numberingStyle == null ? "" : numberingStyle.toLowerCase();
        return style.contains("number") || style.contains("decimal") || style.contains("roman") ? "1." : "-";
    }

    /**
     * 表格：{@code rows[] / cells[]} 的 {@code row number}、{@code column number} 都是 1-based，
     * 中间格式用 0-based 网格，因此统一减一；表头行抽出来放 {@code headers}，数据区行号再减掉表头行数。
     */
    private void visitTable(State state, JSONObject table) {
        JSONArray rows = table.getJSONArray("rows");
        // rowCount 交给 addCell 维护：JSON 的 number of rows 含表头行，直接搬过去会让导出多出空行
        NodeItem.TableItem item = new NodeItem.TableItem(null);
        item.setColCount(table.getIntValue("number of columns"));
        List<JSONObject> cellImages = new ArrayList<>();
        int headerRows = headerRowCount(rows);
        TreeMap<Integer, StringBuilder> headers = new TreeMap<>();
        if (rows != null) {
            for (int r = 0; r < rows.size(); r++) {
                JSONObject row = rows.getJSONObject(r);
                if (row == null) {
                    continue;
                }
                int rowNumber = row.getIntValue("row number");
                if (rowNumber <= 0) {
                    rowNumber = r + 1;
                }
                JSONArray cells = row.getJSONArray("cells");
                if (cells == null) {
                    continue;
                }
                for (int c = 0; c < cells.size(); c++) {
                    JSONObject cell = cells.getJSONObject(c);
                    if (cell == null) {
                        continue;
                    }
                    int column = cell.getIntValue("column number");
                    if (column <= 0) {
                        column = c + 1;
                    }
                    collectImages(cell, cellImages);
                    String text = gatherText(cell, CELL_SKIP);
                    int rowSpan = Math.max(1, cell.getIntValue("row span"));
                    int colSpan = Math.max(1, cell.getIntValue("column span"));
                    boolean header = cell.getBooleanValue("is_header") || rowNumber <= headerRows;
                    if (header) {
                        appendHeaderCell(headers, column - 1, text);
                    } else {
                        item.addCell(new NodeItem.TableCell(text, rowNumber - 1 - headerRows,
                                column - 1, rowSpan, colSpan));
                    }
                }
            }
        }
        if (!headers.isEmpty()) {
            item.setHeaders(padHeaders(headers, item.getColCount()));
        }
        state.document.append(item).prov(provenanceOf(table));
        for (JSONObject image : cellImages) {
            addPicture(state, image);
        }
    }

    /** 表头列可能横向合并，按列位铺开并允许同一列被多行表头追加 */
    private void appendHeaderCell(TreeMap<Integer, StringBuilder> headers, int column, String text) {
        if (column < 0) {
            return;
        }
        StringBuilder builder = headers.computeIfAbsent(column, key -> new StringBuilder());
        if (!text.isEmpty()) {
            joinText(builder, text);
        }
    }

    /**
     * 表头按列位补空串：合并表头只给出最左列，缺列若不留位，导出时数据列就会整体左移。
     */
    private List<String> padHeaders(TreeMap<Integer, StringBuilder> headers, int colCount) {
        int size = Math.max(colCount, headers.lastKey() + 1);
        List<String> result = new ArrayList<>(size);
        for (int column = 0; column < size; column++) {
            StringBuilder builder = headers.get(column);
            result.add(builder == null ? "" : builder.toString().trim());
        }
        return result;
    }

    /** 顶部连续的「整行都是表头单元格」的行数；中间夹杂数据行时后面的都不算表头 */
    private int headerRowCount(JSONArray rows) {
        int count = 0;
        if (rows == null) {
            return 0;
        }
        for (int r = 0; r < rows.size(); r++) {
            JSONObject row = rows.getJSONObject(r);
            JSONArray cells = row == null ? null : row.getJSONArray("cells");
            if (cells == null || cells.isEmpty()) {
                break;
            }
            boolean allHeader = true;
            for (int c = 0; c < cells.size(); c++) {
                JSONObject cell = cells.getJSONObject(c);
                if (cell == null || !cell.getBooleanValue("is_header")) {
                    allHeader = false;
                    break;
                }
            }
            if (!allHeader) {
                break;
            }
            count++;
        }
        return count;
    }

    // ==================== 图片 ====================

    private void addPicture(State state, JSONObject node) {
        String source = trimToEmpty(node.getString("source"));
        if (source.isEmpty()) {
            // imageOutput=off 时 JSON 里没有 source，图片信息只剩版面位置，不必产空节点
            return;
        }
        ImageExtractor extractor = state.images;
        if (extractor == null) {
            return;
        }
        Path file = state.workDir.resolve(source).normalize();
        if (!file.startsWith(state.workDir) || !Files.isRegularFile(file)) {
            state.document.warn("OpenDataLoader 产出的图片缺失：" + source);
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            state.document.warn("OpenDataLoader 图片读取失败（" + source + "）：" + e.getMessage());
            return;
        }
        String name = file.getFileName().toString();
        String suffix = suffixOf(name);
        ImageRef ref = extractor.extract(data, name, mimeOf(suffix), null, null,
                "pdf page " + Math.max(1, node.getIntValue("page number")));
        if (ref != null) {
            String alt = altOf(node);
            state.document.append(NodeItem.picture(ref, alt == null ? ref.fileName() : alt))
                    .prov(provenanceOf(node));
        }
    }

    /** {@code alt_source} 缺失时库写 "missing"，{@code description} 是人工备注，二者都可能为空 */
    private String altOf(JSONObject node) {
        String alt = trimToEmpty(node.getString("alt"));
        if (alt.isEmpty()) {
            alt = trimToEmpty(node.getString("description"));
        }
        if (alt.isEmpty()) {
            String source = trimToEmpty(node.getString("alt_source"));
            alt = ALT_MISSING.equalsIgnoreCase(source) ? "" : source;
        }
        return alt.isEmpty() ? null : alt;
    }

    private void collectImages(JSONObject node, List<JSONObject> collected) {
        if ("image".equals(node.getString("type"))) {
            collected.add(node);
            return;
        }
        for (String container : TEXT_CONTAINERS) {
            JSONArray kids = node.getJSONArray(container);
            if (kids == null) {
                continue;
            }
            for (int i = 0; i < kids.size(); i++) {
                JSONObject child = kids.getJSONObject(i);
                if (child != null) {
                    collectImages(child, collected);
                }
            }
        }
        JSONArray rows = node.getJSONArray("rows");
        for (int r = 0; rows != null && r < rows.size(); r++) {
            JSONArray cells = rows.getJSONObject(r) == null ? null : rows.getJSONObject(r).getJSONArray("cells");
            for (int c = 0; cells != null && c < cells.size(); c++) {
                JSONObject cell = cells.getJSONObject(c);
                if (cell != null) {
                    collectImages(cell, collected);
                }
            }
        }
    }

    private String suffixOf(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 || idx == name.length() - 1 ? "png" : name.substring(idx + 1).toLowerCase();
    }

    private String mimeOf(String suffix) {
        return switch (suffix) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    // ==================== 文本与溯源 ====================

    /**
     * 递归收集 {@code content}：PDF 里一个语义段落常被切成多个文本块（跨行、跨页），
     * 不合并就会出现「上半句一段、下半句一段」的碎块。
     */
    private String gatherText(JSONObject node, Set<String> skipTypes) {
        StringBuilder out = new StringBuilder();
        collectText(node, skipTypes, out);
        return out.toString().trim();
    }

    private void collectText(JSONObject node, Set<String> skipTypes, StringBuilder out) {
        String type = node.getString("type");
        if (type != null && skipTypes.contains(type)) {
            return;
        }
        String content = trimToEmpty(node.getString("content"));
        if (!content.isEmpty()) {
            joinText(out, content);
        }
        for (String container : TEXT_CONTAINERS) {
            JSONArray kids = node.getJSONArray(container);
            if (kids == null) {
                continue;
            }
            for (int i = 0; i < kids.size(); i++) {
                JSONObject child = kids.getJSONObject(i);
                if (child != null) {
                    collectText(child, skipTypes, out);
                }
            }
        }
    }

    /** 拼接相邻文本块：CJK 直接相连，西文补空格（与 {@code tika-pdf} 的折行归并同一口径） */
    private void joinText(StringBuilder out, String text) {
        if (out.isEmpty()) {
            out.append(text);
            return;
        }
        char last = out.charAt(out.length() - 1);
        char first = text.charAt(0);
        if (!(isCjk(last) || isCjk(first))) {
            out.append(' ');
        }
        out.append(text);
    }

    private boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }

    /** {@code bounding box} = [left, bottom, right, top]，单位 pt、原点左下，与 {@link BBox} 同构 */
    private ProvenanceItem provenanceOf(JSONObject node) {
        int page = Math.max(0, node.getIntValue("page number"));
        JSONArray box = node.getJSONArray("bounding box");
        if (box == null || box.size() < 4) {
            return new ProvenanceItem(page, null);
        }
        try {
            return new ProvenanceItem(page, new BBox(box.getFloatValue(0), box.getFloatValue(1),
                    box.getFloatValue(2), box.getFloatValue(3)));
        } catch (Exception e) {
            return ProvenanceItem.ofPage(page);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void deleteTree(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("临时文件删除失败 {}：{}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("临时目录关闭失败 {}：{}", dir, e.getMessage());
        }
    }

    /** 一次解析的可变状态：文档主标题只给第一个一级标题，maxPages 靠页码后置过滤 */
    private static final class State {
        private final QunxuDocument document;
        private final Path workDir;
        private final ImageExtractor images;
        private final int maxPage;
        /** 判定为水印的文字集合（产出节点前先扫一遍内容流得到，用于过滤装饰文字） */
        private final PdfWatermarkFilter.Result watermark;
        /** 是否已出过标题（只影响本文档主标题的归属） */
        private boolean headingEmitted;
        /** 被水印规则挡住的节点数（只写日志，不占用告警） */
        private int watermarkDropped;

        private State(QunxuDocument document, Path workDir, ImageExtractor images, int maxPage,
                      PdfWatermarkFilter.Result watermark) {
            this.document = document;
            this.workDir = workDir;
            this.images = images;
            this.maxPage = maxPage;
            this.watermark = watermark;
        }

        /**
         * 结构化结果按元素自带页码过滤。
         * <p>
         * 不用库的 {@code setPages}：子集页下的页码语义没有对外承诺，一旦偏移，
         * 缓存里的引用页码就整体错位，而这种错误在导出与检索阶段都看不出来。
         */
        private boolean pageAllowed(JSONObject node) {
            return maxPage <= 0 || node.getIntValue("page number") <= maxPage;
        }
    }
}

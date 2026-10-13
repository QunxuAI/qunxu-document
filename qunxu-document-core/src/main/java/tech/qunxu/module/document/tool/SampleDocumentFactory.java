package tech.qunxu.module.document.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 合成样本生成器（只服务于 {@link DocumentSampleRunner} 的冒烟验证，不参与运行时）。
 * <p>
 * 项目里没有现成的 docx/pptx/xlsx/pdf 样本，而 {@code PoiHeadingStyles}、表格合并、
 * PDF 逐页文本与内嵌图抽取这些最容易出错的分支恰恰只有真文件才能覆盖。
 * 用 POI/PDFBox 现造样本比人工攒样本更可控：标题层级、合并单元格、图片张数、页数
 * 都是显式写死的，自检断言才有确定答案。
 * <p>
 * PDF 用 Helvetica（WinAnsi），样本正文只写英文 —— 中文在无嵌入字库的 PDF 里会直接丢字，
 * 那会让「PDF 是否解析出文本」的判定失真。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class SampleDocumentFactory {

    private static final int PAGE_HEADER_COUNT = 3;

    private SampleDocumentFactory() {
    }

    /** 生成全部样本，返回文件列表（目录不存在时自动创建） */
    public static List<Path> generate(Path dir) throws Exception {
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        files.add(docx(dir.resolve("sample.docx")));
        files.add(pptx(dir.resolve("sample.pptx")));
        files.add(xlsx(dir.resolve("sample.xlsx")));
        files.add(pdf(dir.resolve("sample.pdf")));
        files.add(scannedPdf(dir.resolve("scanned.pdf")));
        files.add(watermarkedPdf(dir.resolve("watermarked.pdf")));
        files.add(formWatermarkPdf(dir.resolve("watermark-form.pdf")));
        files.add(searchablePdf(dir.resolve("searchable.pdf")));
        files.add(write(dir.resolve("sample.csv"),
                "名称,数量,单价\n苹果,10,5.5\n香蕉,20,3.2\n橘子,15,4.0\n"));
        files.add(write(dir.resolve("sample.md"),
                "# 部署手册\n\n第一步：准备环境。\n\n## 配置项\n\n- 数据库地址\n- 对象存储桶\n\n"
                        + "```yaml\nqunxu:\n  document:\n    enabled: true\n```\n\n## 回滚\n\n执行快照恢复。\n"));
        files.add(write(dir.resolve("sample.txt"),
                "会议纪要\n\n参会人：张三、李四。\n结论：分块预算取 7000 token。\n"));
        return files;
    }

    // ==================== docx ====================

    private static Path docx(Path file) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            XWPFStyles styles = document.createStyles();
            addHeadingStyle(styles, "Heading1", "Heading 1", 0);
            addHeadingStyle(styles, "Heading2", "Heading 2", 1);

            paragraph(document, "Heading1", "产品说明书", null, 0);
            paragraph(document, null, "这是一段普通正文，用于验证段落抽取与分块装填。", null, 0);
            paragraph(document, "Heading2", "功能清单", null, 0);
            XWPFParagraph list = document.createParagraph();
            XWPFRun bullet = list.createRun();
            bullet.setText("• 支持表格合并单元格");
            XWPFParagraph second = document.createParagraph();
            second.createRun().setText("• 支持内嵌图片抽取");
            paragraph(document, "Heading2", "规格表", null, 0);

            XWPFTable table = document.createTable(4, 3);
            String[][] grid = {{"型号", "尺寸", "价格"}, {"A-100", "10x10", "100"},
                    {"A-200", "20x20", "200"}, {"合计", "", "300"}};
            for (int r = 0; r < 4; r++) {
                XWPFTableRow row = table.getRow(r);
                for (int c = 0; c < 3; c++) {
                    XWPFTableCell cell = row.getCell(c);
                    cell.setText(grid[r][c]);
                }
            }
            // 横向合并（首行前两列）与纵向合并（第三列跨两行）：PoiHeadingStyles 之外的另一处易错点
            mergeHorizontal(table.getRow(0).getCell(0), 2);
            mergeVertical(table.getRow(2).getCell(2), STMerge.RESTART);
            mergeVertical(table.getRow(3).getCell(2), STMerge.CONTINUE);

            byte[] image = noisyPng(160, 120);
            XWPFParagraph pictureParagraph = document.createParagraph();
            pictureParagraph.createRun().addPicture(new ByteArrayInputStream(image),
                    PictureType.PNG, "架构示意图", 160 * 9525, 120 * 9525);
            document.write(out);
        }
        return file;
    }

    private static void paragraph(XWPFDocument document, String style, String text, String font, int size) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        if (size > 0) {
            run.setFontSize(size);
        }
        if (font != null) {
            run.setBold(true);
        }
    }

    private static void addHeadingStyle(XWPFStyles styles, String id, String name, int outlineLevel) {
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setStyleId(id);
        ct.setType(STStyleType.PARAGRAPH);
        ct.addNewName().setVal(name);
        ct.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel));
        styles.addStyle(new XWPFStyle(ct, styles));
    }

    private static void mergeHorizontal(XWPFTableCell cell, int span) {
        cell.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(span));
    }

    private static void mergeVertical(XWPFTableCell cell, STMerge.Enum type) {
        cell.getCTTc().addNewTcPr().addNewVMerge().setVal(type);
    }

    // ==================== pptx ====================

    private static Path pptx(Path file) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            for (int index = 0; index < 2; index++) {
                XSLFSlide slide = show.createSlide();
                // POI 新建的 pptx 没有带 title 占位符的版式，靠 setTextPlaceholder 补上，
                // 才能真正走到 PowerPointDocumentBackend 的「标题占位符 → SECTION_HEADER」分支
                XSLFTextBox title = slide.createTextBox();
                title.setAnchor(new Rectangle(40, 30, 640, 60));
                title.setTextPlaceholder(TextShape.TextPlaceholder.TITLE);
                title.setText("Slide " + (index + 1) + " Title");
                XSLFTextBox body = slide.createTextBox();
                body.setAnchor(new Rectangle(40, 120, 600, 120));
                XSLFTextParagraph paragraph = body.addNewTextParagraph();
                XSLFTextRun run = paragraph.addNewTextRun();
                run.setText("Body bullet: hierarchical chunking keeps the heading breadcrumb.");
                paragraph = body.addNewTextParagraph();
                run = paragraph.addNewTextRun();
                run.setText("Body paragraph: another sentence on the same slide.");

                XSLFTable table = slide.createTable(2, 2);
                table.setAnchor(new Rectangle(40, 260, 400, 80));
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        XSLFTableCell cell = table.getRows().get(r).getCells().get(c);
                        cell.setText(r == 0 ? "col" + c : "value" + r + c);
                    }
                }
                XSLFPictureData picture = show.addPicture(noisyPng(140, 100),
                        org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
                XSLFPictureShape shape = slide.createPicture(picture);
                shape.setAnchor(new Rectangle(480, 120, 140, 100));
            }
            show.write(out);
        }
        return file;
    }

    // ==================== xlsx ====================

    private static Path xlsx(Path file) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            String[][] sales = {{"日期", "区域", "金额"}, {"2026-01-01", "华东", "1200"},
                    {"2026-01-02", "华北", "900"}, {"2026-01-03", "华东", "1500"}};
            writeSheet(workbook.createSheet("销售"), sales);
            String[][] staff = {{"工号", "姓名", "部门"}, {"E001", "张三", "研发"}, {"E002", "李四", "产品"}};
            writeSheet(workbook.createSheet("人员"), staff);
            workbook.write(out);
        }
        return file;
    }

    private static void writeSheet(Sheet sheet, String[][] grid) {
        for (int r = 0; r < grid.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < grid[r].length; c++) {
                row.createCell(c).setCellValue(grid[r][c]);
            }
        }
    }

    // ==================== pdf ====================

    private static Path pdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument(); OutputStream out = Files.newOutputStream(file)) {
            // PDFBox 3 取消了 PDType1Font 上的标准字体常量，改由 Standard14Fonts.FontName 构造
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, noisyPng(180, 120), "chart");
            for (int page = 1; page <= PAGE_HEADER_COUNT; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    // 每页重复的页眉页脚：PdfTikaBackend 靠跨页重复度识别并标成 PAGE_HEADER/PAGE_FOOTER
                    text(stream, bold, 9, 50, 806, "QUNXU DOCUMENT SAMPLE HEADER");
                    // 正文从页面上部往下排：结构化解析器（OpenDataLoader）按版面位置判页眉页脚带，
                    // 全部挤在顶部会让它把整页正文当成装饰吞掉
                    text(stream, bold, 20, 50, 700, "Chapter " + page);
                    text(stream, regular, 11, 50, 640,
                            "Body paragraph on page " + page + " describing the parsing pipeline.");
                    text(stream, regular, 11, 50, 610,
                            "Another sentence that belongs to the same paragraph block.");
                    if (page == 1) {
                        stream.drawImage(image, 50, 400, 180, 120);
                    }
                    text(stream, regular, 9, 50, 40, "Page " + page + " of " + PAGE_HEADER_COUNT);
                }
            }
            document.save(out);
        }
        return file;
    }

    private static void text(PDPageContentStream stream, PDType1Font font, int size,
                             float x, float y, String content) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(content);
        stream.endText();
    }

    /**
     * 扫描件样本：整页只有一张位图，全页无任何文本算子（文本层为空）。
     * <p>
     * 它专门钉住一个真实发生过的故障：这类文件的解析结果不空（全是 PICTURE 节点），
     * 但一个可检索的字都没有。如果模块把它当成功，图片文件名会被当成正文向量化入库，
     * 且这份空壳结果会被写进解析缓存长期命中。
     */
    private static Path scannedPdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument(); OutputStream out = Files.newOutputStream(file)) {
            // 满页尺寸位图：保证能过 ImageFilter 的字节数与宽高三重门槛，否则节点都留不下
            PDImageXObject pageImage = PDImageXObject.createFromByteArray(document, noisyPng(595, 842), "scan");
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    stream.drawImage(pageImage, 0, 0,
                            pdPage.getMediaBox().getWidth(), pdPage.getMediaBox().getHeight());
                }
            }
            document.save(out);
        }
        return file;
    }

    /** 水印文案：只用英文（无嵌入字库的中文会在标准字体下直接丢字） */
    private static final String WATERMARK_TEXT = "INTERNAL COPY DO NOT DISTRIBUTE";

    /**
     * 水印样本：与 {@link #pdf} 同构（同字号、同位置、同文案、同页眉页脚），唯一的差别就是那层水印。
     * <p>
     * 之所以要同构：结构化解析器按字号聚类推断标题层级，斜排大字水印会占走一个字号档位，
     * 于是它本身被判成 level 1 标题、真实标题被整体压深一级。同构才能把「剔掉水印后的标题
     * 层级应与无水印样本一致」当成确定的断言，而不是靠猜。
     * <p>
     * 它同时是 {@code PdfWatermarkFilter} 的靶子：靠透明度 + 斜排两条信号才能删掉。
     */
    private static Path watermarkedPdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument(); OutputStream out = Files.newOutputStream(file)) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, noisyPng(180, 120), "chart");
            PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
            faint.setNonStrokingAlphaConstant(0.12f);
            for (int page = 1; page <= PAGE_HEADER_COUNT; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    text(stream, bold, 9, 50, 806, "QUNXU DOCUMENT SAMPLE HEADER");
                    text(stream, bold, 20, 50, 700, "Chapter " + page);
                    text(stream, regular, 11, 50, 640,
                            "Body paragraph on page " + page + " describing the parsing pipeline.");
                    text(stream, regular, 11, 50, 610,
                            "Another sentence that belongs to the same paragraph block.");
                    if (page == 1) {
                        stream.drawImage(image, 50, 400, 180, 120);
                    }
                    text(stream, regular, 9, 50, 40, "Page " + page + " of " + PAGE_HEADER_COUNT);
                    stream.saveGraphicsState();
                    stream.setGraphicsStateParameters(faint);
                    Matrix matrix = new Matrix();
                    matrix.translate(110, 230);
                    matrix.rotate(Math.toRadians(45));
                    stream.beginText();
                    stream.setTextMatrix(matrix);
                    stream.setFont(bold, 40);
                    stream.showText(WATERMARK_TEXT);
                    stream.endText();
                    stream.restoreGraphicsState();
                }
            }
            document.save(out);
        }
        return file;
    }

    /**
     * Form 水印样本：真实企业 PDF 最常见也最阴的画法 —— 水印整块做成一个共享 Form，每页用
     * {@code q /GS ca=0.12 gs <旋转 cm> /XOb Do Q} 调进去（实测样本就是 16 页共用一个 Form）。
     * <p>
     * 它跟 {@link #watermarkedPdf} 的差别不只是“画在哪”，而是抽出来之后的形状：那个样本的水印在页面流里，
     * 抽文本时自然成行成节点，只删节点就能兜住；本样本的水印在 Form 里按词分开画（真实中文水印按
     * 词/按字分开也一样），斜着压过表格，结构化解析器会把这些短词<b>并进单元格</b> ——
     * 节点整串比对和碎片规则全部够不上（每串都不足 {@code MIN_FRAGMENT}，且被并进了正文），
     * 只有预剔除跟进 Form 内容流才能拿掉。预剔除一旦不跟进 Form，本样本的两个解析器都会泄漏（实测），
     * 这条断言才有牙齿。
     * <p>
     * 除了 Form，每页还往正文行的基线上水平压一个 24pt 淡字：那是 {@code tika-pdf} 的泄漏形状 ——
     * 它按整行剔，而行被抽成「正文 + 水印词」时整行比对删不掉（真实样本里的“…周亚平等。严禁复制”就长这样）。
     * <p>
     * 两处各只靠一条形态信号认出：Form 里那组只靠斜排（只有两页凑不满“跨页重复”，18pt 也不到本页
     * 正文中位数的 1.8 倍）；压在正文行上的那组不斜也不跨页，只靠大字（24pt）。倾角一旦改回
     * 只读文本矩阵，前者变红；大字判定一旦改动跨过阈值，后者变红。
     */
    private static Path formWatermarkPdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument(); OutputStream out = Files.newOutputStream(file)) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
            faint.setNonStrokingAlphaConstant(0.12f);
            PDFormXObject stamp = new PDFormXObject(document);
            stamp.setBBox(new PDRectangle(0, 0, 300, 90));
            stamp.setResources(new PDResources());
            try (PDFormContentStream form = new PDFormContentStream(stamp)) {
                // 按词分开画：拼起来才是一串完整水印（归一化后即逐字形回调看到的那一段）
                String[] words = WATERMARK_TEXT.split(" ");
                for (int i = 0; i < words.length; i++) {
                    form.beginText();
                    form.setFont(bold, 18);
                    Matrix at = new Matrix();
                    at.translate(i * 55, 18 + i * 14);
                    form.setTextMatrix(at);
                    form.showText(words[i]);
                    form.endText();
                }
            }
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    text(stream, bold, 9, 50, 806, "QUNXU DOCUMENT SAMPLE HEADER");
                    text(stream, bold, 20, 50, 700, "Chapter " + page);
                    text(stream, regular, 11, 50, 640,
                            "Body paragraph on page " + page + " describing the parsing pipeline.");
                    text(stream, regular, 11, 50, 610,
                            "Another sentence that belongs to the same paragraph block.");
                    table(stream, regular, 50, 200, 495, 380);
                    text(stream, regular, 9, 50, 40, "Page " + page + " of 2");
                    stream.saveGraphicsState();
                    stream.setGraphicsStateParameters(faint);
                    Matrix place = new Matrix();
                    place.translate(90, 250);
                    place.rotate(Math.toRadians(45));
                    stream.transform(place);
                    stream.drawForm(stamp);
                    stream.restoreGraphicsState();
                    // 第二处水印：另起一个不带旋转矩阵的状态块，水平画在正文行的基线上
                    // （与真实样本里“…周亚平等。严禁复制”同构）。抽出来的行是「正文 + 水印词」，
                    // 整行比对删不掉、碎片规则也判不成（节点文本不是水印的子串），
                    // 只有从内容流源头抹掉绘制才能拿掉 —— tika-pdf 的泄漏形状就长这样
                    stream.saveGraphicsState();
                    stream.setGraphicsStateParameters(faint);
                    stream.beginText();
                    stream.setFont(bold, 24);
                    Matrix onBodyLine = new Matrix();
                    onBodyLine.translate(330, 640);
                    stream.setTextMatrix(onBodyLine);
                    stream.showText("DISTRIBUTE");
                    stream.endText();
                    stream.restoreGraphicsState();
                }
            }
            document.save(out);
        }
        return file;
    }

    /**
     * 画一张带框线的表格（结构化解析器只认有框线的表）。
     * <p>
     * 单元格文案特意与正文不重：水印被拆进哪个单元格，导出里就会出现 {@code cell r0c1 INTERNAL}，
     * 自检能直接拿着“单元格不含水印词”做断言。
     */
    private static void table(PDPageContentStream stream, PDType1Font font, float x0, float y0,
            float width, float height) throws IOException {
        int cols = 3;
        int rows = 4;
        stream.setLineWidth(0.7f);
        for (int r = 0; r <= rows; r++) {
            float y = y0 + height * r / rows;
            stream.moveTo(x0, y);
            stream.lineTo(x0 + width, y);
            stream.stroke();
        }
        for (int c = 0; c <= cols; c++) {
            float x = x0 + width * c / cols;
            stream.moveTo(x, y0);
            stream.lineTo(x, y0 + height);
            stream.stroke();
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                text(stream, font, 10, x0 + width * c / cols + 6, y0 + height * (rows - 1 - r) + 12,
                        "cell r" + r + "c" + c);
            }
        }
    }

    /**
     * 搜索型扫描件样本：整页位图 + 垫在下方的隐形文字层（{@code Tr 3}，水平排、与正文同号）。
     * <p>
     * 这是扫描件 OCR 结果的常见封装，那份隐形文字是它唯一的正文。它跟水印一样“看不见”，
     * 所以这个样本的唯一作用是验证反向性质：按绘制状态删水印时绝不能把它删掉。
     */
    private static Path searchablePdf(Path file) throws IOException {
        try (PDDocument document = new PDDocument(); OutputStream out = Files.newOutputStream(file)) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDImageXObject pageImage = PDImageXObject.createFromByteArray(document, noisyPng(595, 842), "scan");
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    stream.drawImage(pageImage, 0, 0,
                            pdPage.getMediaBox().getWidth(), pdPage.getMediaBox().getHeight());
                    stream.beginText();
                    stream.setRenderingMode(RenderingMode.NEITHER);
                    stream.setFont(regular, 11);
                    stream.newLineAtOffset(50, 700 - (page - 1) * 30);
                    stream.showText("Invisible overlay sentence number " + page + " about the pump bearing.");
                    stream.endText();
                }
            }
            document.save(out);
        }
        return file;
    }

    // ==================== 杂项 ====================

    private static Path write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 噪声色块 PNG：尺寸够大且不可压缩，保证能通过 {@code ImageFilter} 的 min-bytes/min-width/min-height
     * 三重门槛（纯色小图会被当成装饰线条过滤掉，样本就白做了）。
     */
    private static byte[] noisyPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

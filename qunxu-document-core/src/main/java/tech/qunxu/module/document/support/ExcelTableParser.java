package tech.qunxu.module.document.support;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import tech.qunxu.module.document.api.DocumentFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表格网格读取器（自 app 的 {@code ExcelTableParser} 迁入，行为逐条对齐）。
 * <p>
 * xlsx/xls 用 POI 的 {@link DataFormatter} 按显示格式取值（日期/数字保留可读文本），
 * csv 用 hutool 并按内容探测字符集；全空行跳过，累计行数超过上限即截断并记一条告警。
 * 产物是「Sheet → 文本网格」，由 {@code TabularDocumentBackend} 转成 {@code TableItem}。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class ExcelTableParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelTableParser.class);

    /** 单个文件累计最大行数保护，防止超大表格撑爆内存 */
    public static final int MAX_TOTAL_ROWS = 100_000;

    private ExcelTableParser() {
    }

    /** 一个 Sheet（CSV 视为单 Sheet）的名称与行数据 */
    public record SheetData(String name, List<List<String>> rows) {
    }

    /**
     * 解析表格文件为结构化行数据。
     *
     * @param is       文件输入流（调用方负责关闭）
     * @param format   已判定的格式（CSV 走文本解析，其余走 POI 工作簿）
     * @param warnings 告警收集器（可为 null）：截断、空表等
     * @return Sheet 列表（空文件返回空列表）
     */
    public static List<SheetData> parse(InputStream is, DocumentFormat format, List<String> warnings)
            throws IOException {
        if (format == DocumentFormat.CSV) {
            return parseCsv(is, warnings);
        }
        return parseWorkbook(is, warnings);
    }

    /** 按文件名判定格式后解析（扩展名缺失时按工作簿尝试） */
    public static List<SheetData> parse(InputStream is, String fileName, List<String> warnings) throws IOException {
        return parse(is, DocumentFormat.fromExtension(DocumentFormat.extensionOf(fileName)), warnings);
    }

    /** POI 解析 xlsx/xls：逐 Sheet 逐行取单元格文本，跳过全空行 */
    public static List<SheetData> parseWorkbook(InputStream is, List<String> warnings) throws IOException {
        List<SheetData> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int totalRows = 0;
        boolean truncated = false;
        try (Workbook workbook = WorkbookFactory.create(is)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (totalRows >= MAX_TOTAL_ROWS) {
                        truncated = true;
                        break;
                    }
                    short first = row.getFirstCellNum();
                    short last = row.getLastCellNum();
                    if (first < 0 || last < 0) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    boolean allBlank = true;
                    for (int c = first; c < last; c++) {
                        Cell cell = row.getCell(c);
                        String v = cell != null ? formatter.formatCellValue(cell).trim() : "";
                        if (!v.isEmpty()) {
                            allBlank = false;
                        }
                        cells.add(v);
                    }
                    if (!allBlank) {
                        rows.add(cells);
                        totalRows++;
                    }
                }
                if (!rows.isEmpty()) {
                    result.add(new SheetData(sheet.getSheetName(), rows));
                }
            }
        }
        if (truncated) {
            String message = "表格行数超过上限 " + MAX_TOTAL_ROWS + "，已截断解析";
            log.warn(message);
            if (warnings != null && !warnings.contains(message)) {
                warnings.add(message);
            }
        }
        return result;
    }

    /** hutool 解析 csv：自动探测字符集（兼容 UTF-8/GBK 等），单 Sheet，跳过 BOM 与全空行 */
    public static List<SheetData> parseCsv(InputStream is, List<String> warnings) throws IOException {
        byte[] bytes = toBytes(is);
        Charset charset = TextDecoder.detectCharset(bytes);
        List<List<String>> rows = new ArrayList<>();
        String text = TextDecoder.decode(bytes);
        try {
            CsvData data = CsvUtil.getReader().read(new StringReader(text));
            if (data != null && data.getRows() != null) {
                for (CsvRow row : data.getRows()) {
                    if (rows.size() >= MAX_TOTAL_ROWS) {
                        String message = "CSV 行数超过上限 " + MAX_TOTAL_ROWS + "，已截断解析";
                        log.warn(message);
                        if (warnings != null && !warnings.contains(message)) {
                            warnings.add(message);
                        }
                        break;
                    }
                    List<String> fields = row.getRawList();
                    if (fields == null || fields.isEmpty()) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    boolean allBlank = true;
                    for (String f : fields) {
                        String v = f == null ? "" : f.trim();
                        if (!v.isEmpty()) {
                            allBlank = false;
                        }
                        cells.add(v);
                    }
                    if (!allBlank) {
                        rows.add(cells);
                    }
                }
            }
        } catch (RuntimeException e) {
            String message = "CSV 解析失败：" + e.getMessage();
            log.warn(message);
            if (warnings != null) {
                warnings.add(message);
            }
        }
        log.info("CSV 解析完成，字符集={}，有效行数={}", charset.name(), rows.size());
        return rows.isEmpty() ? Collections.emptyList() : List.of(new SheetData("Sheet1", rows));
    }

    private static byte[] toBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        is.transferTo(bos);
        return bos.toByteArray();
    }

    /** 供只拿到字节的路径复用 */
    public static List<SheetData> parseCsv(byte[] bytes, List<String> warnings) throws IOException {
        return parseCsv(new ByteArrayInputStream(bytes), warnings);
    }
}

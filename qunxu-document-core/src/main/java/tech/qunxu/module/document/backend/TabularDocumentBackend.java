package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.ConvertOptions;
import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.support.ExcelTableParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 表格类解析器（xlsx / xls / csv）：每个 Sheet 一个 {@code SHEET} 标题 + 一个 {@code TABLE}。
 * <p>
 * 网格读取沿用迁移前的 {@link ExcelTableParser}（POI {@code DataFormatter} 按显示格式取值、
 * 全空行跳过、累计行数上限截断、CSV 字符集探测），保证与改造前的 Excel 导入结果可比。
 * 首行当表头，行分块由 {@code TableRowChunker} 负责，本 Backend 不切块。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class TabularDocumentBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(TabularDocumentBackend.class);

    @Override
    public String key() {
        return "tabular";
    }

    @Override
    public String label() {
        return "表格(xlsx/xls/csv)";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.XLSX || format == DocumentFormat.XLS || format == DocumentFormat.CSV;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        ConvertOptions options = context.options();
        QunxuDocument document = context.newDocument();
        List<String> warnings = new ArrayList<>();
        List<ExcelTableParser.SheetData> sheets;
        try (InputStream is = context.openStream()) {
            sheets = ExcelTableParser.parse(is, context.format(), warnings);
        } catch (Exception e) {
            // csv 被误判成工作簿或反之：换一条路再试一次，避免直接失败
            log.debug("表格解析失败，尝试另一条分支：{}", e.getMessage());
            try (InputStream retry = context.openStream()) {
                sheets = context.format() == DocumentFormat.CSV
                        ? ExcelTableParser.parseWorkbook(retry, warnings)
                        : ExcelTableParser.parseCsv(retry, warnings);
            } catch (Exception inner) {
                throw e;
            }
        }
        document.getWarnings().addAll(warnings);
        int sheetCount = 0;
        for (ExcelTableParser.SheetData sheet : sheets) {
            if (sheet == null || sheet.rows() == null || sheet.rows().isEmpty()) {
                continue;
            }
            if (!options.sheetSelected(sheet.name())) {
                continue;
            }
            sheetCount++;
            document.append(NodeItem.sheet(sheet.name() == null ? "Sheet" + sheetCount : sheet.name()));
            document.append(NodeItem.TableItem.ofGrid(sheet.rows()));
        }
        document.getMeta().setPageCount(sheetCount);
        if (sheetCount == 0) {
            document.warn("表格文件没有可解析的数据行");
        }
        if (document.countOf(DocumentKind.TABLE) > 0 && !warnings.isEmpty()) {
            // 截断等告警已通过 warnings 进入文档，状态由 converter 定为 PARTIAL_SUCCESS
            log.debug("表格解析带告警：{}", warnings);
        }
        return document;
    }
}

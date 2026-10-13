package tech.qunxu.module.document.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模块自检（CI 主回归入口）。
 * <p>
 * 复用 {@link DocumentSampleRunner} 内置的 69 项检查（生成真实样本 → 路由 → 导出 → 分块 →
 * 缓存指纹 → JSON 往返 → L2/L3 扩展点 → SSRF 闸门 → 扫描件 OCR 页级兜底 → 水印剔除），
 * 断言全部通过，并钉住检查项总数：任何人新增能力时必须同步补断言，否则此测试会失败提醒。
 */
class DocumentSampleRunnerTest {

    @Test
    void builtInSelfCheckAllPass() {
        int failed = DocumentSampleRunner.runSelfCheck();
        assertEquals(0, failed, "内置自检出现失败项（见上方 stdout 的 [FAIL] 行）");
    }

    @Test
    void selfCheckCountIsStable() {
        DocumentSampleRunner.runSelfCheck();
        assertEquals(69, DocumentSampleRunner.checkCount(),
                "内置自检检查项数应为 69；若你新增了能力，请在本模块同步补断言并更新此数字");
    }
}

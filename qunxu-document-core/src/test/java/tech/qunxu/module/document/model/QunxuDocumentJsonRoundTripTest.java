package tech.qunxu.module.document.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间格式 JSON 序列化往返的快速单测（不依赖任何解析引擎与样本文件，CI 中秒级完成）。
 */
class QunxuDocumentJsonRoundTripTest {

    @Test
    void roundTripPreservesContentAndTree() {
        QunxuDocument origin = new QunxuDocument();
        origin.addPage(1, 595f, 842f);
        NodeItem heading = origin.appendHeading(1, "第一章");
        NodeItem paragraph = origin.appendParagraph("这是正文段落，包含中文与 English text。");
        origin.append(NodeItem.title("文档标题"));

        String json = origin.toJson();
        QunxuDocument restored = QunxuDocument.fromJson(json);

        assertEquals(origin.getItems().size(), restored.getItems().size(), "节点数应一致");
        assertEquals(origin.countOf(DocumentKind.SECTION_HEADER), restored.countOf(DocumentKind.SECTION_HEADER),
                "章节数应一致");
        assertEquals(origin.countOf(DocumentKind.PARAGRAPH), restored.countOf(DocumentKind.PARAGRAPH),
                "段落数应一致");
        assertEquals("第一章", restored.item(heading.getIndex()).getText(), "标题文本应一致");
        assertEquals(origin.getPages().size(), restored.getPages().size(), "页面表应保留");
        assertEquals(origin.getItems().size(), restored.getItems().size(), "节点数应一致");
        // 页码由页面分隔推导，不随单个节点序列化；往返后经 fromJson 重建的页面表与 origin 一致即可
        assertTrue(restored.getPages().size() >= 1, "应至少有一页");
    }

    @Test
    void exportMarkdownContainsContent() {
        QunxuDocument document = new QunxuDocument();
        document.append(NodeItem.title("H1 主标题"));
        document.append(NodeItem.heading(2, "二级小节"));
        document.appendParagraph("一句话。");

        String markdown = document.exportAsMarkdown();
        assertTrue(markdown.contains("主标题"), "Markdown 应包含标题文本");
        assertTrue(markdown.contains("一句话"), "Markdown 应包含正文文本");
        assertTrue(markdown.contains("#"), "Markdown 应包含标题标记");
    }

    @Test
    void appendPreservesInsertionOrder() {
        QunxuDocument document = new QunxuDocument();
        document.append(NodeItem.title("A"));
        document.append(NodeItem.title("B"));
        document.append(NodeItem.title("C"));
        List<String> texts = document.getItems().stream().map(NodeItem::getText).toList();
        assertEquals(List.of("A", "B", "C"), texts);
    }
}

package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.NodeItem;

/**
 * 纯文本解析器：按空行分段，段落内换行压成空格。
 * <p>
 * 与改造前 {@code new Tika().parseToString()} 再按空行切分的行为一致（txt 走 Tika 也只是原样透传），
 * 但不依赖 Tika 的重型解析链，解析更快、结果更可控。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class PlainTextDocumentBackend implements DocumentBackend {

    @Override
    public String key() {
        return "plaintext";
    }

    @Override
    public String label() {
        return "纯文本";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.TEXT;
    }

    @Override
    public QunxuDocument parse(ParseContext context) throws Exception {
        QunxuDocument document = context.newDocument();
        String text = context.text();
        if (text == null || text.isBlank()) {
            document.warn("文本文件为空");
            return document;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n{2,}");
        for (String block : blocks) {
            String paragraph = block.replaceAll("\\s*\n\\s*", " ").trim();
            if (!paragraph.isEmpty()) {
                document.append(NodeItem.paragraph(paragraph));
            }
        }
        if (document.isEmpty()) {
            document.warn("文本文件没有有效段落");
        }
        return document;
    }
}

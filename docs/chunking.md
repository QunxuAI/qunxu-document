# 分块与导出

英文版：[chunking.en.md](chunking.en.md)

## 分块器

所有分块器注册在 `ChunkerRegistry`，以名字为键。名字大小写不敏感；旧分割器别名（Spring AI splitter 名字）
映射到同一实现且分段结果不变。

| Key | 类 | 策略 | 旧别名 |
| --- | --- | --- | --- |
| `hierarchical` | `HierarchicalChunker` | 按标题结构分块；每块携带标题面包屑路径 | `HierarchicalChunker`、`MarkdownHeaderSplitter` |
| `table-row` | `TableRowChunker` | 每个 `TABLE` 独立按行分块（≤ `rowsPerChunk` **且** ≤ token 预算）；Sheet 名 + 表头逐块重复，保证每块自包含 | `ExcelDocumentSplitter`、`TableRowChunker` |
| `flat` | `FlatChunker` | 字符窗口切分 | `SimpleDocumentSplitter`、`FlatChunker` |
| `flat`（正则） | `FlatChunker` | 按正则分隔符切分（如空行） | `RegexDocumentSplitter` |
| `AutoChunker`（或空名） | 自动选型 | 有标题 → `hierarchical`；纯表格 → `table-row`；纯文本 → flat（字符） | |

### 用法

```java
ChunkerRegistry registry = ...; // 自动配置 Bean，或 new ChunkerRegistry(List.of(...))
ChunkOptionsFactory factory = new ChunkOptionsFactory(properties); // 或 ChunkOptions.of(...)

List<Chunk> chunks = registry.chunk("AutoChunker", document,
        factory.create(maxTokens /*512*/, minTokens /*128*/, rowsPerChunk /*10*/, regexSeparator /*null*/, strategy /*null*/));
```

- token 预算经 `JTokkitTokenCounter`（CL100K_BASE）计量——与 Spring AI 的
  `TokenCountBatchingStrategy` 同一 tokenizer 家族，分块大小不会超 embedding 上限。
- 低于 `minTokens` 的块并入前一块，避免产生检索不到的碎片。
- `include-heading-breadcrumb=true`（默认）会在块首注入如 `> 第一章 / 1.2 小节`。

## 导出器

| Key | 类 | 输出 |
| --- | --- | --- |
| `markdown` | `MarkdownExporter` | ATX 标题、列表、代码围栏、管道表格、图片链接 |
| `html` | `HtmlExporter` | 语义化 HTML |
| `text` | `TextExporter` | 纯文本 |
| `json` | `JsonExporter` | 完整 `QunxuDocument` dict——可经 `QunxuDocument.fromJson(...)` 往返还原 |

```java
QunxuDocument document = result.getDocument();
String markdown = document.exportAsMarkdown();
String html      = document.exportAsHtml();
String text      = document.exportAsText();
String json      = document.toJson();
QunxuDocument back = QunxuDocument.fromJson(json); // 缓存 / 存储往返
```

## 扩展

- **新分块器**：实现 `BaseChunker` 并注册为 Bean；自动配置的 `ChunkerRegistry` 会收集它
  （`@ConditionalOnMissingBean` 语义）。
- **新导出器**：实现 `DocumentExporter` 并注册为 Bean，或调用 `converter.registerExporter(...)`。

# Chunking & exporters

## Chunkers

All chunkers are registered in `ChunkerRegistry` and keyed by name. Names are case-insensitive and legacy aliases
(Spring AI splitter names) map to the same implementation with identical output.

| Key | Class | Strategy | Legacy aliases |
| --- | --- | --- | --- |
| `hierarchical` | `HierarchicalChunker` | split by heading structure; each chunk carries the heading breadcrumb path | `HierarchicalChunker`, `MarkdownHeaderSplitter` |
| `table-row` | `TableRowChunker` | each `TABLE` chunked independently by rows (≤ `rowsPerChunk` **and** token budget); sheet name + headers repeated per chunk so each chunk is self-contained | `ExcelDocumentSplitter`, `TableRowChunker` |
| `flat` | `FlatChunker` | character-window splitting | `SimpleDocumentSplitter`, `FlatChunker` |
| `flat` (regex) | `FlatChunker` | split on a regex separator (e.g. blank lines) | `RegexDocumentSplitter` |
| `AutoChunker` (or empty name) | auto-selection | headings → `hierarchical`; pure tables → `table-row`; plain text → flat (chars) | |

### Usage

```java
ChunkerRegistry registry = ...; // auto-configured bean, or new ChunkerRegistry(List.of(...))
ChunkOptionsFactory factory = new ChunkOptionsFactory(properties); // or ChunkOptions.of(...)

List<Chunk> chunks = registry.chunk("AutoChunker", document,
        factory.create(maxTokens /*512*/, minTokens /*128*/, rowsPerChunk /*10*/, regexSeparator /*null*/, strategy /*null*/));
```

- Token budget is measured with `JTokkitTokenCounter` (CL100K_BASE) — the same tokenizer family Spring AI's
  `TokenCountBatchingStrategy` uses, so chunk sizes stay under embedding limits.
- Chunks below `minTokens` are merged into the previous chunk to avoid retrieval-dead fragments.
- `include-heading-breadcrumb=true` (default) prepends e.g. `> 第一章 / 1.2 小节` to each chunk.

## Exporters

| Key | Class | Output |
| --- | --- | --- |
| `markdown` | `MarkdownExporter` | ATX headings, lists, code fences, tables as pipe tables, image links |
| `html` | `HtmlExporter` | semantic HTML |
| `text` | `TextExporter` | plain text |
| `json` | `JsonExporter` | the full `QunxuDocument` dict — round-trippable via `QunxuDocument.fromJson(...)` |

```java
QunxuDocument document = result.getDocument();
String markdown = document.exportAsMarkdown();
String html      = document.exportAsHtml();
String text      = document.exportAsText();
String json      = document.toJson();
QunxuDocument back = QunxuDocument.fromJson(json); // cache / storage round-trip
```

## Extending

- **New chunker**: implement `BaseChunker` and register a bean; it is picked up by the auto-configured
  `ChunkerRegistry` (`@ConditionalOnMissingBean` semantics).
- **New exporter**: implement `DocumentExporter` and register a bean, or call `converter.registerExporter(...)`.

**English** | [简体中文](README.md)

# qunxu-document

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://adoptium.net)
[![Build](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/qunxu-ai/qunxu-document/actions)

A **docling-style document parsing library** for RAG / knowledge bases / AI applications:

```
file / URL ──► format-specific DocumentBackend ──► unified QunxuDocument model ──► Markdown / HTML / JSON / Text ──► chunking
                  (POI · PDFBox · Tika · OpenDataLoader)                                    (token-budget aware)
```

Format differences stop at the backend. Everything downstream (export, chunking, caching, indexing) only sees the
unified `QunxuDocument` model, so adding a new file format is just one new backend.

## Features

- **8 built-in backends** — docx, pptx, xlsx/csv (with merged-cell aware tables), markdown, plain text, and three PDF paths:
  OpenDataLoader (element-level bbox, reading order, heading levels, merged cells), PDFBox/Tika (text + embedded images),
  and a Tika fallback. PDF degrades gracefully from structured → tika-pdf when a parser fails.
- **Unified model** — `QunxuDocument`: pages + a flat typed node list (`TITLE`, `SECTION_HEADER`, `PARAGRAPH`, `TABLE`,
  `PICTURE`, `CODE`, `LIST_ITEM`, ...) with provenance (page, bbox) for citation / ground-truth retrieval.
- **4 exporters** — Markdown, HTML, JSON (round-trippable), plain text.
- **3 chunkers + auto-selection** — hierarchical (heading breadcrumbs), table-row (sheet/header-aware), flat;
  `AUTO` picks per document type; token budget measured with CL100K_BASE (jtokkit), safe for embedding limits.
- **Parse caching** — content-fingerprint keyed, in-memory LRU out of the box, replaceable via SPI for persistence.
- **SSRF-guarded URL input** — host allow-list, redirect re-validation, private/loopback/link-local blocked by default.
- **Scanned-document OCR gate** — page-level rasterizer + `PageOcr` SPI; money is only spent on pages that lack a text layer,
  once per page, with page-count/timeout budgets surfaced as warnings (no silent half-output).
- **Watermark / hidden-text filtering** — removes watermarks (including Form-based rotated low-opacity ones) while
  deliberately keeping searchable invisible OCR layers intact.
- **Zero `@Component`** — everything is wired by one `DocumentAutoConfiguration` (Spring Boot autoconfigure), so it never
  collides with your app's component scan. Works as a plain library too (no Spring required to use the core).

## Supported formats

| Format | Backend key | Notes |
| --- | --- | --- |
| PDF | `opendataloader`, `tika-pdf`, `tika-fallback` | structured (bbox/reading order) → degraded → last resort |
| DOCX | `poi-docx` | headings, tables, embedded images |
| PPTX | `poi-pptx` | slides as pages, placeholder text |
| XLSX | `tabular` | sheet pages, merged-cell aware tables |
| CSV | `tabular` | encoding detection (GBK-friendly) |
| Markdown | `markdown` | ATX headings, lists, code blocks |
| Plain text | `plaintext` | |
| Anything | `tika-fallback` | last-resort Tika parsing |

## Modules

The project is split into three Maven artifacts (mirroring docling-java's `docling-core` + `docling-bom` layout):

| Artifact | Description | Dependencies |
| --- | --- | --- |
| `qunxu-document-core` | Pure-Java library: backends, unified model, exporters, chunkers, cache, OCR gate, SSRF-guarded URL source, self-check. **Zero Spring dependencies.** | slf4j, fastjson2, POI, PDFBox, Tika, OpenDataLoader, hutool, commons-io, jtokkit |
| `qunxu-document-spring-boot-starter` | Spring Boot autoconfigure: wires every core bean via `AutoConfiguration.imports` (still **zero `@Component`**, `@ConditionalOnMissingBean` everywhere so hosts override via SPI). | `qunxu-document-core` + `spring-boot-autoconfigure` |
| `qunxu-document-bom` | Version bill of materials (no code). Import it to manage all qunxu-document artifact versions in one place. | — |

- Spring Boot 4 applications → use the **starter**.
- Plain Java / Boot 3 / non-Spring hosts → use the **core** and assemble the converter manually (the bundled
  `DocumentSampleRunner` shows the wiring).
- Managing versions centrally (like Spring Boot's own BOM) → import **`qunxu-document-bom`**.

## Getting started

### 1. Add the dependency

**Spring Boot 4 application** (auto-wired):

```xml
<dependency>
    <groupId>tech.qunxu</groupId>
    <artifactId>qunxu-document-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Plain Java (no Spring)**:

```xml
<dependency>
    <groupId>tech.qunxu</groupId>
    <artifactId>qunxu-document-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Managing versions centrally (optional)** — import the BOM once, then omit versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>tech.qunxu</groupId>
            <artifactId>qunxu-document-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>tech.qunxu</groupId>
        <artifactId>qunxu-document-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

> The structured-PDF backend (`opendataloader-pdf-core`) is not on Maven Central yet — the POM declares the
> [JitPack](https://jitpack.io) repository so it resolves from the upstream GitHub source automatically.
> See [docs/dependencies.en.md](docs/dependencies.en.md) if your environment forbids third-party build repositories.

### 2. Use it (Spring Boot)

Add the dependency to a Spring Boot 4 application and inject the auto-configured beans:

```java
@Component
class DocumentIngestService {

    private final DocumentConverter converter;
    private final ChunkerRegistry chunkers;

    public DocumentIngestService(DocumentConverter converter, ChunkerRegistry chunkers) {
        this.converter = converter;
        this.chunkers = chunkers;
    }

    public List<Chunk> ingest(byte[] pdf, String fileName) {
        ConversionResult result = converter.convert(
                DocumentSource.fromBytes(fileName, pdf),
                ConvertOptions.defaults().extractImages(true));

        if (!result.isSuccess()) {
            throw new IllegalStateException("parse failed: " + result.getErrors());
        }
        QunxuDocument document = result.getDocument();

        String markdown = document.exportAsMarkdown();   // or exportAsHtml() / exportAsText() / toJson()
        List<Chunk> chunks = chunkers.chunk(ChunkerRegistry.AUTO_NAME, document,
                new ChunkOptionsFactory(new DocumentModuleProperties()).create(512, 128, 10, null, null));
        return chunks;
    }
}
```

### 3. Use it without Spring

All core classes are plain Java; only the auto-configuration needs Spring. Add `qunxu-document-core` and assemble a
converter from the built-in backends/exporters (mirrors `DocumentAutoConfiguration`), or just run the bundled
self-check / viewer from the `qunxu-document-core/` module:

```bash
cd qunxu-document-core
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" tech.qunxu.module.document.tool.DocumentSampleRunner --make-samples
```

## Configuration (prefix `qunxu.document`)

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.enabled` | `true` | master switch |
| `qunxu.document.backends.preferred.pdf` | — | per-format preferred backend (e.g. `opendataloader`) |
| `qunxu.document.url.allow-hosts` | empty | URL source allow-list; **empty disables `fromUrl`** |
| `qunxu.document.url.allow-private-network` | `false` | allow loopback/private/link-local (SSRF gate) |
| `qunxu.document.image.enabled` | `true` | extract embedded images |
| `qunxu.document.image.sink` | `storage` | `storage` (implement `ImageSink`) / `local` / `noop` |
| `qunxu.document.ocr.enabled` | `true` | OCR deployment switch (per-request via `ConvertOptions.doOcr`) |
| `qunxu.document.cache.enabled` | `true` | parse-result cache |
| `qunxu.document.chunk.max-tokens` | `7000` | per-chunk token budget |

Full reference: [docs/configuration.en.md](docs/configuration.en.md).

## Extension points (SPI)

All overridable without touching the library (Spring beans with `@ConditionalOnMissingBean`, or programmatic):

| SPI | Purpose |
| --- | --- |
| `DocumentBackend` | add a new file format |
| `DocumentExporter` | add an output format |
| `BaseChunker` / `ChunkerRegistry` | custom chunking strategy |
| `ImageSink` | object-storage image persistence (`storage` sink) |
| `PageOcr` | plug in a recognition engine (scanned pages) |
| `ConversionCacheStore` | persistent parse cache |
| `DocumentConverterCustomizer` | runtime add/remove backends & exporters |

## Security

Document parsing is an untrusted-input attack surface. The library ships:
- **SSRF guard** on URL input — allow-list, redirect re-validation, private/loopback/link-local blocked by default.
- **Parse cache keyed by content fingerprint** — never by path/name, so cache can't be poisoned across tenants.
- **Size/quantity ceilings** — download size, image count/size, OCR page count/timeout, token budgets.
- Failures degrade loudly (status + reasons + warnings) instead of silently emitting half results.

Report vulnerabilities via [SECURITY.md](SECURITY.md).

## Documentation

- [Configuration reference](docs/configuration.en.md) · [中文](docs/configuration.md)
- [Chunking & exporters](docs/chunking.en.md) · [中文](docs/chunking.md)
- [Known limitations](docs/limitations.en.md) · [中文](docs/limitations.md)
- [Dependencies & build reproducibility](docs/dependencies.en.md) · [中文](docs/dependencies.md)
- [Roadmap](docs/roadmap.en.md) · [中文](docs/roadmap.md)
- [Changelog](CHANGELOG.en.md) · [中文](CHANGELOG.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

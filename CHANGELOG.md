# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

简体中文版：[CHANGELOG.zh-CN.md](CHANGELOG.zh-CN.md)

## [Unreleased]

### Changed

- **Split into a multi-module build** (mirrors docling-java's layout):
  - `qunxu-document-core` — pure-Java library (backends, model, exporters, chunkers, cache, OCR gate, URL source,
    self-check). **Zero Spring dependencies**; `DocumentModuleProperties` is now a plain POJO (binding moved to the
    starter's `@Bean + @ConfigurationProperties`).
  - `qunxu-document-spring-boot-starter` — Spring Boot autoconfigure (`DocumentAutoConfiguration` +
    `AutoConfiguration.imports`) on top of core.
  - `qunxu-document-bom` — version bill of materials (no code); import it to manage all qunxu-document versions
    in one place.
  - The old single `qunxu-document` jar is replaced by these artifacts; the root POM is now the aggregator/parent.

## [0.1.0] - 2025-10-13

First open-source release of qunxu-document — a standalone, self-contained document parsing library
(Java 21 + Spring Boot 4.1.1 autoconfigure, Apache-2.0).

### Added

- **Document parsing** — 8 built-in backends: `poi-docx`, `poi-pptx`, `tabular` (xlsx/csv, merged-cell aware),
  `markdown`, `plaintext`, `tika-pdf`, `opendataloader` (PDF structured: element bbox / reading order /
  heading levels / merged cells), `tika-fallback`. PDF degrades gracefully structured → `tika-pdf`.
- **Unified model** — `QunxuDocument` (pages + typed node list with provenance), JSON round-trip.
- **Exporters** — markdown / html / text / json.
- **Chunkers** — `hierarchical` (breadcrumbs), `table-row` (sheet/header aware), `flat` (chars / regex),
  plus legacy splitter aliases and `AutoChunker` auto-selection; token budgets via jtokkit CL100K_BASE.
- **Parse cache** — content-fingerprint keyed, in-memory LRU, `ConversionCacheStore` SPI for persistence.
- **SSRF-guarded URL source** — allow-list, redirect re-validation, private/loopback blocked by default.
- **OCR gate** — page rasterizer + `PageOcr` SPI; per-page budgets and timeouts surfaced as warnings.
- **Watermark / hidden-text handling** — removes watermarks (incl. Form-based rotated low-opacity),
  keeps invisible OCR text layers.
- **Self-check** — 69 built-in checks (real generated samples), reusable from JUnit/CI.

### Notes

- `org.opendataloader:opendataloader-pdf-core` is not on Maven Central; resolved via JitPack
  (see [docs/dependencies.md](docs/dependencies.md)).

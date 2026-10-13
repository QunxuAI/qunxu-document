# Roadmap

This roadmap is grounded in a code-level audit of `qunxu-document` 0.1.0. Items are grouped by theme; each lists the
code evidence, why it matters, and rough effort. Priority legend: **P0** (foundational / correctness), **P1**
(adoption / ecosystem), **P2** (differentiation).

简体中文版：[roadmap.md](roadmap.md)

---

## 1. Release & distribution (0.1.x → 1.0)

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 1.1 | Publish to Maven Central (`tech.qunxu:qunxu-document`), add GPG + sources/javadoc jars + release workflow | POM already has `licenses`/`developers` blocks | Central is the default trust signal for Java consumers | **P0** |
| 1.2 | Drop JitPack once `opendataloader-pdf-core` lands on Central | `pom.xml` declares `jitpack.io` repo + rewritten coordinate | Third-party build repos are a supply-chain objection for some orgs | **P0** |
| 1.3 | Spring Boot 3.x compatibility artifact | `DocumentAutoConfiguration` targets Boot 4.1.x (`spring-boot-autoconfigure`); core classes are plain Java | Boot 3 still dominates production; current library can't be auto-wired in Boot 3 apps | **P1** |

## 2. Format coverage

Detector (`DocumentFormat`) recognizes many more formats than the backends serve; anything without a dedicated
backend silently falls through to `TikaFallbackBackend` (generic text dump — no structure, no pages, no tables).

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 2.1 | Legacy binary Office: `.doc` / `.ppt` | `MsWordDocumentBackend.supports == DOCX only`; `PowerPointDocumentBackend.supports == PPTX only` | POI's HSSF/HSLF can parse them structurally; currently they produce flat Tika text | **P0** (common legacy corpuses) |
| 2.2 | Semantic HTML → Markdown/QunxuDocument | `DocumentFormat.HTML` exists; no `HtmlDocumentBackend`; `TikaFallbackBackend.supports != IMAGE` catches it | Web articles / exported web pages are a major RAG source; today they arrive as unstructured text | **P1** |
| 2.3 | `ODT`/`EPUB`/`RTF` dedicated backends | formats detected, only Tika fallback | Documented as "works but unstructured"; EPUB→chapters is high-value for e-book corpora | P2 |
| 2.4 | Image input backend (the `image` contract) | `ImageDocumentBackend.parse` throws `UnsupportedOperationException`; `TikaFallbackBackend` deliberately excludes `IMAGE` → `UNSUPPORTED_FORMAT` | Images are explicitly "unsupported" today; OCR/VLM description unlocks them | **P1** (see §3) |

## 3. OCR / VLM / remote parsing

This is the single biggest capability lever. The module already ships the *contracts*; the engines are absent.

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 3.1 | A reference local OCR adapter (Tesseract JNA) implementing `PageOcr` | `PageOcr.recognize(OcrRequest)` + `PdfPageRasterizer` + `OcrPostProcessor` fully wired; only the engine is missing | Scanned PDFs currently fail loudly unless a host supplies OCR | **P1** |
| 3.2 | A `RemoteDocumentBackend` implementation (Docling-serve / MinerU / PaddleOCR-VL adapter) | `RemoteDocumentBackend` is contract-only (`parse` throws `UnsupportedOperationException`) with `endpoint()`, `fields()`, `decode()` already specified | Bridging to the Python layout/OCR ecosystem reuses the whole pipeline with zero downstream change — the design's biggest payoff | **P1** |
| 3.3 | VLM image description via `ImageDocumentBackend` (PictureItem + altText) | `ImageDocumentBackend` javadoc lays out the contract; `DocumentPostProcessor` chain exists | Image-heavy PDFs get alt-text instead of bare `![…]` links | P2 |

## 4. Correctness & quality

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 4.1 | Per-backend JUnit suites (currently end-to-end only via the 69-check self-check) | only `DocumentSampleRunnerTest` + one model round-trip test exist | Isolating a regression to a backend today means running the whole sample generator | **P0** |
| 4.2 | CJK-aware token counting | `JTokkitTokenCounter` documents CL100K bias (~1.3–1.5 tok/CJK char), conservative by design | Chunks are smaller than they need to be for Chinese; a CJK-consistent counter via the `TokenCounter` SPI preserves the single-standard guarantee | **P1** |
| 4.3 | Property/fuzz tests on untrusted inputs (arbitrary bytes, truncated office files, PDF bombs) | parsers consume raw bytes via `DocumentSource`; budgets exist but no fuzzing harness | Parsing libraries live on the attack surface; fuzzing is the highest-ROI hardening | **P1** |
| 4.4 | Golden-file exporter tests (markdown/html/text/json snapshots) | exporters are pure functions of `QunxuDocument` | Cheap, high-signal protection against silent output changes | P2 |

## 5. Performance & memory

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 5.1 | Streaming / size-gated ingestion | `convert()` calls `source.bytes()` + `source.sha256()` — whole file in memory; `safeSize` caps bytes | 100 MB+ documents double memory (bytes + parsed model); disk-spill or `InputStream`-based backends needed | **P1** |
| 5.2 | Byte-aware cache eviction | `InMemoryConversionCache` is entry-count LRU only; `json-spill-threshold` exists in properties but no store implements it | A 40 MB JSON entry can evict dozens of small ones; move to byte budgets or a Caffeine store | **P1** |
| 5.3 | Concurrency audit of `OpenDataLoaderPdfBackend` | javadoc warns `DocumentProcessor` has static mutable state + a temp-file round-trip | Avoids silent corruption under concurrent PDF jobs; add a pool/guard | **P1** |
| 5.4 | Per-format benchmarks (`perfs/`) | no benchmark harness | Parse throughput/latency is the core selling point; needed to defend performance claims | **P1** |
| 5.5 | Optional parallel page processing (PDF text/OCR) | backends parse serially; `SingleFlight` only dedups concurrent same-file calls | Multi-page PDFs are the long tail of latency | P2 |

## 6. Security hardening

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 6.1 | OOXML/zip-bomb protection (entry count, compression ratio, total size) | budgets exist for images/OCR/download, but container files are opened unchecked | DOCX/XLSX are zip containers — the classic zip-bomb surface | **P0** |
| 6.2 | PDF object-count / cross-reference sanity before parsing | PDF budgets are per-page/image only | Hostile PDFs can exhaust memory before page limits kick in | **P0** |
| 6.3 | SSRF + URL-source integration tests in CI | `SsrfGuard` is unit-tested in the self-check only | Network input deserves dedicated end-to-end tests (redirect chains, DNS rebinding cases) | P2 |
| 6.4 | Dependency SBOM + signature verification | versions pinned; JitPack is a build-service trust point | Meets enterprise supply-chain expectations (CycloneDX via CI) | P2 |

## 7. Observability & ecosystem

| # | Item | Code evidence | Why | Priority |
| --- | --- | --- | --- | --- |
| 7.1 | Micrometer metrics when in a Spring context (parse time, cache hit/miss, degrade count, OCR cost) | `DocumentConverter.stats()` + `InMemoryConversionCache` hit/miss counters exist but aren't wired to Micrometer | RAG ingestion pipelines need these dashboards in production | **P1** |
| 7.2 | Docs site (mkdocs/GitHub Pages) + Javadoc | only markdown docs | Docling-grade discoverability; API reference is the first thing users look for | **P1** |
| 7.3 | `scripts/` reproducible-dependency helper (vendoring or mirror guidance for JitPack-blocked orgs) | documented in `docs/dependencies.md`, not automated | Removes the #1 adoption objection | P2 |

---

## North-star

Parity with docling's feature set for the RAG use case, without pulling in the Python ecosystem by default:

1. **Ship** — Central, Boot 3 artifact, core/starter split (1.x).
2. **Cover** — every format the detector recognizes has a *structural* backend, not a Tika text dump (2.x).
3. **Bridge** — `RemoteDocumentBackend` adapter to a layout/OCR engine (MinerU/Docling-serve/PaddleOCR-VL) so
   layout-model quality is available on demand; local Tesseract for the offline case.
4. **Trust** — fuzz + budgets + SBOM + benchmarks make "untrusted input" a documented, tested property.
5. **Stand apart** — CJK token awareness and SSRF-first URL input are differentiators docling doesn't have.

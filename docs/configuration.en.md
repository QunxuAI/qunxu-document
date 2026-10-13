# Configuration reference

All properties use the prefix `qunxu.document`. The module is a Spring Boot autoconfigure library; set them in
`application.yaml` / `application.properties` (or via env vars, e.g. `QUNXU_DOCUMENT_IMAGE_SINK=local`).

## `qunxu.document.enabled`

Master switch. When `false`, the auto-configuration backs off entirely and no beans are registered.

## `qunxu.document.backends.preferred.<format>`

Per-format preferred backend (L2 routing override). Key = lowercase format name (`pdf`, `docx`, ...), value = backend key.

```yaml
qunxu:
  document:
    backends:
      preferred:
        pdf: opendataloader   # default for PDF is opendataloader anyway; this is how you force/change it
```

## `qunxu.document.url.*` — URL input source (SSRF gate)

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.url.allow-hosts` | empty | comma-separated host allow-list; entries starting with `.` also allow subdomains. **Empty = `fromUrl` is disabled** (throws, never silently allows). |
| `qunxu.document.url.max-bytes` | `52428800` | max single download size |
| `qunxu.document.url.connect-timeout-seconds` | `10` | connect timeout |
| `qunxu.document.url.read-timeout-seconds` | `60` | read timeout |
| `qunxu.document.url.max-redirects` | `3` | max redirects followed (each hop re-validated by the SSRF guard) |
| `qunxu.document.url.allow-private-network` | `false` | allow loopback / private / link-local addresses (local dev or internal object-storage links only) |

## `qunxu.document.image.*` — embedded image extraction

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.image.enabled` | `true` | extract embedded images (off → markdown has no image links) |
| `qunxu.document.image.sink` | `storage` | `storage` (host implements `ImageSink`) / `local` (dir) / `noop` (drop) |
| `qunxu.document.image.local-root` | — | root dir when `sink=local` |
| `qunxu.document.image.base-path` | `document-images` | path prefix (`storage` sink: `prePath` hint; `local` sink: subdirectory) |
| `qunxu.document.image.url-prefix` | — | display URL prefix when `sink=local` |
| `qunxu.document.image.min-width` / `min-height` | `80` / `80` | filter out icons / lines |
| `qunxu.document.image.min-bytes` | `10240` | filter out tiny images |
| `qunxu.document.image.max-bytes` | `10485760` | skip images above this size |
| `qunxu.document.image.max-images-per-doc` | `200` | hard cap per document (counts attempts, incl. filtered) |

## `qunxu.document.ocr.*` — scanned-page OCR gate

Deployment-level rendering budgets. **Whether to OCR at all is decided per request** via `ConvertOptions.doOcr(true)` —
OCR is an external paid call, so the deployer must not default it on for callers.

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.ocr.enabled` | `true` | deployment switch (off → OCR post-processor not registered) |
| `qunxu.document.ocr.max-pages` | `20` | default page cap per document (when request doesn't set `ocrMaxPages`) |
| `qunxu.document.ocr.timeout-seconds` | `120` | total OCR budget per document (≤0 unlimited); pages beyond budget are stopped and warned |
| `qunxu.document.ocr.dpi` | `200` | rasterization DPI |
| `qunxu.document.ocr.max-side` | `2048` | long-edge pixel cap (≤0 unlimited) |
| `qunxu.document.ocr.format` | `jpeg` | raster encoding: `jpeg` / `png` |
| `qunxu.document.ocr.jpeg-quality` | `0.9` | JPEG quality (0~1) |
| `qunxu.document.ocr.max-bytes` | `6400000` | max raster bytes per page (skipped if still over after downscale) |

## `qunxu.document.cache.*` — parse-result cache

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.cache.enabled` | `true` | cache parse results (single source of truth for dedup) |
| `qunxu.document.cache.json-spill-threshold` | `1048576` | when the intermediate JSON exceeds this, spill to object storage and store only the path |
| `qunxu.document.cache.memory-max-entries` | `64` | in-memory LRU cap (default store, replaced by a persistent `ConversionCacheStore` bean if present) |

## `qunxu.document.chunk.*` — chunking defaults

| Property | Default | Description |
| --- | --- | --- |
| `qunxu.document.chunk.max-tokens` | `7000` | per-chunk token budget (under Spring AI's 7372 single-doc embedding cap) |
| `qunxu.document.chunk.include-heading-breadcrumb` | `true` | prepend heading breadcrumb to each chunk |
| `qunxu.document.chunk.rows-per-chunk` | `10` | rows per chunk for table-row chunking |

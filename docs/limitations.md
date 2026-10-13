# Known limitations

Honest list of what this library does *not* do yet, so you don't file issues for known gaps.
For the planned work, see the code-grounded [roadmap](roadmap.md).

## Functional gaps

| Area | Status | Workaround / plan |
| --- | --- | --- |
| **Image-only PDFs (scanned)** | Fail loudly with "无文本层,需 OCR" by default; succeed only with a plugged-in `PageOcr` engine | Implement `PageOcr` (the module ships the rasterizer gate, not a recognition engine). The `ImageDocumentBackend` (OCR/VLM image description) is an extension stub — not implemented. |
| **Remote parse backend** | `RemoteDocumentBackend` is contract-only (throws `UnsupportedOperationException`) | Not wired into auto-configuration; reserved for a future hosted-parsing path. |
| **Chinese token count** | CL100K_BASE is conservative for CJK (~1.3–1.5 token/char); chunks are smaller than their Chinese character count suggests | Acceptable for staying under embedding limits; a CJK-specific counter can be plugged in via `TokenCounter`. |
| **`ImageSink=storage`** | No object-storage implementation ships | Implement `ImageSink` in the host app (OSS/MinIO/…); `local` and `noop` work out of the box. |
| **OCR for PPTX/DOCX pages** | OCR gate targets PDF pages only | Not planned. |

## Platform / ecosystem constraints

| Item | Note |
| --- | --- |
| **Java 21+ required** | Bytecode targets Java 21 (major 65). |
| **Spring Boot 4 autoconfigure lives in the starter only** | `qunxu-document-spring-boot-starter` targets Spring Boot 4.1.x (`spring-boot-autoconfigure`). `qunxu-document-core` has **zero Spring dependencies**; for Spring Boot 3 or non-Spring hosts, depend on core and assemble the converter manually (see the runner's `buildConverter` as reference). |
| **`opendataloader-pdf-core` not on Maven Central** | Resolved via JitPack from the upstream GitHub source. Tracked upstream; when it lands on Central the POM will switch back to the official coordinates. See [dependencies.md](dependencies.md). |
| **`qunxu.document.url` requires an explicit allow-list** | Empty allow-list disables `fromUrl` on purpose (SSRF posture). |

## Formats that work but are unstructured

The detector recognizes more formats than the backends serve structurally. Anything without a dedicated backend
falls through to `TikaFallbackBackend` — it *parses* (no `UNSUPPORTED_FORMAT`), but produces flat text: no headings,
no pages, no tables. Today that means `.doc`, `.ppt`, `.rtf`, `.html`, `.odt`, `.epub` and structured files
(`.json/.xml/.yaml`). Images (`.png/.jpg/…`) are the one deliberate exception — they return `UNSUPPORTED_FORMAT`
rather than a content-free document.

See [roadmap.md §2 Format coverage](roadmap.md#2-format-coverage) for the plan.

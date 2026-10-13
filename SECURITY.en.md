# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.** Document parsers process untrusted files, so
we treat security reports seriously and confidentially.

Report vulnerabilities privately to **[opensource@qunxu.ai](mailto:opensource@qunxu.ai)** (PGP key available on
request). Include:

- The affected version(s) and the file/format that triggers the issue
- A minimal reproducer (sample file or crafted input) — if the file is sensitive, describe it instead
- Impact assessment and, if you have one, a suggested fix

You should receive an acknowledgement within 3 business days. We will coordinate disclosure after a fix is
released, and we credit reporters in the release notes (unless you prefer to stay anonymous).

## Security-relevant design notes

This library is an **untrusted-input attack surface** by nature. Key protections (and their limits):

| Area | Built-in protection | Residual risk / guidance |
| --- | --- | --- |
| URL input | SSRF guard: allow-list, redirect re-validation, loopback/private/link-local blocked by default | Only use `fromUrl` with an explicit allow-list; never set `allow-private-network=true` in production |
| Zip/archive bombs | — | Not yet enforced on DOCX/XLSX (container files); deploy behind a size/burst limiter |
| PDF bombs | per-page/image/OCR budgets (page count, image size, raster bytes, OCR pages/timeout) | Keep budgets at or below defaults in hostile environments |
| Cache poisoning | cache keyed by content fingerprint, never by path/name | Provide a persistent `ConversionCacheStore` behind tenant isolation if multi-tenant |
| Dependency supply chain | versions pinned in `pom.xml`; `opendataloader-pdf-core` from JitPack | JitPack builds from upstream source; verify upstream tags; Dependabot monitors versions |

## Supported versions

Only the latest release receives security fixes. Backporting is considered on request for a previous minor release.

## Scope

In scope: `qunxu-document` source code and its released artifacts. Out of scope: third-party parsers' own
vulnerabilities (POI, PDFBox, Tika, OpenDataLoader, veraPDF) — report those upstream.

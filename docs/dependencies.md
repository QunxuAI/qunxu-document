# Dependencies & build reproducibility

## Runtime dependencies

| Group:Artifact | Version (managed in `pom.xml`) | License | Purpose |
| --- | --- | --- | --- |
| `org.springframework.boot:spring-boot-autoconfigure` | 4.1.1 (BOM) | Apache-2.0 | auto-configuration & `@ConfigurationProperties` (optional at runtime for plain-Java use) |
| `org.slf4j:slf4j-api` | 2.x (BOM) | MIT | logging |
| `com.alibaba.fastjson2:fastjson2` | 2.0.65 | Apache-2.0 | `QunxuDocument` dict serialization / cache format |
| `org.apache.poi:poi-ooxml` / `poi-scratchpad` | 5.5.1 | Apache-2.0 | docx / pptx / xlsx parsing |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Apache-2.0 | PDF text + embedded images |
| `org.apache.tika:tika-core` / `tika-parsers-standard-package` | 3.3.2 | Apache-2.0 | mime detection + last-resort / pdf parsing |
| `cn.hutool:hutool-all` | 5.8.47 | Mulan PSL v2 | CSV parsing, charset detection |
| `commons-io:commons-io` | 2.18.0 | Apache-2.0 | IO utilities |
| `com.knuddels:jtokkit` | 1.1.0 | MIT | CL100K_BASE token counting |

## `opendataloader-pdf-core` — resolved via JitPack

The structured-PDF backend depends on `opendataloader-pdf-core` (`org.opendataloader`). This artifact is **not yet
published to Maven Central**; the POM therefore:

1. depends on `com.github.opendataloader-project.opendataloader-pdf:opendataloader-pdf-core:2.5.12`
   (JitPack's build of the [upstream Apache-2.0 source](https://github.com/opendataloader-project/opendataloader-pdf)),
   and
2. declares the `https://jitpack.io` repository.

Consequences:

- **Transparent for consumers**: `mvn` resolves it automatically.
- **JitPack is a third-party build service**: if your environment blocks it, either
  - add an internal mirror/proxy of JitPack, or
  - build upstream yourself and install it into your repo
    (`git clone --branch v2.5.12 --depth 1 https://github.com/opendataloader-project/opendataloader-pdf && cd java && mvn install -DskipTests`),
    then drop the JitPack `<repository>` from the POM.
- Once upstream publishes to Maven Central, this README note, the JitPack repository and the rewritten coordinate
  all get reverted to the official `org.opendataloader:opendataloader-pdf-core`.

## Build

The repo is a multi-module Maven build (parent `tech.qunxu:qunxu-document`):

```
qunxu-document/                          parent pom (aggregator + dependency management)
├── qunxu-document-core/                 pure-Java library (no Spring dependencies)
├── qunxu-document-spring-boot-starter/  Spring Boot autoconfigure (depends on core)
└── qunxu-document-bom/                  version bill of materials (no code; import to manage versions)
```

```bash
# Java 21+ and Maven 3.9+, from the repo root (builds both modules)
mvn verify          # compiles, runs the 69-check self-check + unit tests + autoconfigure smoke tests
mvn install         # installs to your local repo
```

The self-check generates real samples (docx/pptx/xlsx/pdf/md/csv) in `java.io.tmpdir` and exercises the full pipeline;
no network access or database is needed once dependencies are cached. To run the self-check's `main` directly, work in
the `qunxu-document-core/` module (see the runner's javadoc).

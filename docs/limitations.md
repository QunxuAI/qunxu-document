# 已知限制

本库*尚未*做到的事项的诚实清单，避免你对已知缺口重复提 issue。
计划内工作见基于代码审计的 [路线图](roadmap.md)。

英文版：[limitations.en.md](limitations.en.md)

## 功能缺口

| 领域 | 现状 | 变通 / 计划 |
| --- | --- | --- |
| **纯图片 PDF（扫描件）** | 默认大声报错"无文本层,需 OCR"；接入 `PageOcr` 引擎后才成功 | 实现 `PageOcr`（模块只带栅格化闸门，不带识别引擎）。`ImageDocumentBackend`（OCR/VLM 图片描述）是扩展位——未实现。 |
| **远程解析后端** | `RemoteDocumentBackend` 只有契约（抛 `UnsupportedOperationException`） | 未接入自动配置；预留给未来的托管解析路径。 |
| **中文 token 计数** | CL100K_BASE 对 CJK 保守（约 1 字 1.3~1.5 token），分块比中文实际字符数偏小 | 对"不超 embedding 上限"是安全的；可经 `TokenCounter` 换 CJK 专用计数器。 |
| **`ImageSink=storage`** | 未内置对象存储实现 | 在宿主应用中实现 `ImageSink`（OSS/MinIO/…）；`local` 与 `noop` 开箱即用。 |
| **PPTX/DOCX 页面的 OCR** | OCR 闸门只针对 PDF 页面 | 无计划。 |

## 平台 / 生态约束

| 事项 | 说明 |
| --- | --- |
| **需要 Java 21+** | 字节码目标 Java 21（major 65）。 |
| **Spring Boot 4 自动配置只在 starter** | `qunxu-document-spring-boot-starter` 面向 Spring Boot 4.1.x（`spring-boot-autoconfigure`）。`qunxu-document-core` **零 Spring 依赖**；Spring Boot 3 或非 Spring 宿主依赖 core 并手动组装（参考 runner 的 `buildConverter`）。 |
| **`opendataloader-pdf-core` 不在 Maven Central** | 经 JitPack 从上游 GitHub 源码解析。持续跟进上游；发布到 Central 后 POM 换回官方坐标。见 [dependencies.md](dependencies.md)。 |
| **`qunxu.document.url` 必须显式配白名单** | 白名单为空即禁用 `fromUrl`（SSRF 姿态）。 |

## 能解析但没有结构的格式

探测器认识的格式远多于后端结构化服务的。没有专用 Backend 的格式会落到 `TikaFallbackBackend`——它*能解析*
（不会返回 `UNSUPPORTED_FORMAT`），但产出的是扁平文本：无标题、无页码、无表格。目前包括 `.doc`、`.ppt`、
`.rtf`、`.html`、`.odt`、`.epub` 和结构化文件（`.json/.xml/.yaml`）。图片（`.png/.jpg/…`）是唯一的刻意例外——
返回 `UNSUPPORTED_FORMAT` 而不是一份没有正文的空文档。

计划见 [路线图 §2 格式覆盖](roadmap.md#2-格式覆盖)。

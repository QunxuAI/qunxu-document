# 参与贡献（qunxu-document）

感谢考虑为本项目做贡献！请先阅读[行为准则](CODE_OF_CONDUCT.md)。

英文版：[CONTRIBUTING.en.md](CONTRIBUTING.en.md)

## 如何贡献

1. 写代码前先开 **issue** 讨论 bug 或新功能（尤其是新后端 / 新分块器——这些涉及设计）。
2. Fork 仓库并创建特性分支（`feat/...`、`fix/...`）。
3. 带着测试完成改动。
4. 向 `main` 发起 Pull Request。

## 开发环境

- JDK 21+（字节码目标 Java 21）
- Maven 3.9+

```bash
mvn verify          # 编译 + 跑全部测试
```

模块内置 **69 项自检**（`DocumentSampleRunner.runSelfCheck()`），会生成真实样本（docx/pptx/xlsx/pdf/md/csv）
并断言整条链路。JUnit 测试 `DocumentSampleRunnerTest.selfCheckCountIsStable` 把检查数钉在 69——
**新增能力必须同步新增对应断言并更新该数字**。这是刻意的：防止静默回归。

## 编码约定

- 遵循现有风格（4 空格缩进，与 `.editorconfig` 一致）。
- `pom.xml` 是 Java 21 + Spring Boot 4 自动配置；除非必要，不要引入框架 starter。
- 保持库 **Spring 可选**：核心类必须是纯 Java。
- 不要加 `@Component`；新 Bean 经 `DocumentAutoConfiguration` 以 `@ConditionalOnMissingBean` 装配，
  让宿主能经 SPI 覆盖。
- 新格式 = 新 `DocumentBackend`；新输出 = 新 `DocumentExporter`；新切分 = 新 `BaseChunker`。
  每个都要注册进 `DocumentAutoConfiguration` 并扩展自检。

## 新增一种文档格式（典型流程）

1. 实现 `DocumentBackend`（参考 `MsWordDocumentBackend` 或 `TabularDocumentBackend` 的模式——
   尽量转成带页码/bbox 溯源的类型化 `QunxuDocument` 节点）。
2. 在 `DocumentAutoConfiguration` 注册。
3. 在 `SampleDocumentFactory` 加一个合成样本，并把断言加进自检。
4. 在 `README.md` 的支持格式表里补充说明。

## 提交与 PR 规范

- 提交聚焦单一逻辑变更；一个 PR 只做一件事。
- PR 标题概括改动；提及关联 issue 号。
- CI 会跑 `mvn verify`——确保它是绿的。

## 许可证

通过贡献，你同意你的贡献以项目许可证 [Apache License 2.0](LICENSE) 授权，不附加额外条款。

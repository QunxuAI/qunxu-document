# Contributing to qunxu-document

Thanks for considering a contribution! Please read the [Code of Conduct](CODE_OF_CONDUCT.md) first.

## How to contribute

1. **Open an issue** for a bug or feature before writing code (especially new backends / chunkers — design matters here).
2. Fork the repo and create a feature branch (`feat/...`, `fix/...`).
3. Make your change with tests.
4. Open a pull request against `main`.

## Development environment

- JDK 21+ (bytecode targets Java 21)
- Maven 3.9+

```bash
mvn verify          # compiles + runs all tests
```

The module has a **69-check self-check** (`DocumentSampleRunner.runSelfCheck()`) that generates real samples
(docx/pptx/xlsx/pdf/md/csv) and asserts the full pipeline. The JUnit test
`DocumentSampleRunnerTest.selfCheckCountIsStable` pins the count to 69 — **if you add capability, you must add
corresponding assertions and update that number**. This is deliberate: it prevents silent regressions.

## Coding conventions

- Follow the existing style (4-space indent, same as `.editorconfig`).
- `pom.xml` is Java 21 + Spring Boot 4 autoconfigure; don't add framework starters unless required.
- Keep the library **Spring-optional**: core classes must remain plain Java.
- Don't add `@Component`; wire new beans through `DocumentAutoConfiguration` with `@ConditionalOnMissingBean`
  so hosts can override via SPI.
- New formats = new `DocumentBackend`; new outputs = new `DocumentExporter`; new splits = new `BaseChunker`.
  Register each in `DocumentAutoConfiguration` and extend the self-check.

## Adding a new document format (typical flow)

1. Implement `DocumentBackend` (see `MsWordDocumentBackend` or `TabularDocumentBackend` for the pattern —
   convert to `QunxuDocument` typed nodes with page/bbox provenance where possible).
2. Register it in `DocumentAutoConfiguration`.
3. Add a generated sample to `SampleDocumentFactory` and route assertions to the self-check.
4. Document the format in `README.md`'s supported-formats table.

## Commit & PR guidelines

- Write focused commits; one logical change per PR.
- PR title should summarize the change; mention issue numbers.
- CI runs `mvn verify` — make sure it's green.

## License

By contributing, you agree that your contributions are licensed under [Apache License 2.0](LICENSE)
(the project license) without additional terms.

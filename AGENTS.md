# Kestra EDGAR Plugin

## What

- Provides plugin components under `io.kestra.plugin.edgar`, for SEC EDGAR (the public database of US company filings).
- Includes classes such as `company.Lookup`, `filings.List`, `filings.Download`, `filings.Trigger`, `xbrl.CompanyFacts`, `AbstractEdgarTask`, `EdgarClient`.

## Why

- What user problem does this solve? Teams that use SEC EDGAR reimplement the same plumbing in every script: the User-Agent SEC requires, its rate limit, ticker-to-CIK lookups and EDGAR's response formats.
- Why would a team adopt this plugin in a workflow? It keeps EDGAR steps in the same Kestra flow as the rest of the pipeline, with typed outputs and Kestra's retries.
- What operational/business outcome does it enable? Filing monitors and financial datasets become short flows instead of custom scripts.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `edgar`: the shared base task (`AbstractEdgarTask`), the per-run HTTP client (`EdgarClient`) and the ticker resolver (`CompanyTickers`). No tasks live in the root package.
- `edgar.company`: company tasks.
- `edgar.filings`: filing tasks; `Submissions` reads SEC's columnar submissions API and its older pages, `FilingIndex` reads a filing's `-index-headers.html` for each document's declared type, `Trigger` and `FilingWatermark` poll for new filings with per-company state in the namespace KV store.
- `edgar.xbrl`: XBRL tasks; `FactsReader` streams SEC's company facts JSON token by token.

The plugin calls SEC's public APIs only; tests use WireMock with fixtures in `src/test/resources/fixtures`, so they need no network.

### Key Plugin Classes

- `io.kestra.plugin.edgar.company.Lookup`
- `io.kestra.plugin.edgar.filings.List`
- `io.kestra.plugin.edgar.filings.Download`
- `io.kestra.plugin.edgar.filings.Trigger`
- `io.kestra.plugin.edgar.xbrl.CompanyFacts`

### Project Structure

```
plugin-edgar/
├── src/main/java/io/kestra/plugin/edgar/
├── src/test/java/io/kestra/plugin/edgar/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Keep tasks stateless: create `EdgarClient` inside `run()` and close it there; the rate limit is per run.
- Select exhibits by the type declared in the filing index, never by file name.
- Never load a whole SEC response that can be large (company facts) into memory: stream it to disk and parse it incrementally.
- The trigger fires each filing once by accession number; only save a company's state after its poll succeeds.
- `filings.Trigger` redeclares the connection properties of `AbstractEdgarTask` (a task and a trigger can't share a superclass): keep both in lockstep.
- Never put a real contact in code, tests or fixtures; use a placeholder User-Agent such as `Example Co admin@example.com`.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines

# How to use the SEC EDGAR plugin

This plugin reads company data and filings from [SEC EDGAR](https://www.sec.gov/edgar/search/), the public database of US company filings. EDGAR needs no account or API key. Each task has its own documentation page with full examples.

## Authentication

EDGAR needs no account or API key, but SEC's [fair access rules](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data) apply to every request:

- **User-Agent:** automated tools must identify themselves with a name and a contact email. Every task and the trigger require `userAgent`, for example `userAgent: "Example Co admin@example.com"`, and fail before calling SEC if it doesn't contain both.
- **Rate limit:** at most 10 requests per second from all your tools together. Each task spaces its requests to stay under `maxRequestsPerSecond` (default 5, at most 10). The limit applies to each task run, so tasks running in parallel each count against SEC's limit: lower `maxRequestsPerSecond` when several EDGAR tasks run at the same time.
- **Retries:** a request that SEC rejects with HTTP 429 or 503 is retried. Each attempt first retries once after the delay in SEC's `Retry-After` header (or 1 second if SEC sends none). If SEC still refuses, the task makes up to two more attempts, 10 and then 20 seconds later; these waits don't read `Retry-After`, so a longer delay asked by SEC can be cut short. If every attempt is refused, the task fails and suggests lowering `maxRequestsPerSecond`.

To set `userAgent` once for every EDGAR task of a flow, use a flow variable, which works on Kestra 1.x and 2.x:

```yaml
variables:
  sec_user_agent: "Example Co admin@example.com"

tasks:
  - id: lookup
    type: io.kestra.plugin.edgar.company.Lookup
    userAgent: "{{ vars.sec_user_agent }}"
    tickers:
      - AAPL
```

On Kestra 1.x, plugin defaults for the type `io.kestra.plugin.edgar` can set it for all flows. Kestra 2.0 [removes plugin defaults](https://kestra.io/docs/migration-guide/v2.0.0/plugin-defaults-removed); in Enterprise Edition, a [Policy](https://kestra.io/docs/enterprise/governance/policies) can set it instead. The contact email isn't secret, but if you'd rather not write it in flows, store it as a [secret](https://kestra.io/docs/concepts/secret) and use `userAgent: "{{ secret('SEC_USER_AGENT') }}"`.

## Tasks

### company.Lookup

Resolves stock tickers to the company's CIK (SEC's Central Index Key, the identifier the other EDGAR tasks use), name and exchange, from SEC's company ticker file. Tickers are matched case-insensitively, and `BRK.B` also matches EDGAR's `BRK-B`. The task fails, naming them, if SEC lists no company for one or more tickers. `fetchType` controls the output: `FETCH` (default) returns `rows`, `FETCH_ONE` the first company in `row`, `STORE` an ION file in internal storage.

### filings.List

Lists one company's filings, newest first, given its `ticker` or its `cik`, optionally filtered by `forms` (exact form types such as `8-K` or `10-K/A`) and by an inclusive `startDate` / `endDate` on the filing date. SEC returns the most recent filings (at least a year, or 1,000) in one response and older ones in additional pages; the task reads an older page only when `startDate` reaches it or when it still needs results, and stops at `maxResults` (default 100, at most 10,000). Each filing has its accession number, form, filing and report dates, acceptance time, 8-K item numbers and the URL of its primary document. `fetchType` works as for `company.Lookup`; use `STORE` for long histories.

### filings.Download

Downloads a filing's primary document and, optionally, its exhibits to internal storage, given the filer's `cik` and the filing's `accessionNumber` (both returned by `filings.List`). Exhibits are selected by the **document type the filer declared** in the filing index, such as `EX-99.1` for a press release, never by file name: exhibit file names follow no convention, and Intel's Q2 2026 earnings release, for example, is `q226earningsrelease.htm`. `exhibits` takes types, case-insensitively, where `*` matches any characters: `EX-99*` matches `EX-99.1` and `EX-99.2`. For material contracts use `EX-10.*`, since `EX-10*` also matches the XBRL files (`EX-101.SCH`, `EX-101.LAB`, …). Each document is streamed to internal storage under its original file name; the output gives its type, file name, description, SEC URL and storage `uri`.

### xbrl.CompanyFacts

Reads every value a company has reported in XBRL (SEC's company facts), given its `ticker` or `cik`, as flat rows: `taxonomy`, `concept`, `unit`, `value` (exact, as reported), period `start` (empty for point-in-time values such as shares outstanding) and `end`, `fiscalYear` and `fiscalPeriod`, and the `form`, `filed` date and `accessionNumber` of the filing that reported it, plus SEC's calendar `frame` when set. The same value appears once per filing that reported it; use `frame` to keep one value per calendar period.

Filters are applied while the response is read: `taxonomies` (such as `us-gaap` or `dei`), `concepts` (such as `Revenues` or `NetIncomeLoss`), `units` (such as `USD` or `USD/shares`), `forms` (such as `10-K`) and an inclusive `startDate` / `endDate` on the period end date.

**Size:** a large company's facts run to tens of megabytes and hundreds of thousands of values. The response is streamed to disk and read value by value, so the task never holds it in memory, and `fetchType` defaults to `STORE`, which writes the rows to an ION file in internal storage as they are read. Use `STORE` for unfiltered or broad requests. `FETCH` returns the rows in the task output and is meant for filtered requests: it fails, rather than truncating, when more than 10,000 values match.

## Triggers

### filings.Trigger

Polls SEC EDGAR and starts **one execution per poll** when the watched companies (`tickers`, `ciks` or both) have new filings of the watched `forms`. `{{ trigger.filings }}` lists them, oldest first, in the same shape as `filings.List` rows, and `{{ trigger.count }}` gives their number.

- **Requests:** each poll makes one request per company, plus one for SEC's ticker file when `tickers` is set, and reads only each company's recent filings. The default `interval` of 10 minutes keeps that far under SEC's limit; the minimum is 1 minute.
- **First poll:** records the filings already on SEC and fires nothing. With `lookback` (for example `P7D`), it fires once for the filings accepted within that period. `lookback` is used only while no state is saved.
- **Each filing fires once:** filings are tracked by accession number within a window of acceptance times, so filings accepted in the same second are neither skipped nor repeated.
- **Late publishing:** a filing that appears on SEC after a later one, with an earlier acceptance time, still fires, as long as it was accepted within the last 7 days. A filing that appears more than 7 days after its acceptance time is not fired.
- **Failed polls:** if reading a company fails (an SEC error, a timeout, a rate limit after retries), nothing is recorded for it, a warning is logged, and its filings are checked again on the next poll. Other companies in the same trigger are not affected. A ticker that SEC doesn't list is logged as a warning on each poll and not watched.
- **State:** kept per company in the flow's namespace KV store, under a key starting with `edgar_filings_`. Deleting it makes the next poll behave like the first one.

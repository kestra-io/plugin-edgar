package io.kestra.plugin.edgar.xbrl;

import java.io.BufferedOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.edgar.AbstractEdgarTask;
import io.kestra.plugin.edgar.CompanyTickers;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Read a company's XBRL financial data from SEC EDGAR",
    description = """
        Reads every value a company has reported in XBRL (SEC's company facts) and returns them as flat rows: \
        taxonomy, concept, unit, value, period, fiscal year and period, and the form, date and accession number of \
        the filing that reported it. Filters on taxonomy, concept, unit, form and period end date are applied while \
        the response is read, so only matching values are kept.

        A large company's facts run to tens of megabytes and hundreds of thousands of values, so `fetchType` \
        defaults to `STORE`, which writes the rows to an ION file in internal storage as they are read. `FETCH` \
        returns the rows in the output and is meant for filtered requests; it fails, rather than truncating, above \
        10,000 rows.

        SEC allows at most 10 requests per second from all your tools together; see `maxRequestsPerSecond`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Read a company's annual revenue and net income.",
            full = true,
            code = """
                id: edgar_annual_results
                namespace: company.team

                tasks:
                  - id: facts
                    type: io.kestra.plugin.edgar.xbrl.CompanyFacts
                    userAgent: "Example Co admin@example.com"
                    ticker: AAPL
                    taxonomies:
                      - us-gaap
                    concepts:
                      - Revenues
                      - NetIncomeLoss
                    units:
                      - USD
                    forms:
                      - 10-K
                    startDate: "2020-01-01"
                    fetchType: FETCH

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.facts.size }} values: {{ outputs.facts.rows | toJson }}"
                """
        ),
        @Example(
            title = "Store all of a company's XBRL facts in internal storage.",
            full = true,
            code = """
                id: edgar_all_facts
                namespace: company.team

                tasks:
                  - id: facts
                    type: io.kestra.plugin.edgar.xbrl.CompanyFacts
                    userAgent: "Example Co admin@example.com"
                    cik: "320193"
                """
        )
    }
)
public class CompanyFacts extends AbstractEdgarTask implements RunnableTask<CompanyFacts.Output> {
    static final int MAX_FETCHED_ROWS = 10_000;

    @Schema(title = "Ticker of the company", description = "For example `AAPL`. Set either `ticker` or `cik`.")
    @PluginProperty(group = "main")
    private Property<String> ticker;

    @Schema(title = "CIK of the company", description = """
        With or without leading zeros, for example `320193`. Set either `ticker` or `cik`.""")
    @PluginProperty(group = "main")
    private Property<String> cik;

    @Schema(title = "Taxonomies to keep", description = "For example `us-gaap`, `ifrs-full` or `dei`. All when empty.")
    @PluginProperty(group = "processing")
    private Property<List<String>> taxonomies;

    @Schema(title = "Concepts to keep", description = """
        Exact names, case-insensitive, for example `Revenues` or `NetIncomeLoss`. All when empty.""")
    @PluginProperty(group = "processing")
    private Property<List<String>> concepts;

    @Schema(title = "Units to keep", description = "For example `USD`, `shares` or `USD/shares`. All when empty.")
    @PluginProperty(group = "processing")
    private Property<List<String>> units;

    @Schema(title = "Forms to keep", description = """
        Form of the filing that reported the value, for example `10-K` or `10-Q`. All when empty.""")
    @PluginProperty(group = "processing")
    private Property<List<String>> forms;

    @Schema(title = "Earliest period end date to keep", description = "Inclusive, for example `2020-01-01`.")
    @PluginProperty(group = "processing")
    private Property<LocalDate> startDate;

    @Schema(title = "Latest period end date to keep", description = "Inclusive, for example `2025-12-31`.")
    @PluginProperty(group = "processing")
    private Property<LocalDate> endDate;

    @Schema(
        title = "How to return the values",
        description = """
            `STORE` (default) writes them to an ION file in internal storage and returns its `uri`; use it for \
            unfiltered requests. `FETCH` returns them in `rows` and fails above 10,000 rows, `FETCH_ONE` returns the \
            first in `row`, `NONE` returns only `size`."""
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rTicker = runContext.render(this.ticker).as(String.class).orElse(null);
        var rCik = runContext.render(this.cik).as(String.class).orElse(null);
        var rStartDate = runContext.render(this.startDate).as(LocalDate.class).orElse(null);
        var rEndDate = runContext.render(this.endDate).as(LocalDate.class).orElse(null);
        if (rStartDate != null && rEndDate != null && rStartDate.isAfter(rEndDate)) {
            throw new IllegalArgumentException("`startDate` (%s) must not be after `endDate` (%s).".formatted(rStartDate, rEndDate));
        }
        var filter = new FactsReader.Filter(
            upperCase(runContext.render(this.taxonomies).asList(String.class)),
            upperCase(runContext.render(this.concepts).asList(String.class)),
            upperCase(runContext.render(this.units).asList(String.class)),
            upperCase(runContext.render(this.forms).asList(String.class)),
            rStartDate,
            rEndDate
        );
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);

        var download = runContext.workingDir().createTempFile(".json");
        String companyCik;
        try (var client = client(runContext)) {
            companyCik = CompanyTickers.resolveCik(client, baseUri(runContext), rTicker, rCik);
            var url = URI.create(dataBaseUri(runContext) + "/api/xbrl/companyfacts/CIK" + companyCik + ".json");
            if (!client.downloadIfExists(url, download)) {
                throw new IllegalArgumentException(
                    "SEC has no XBRL facts for CIK %s. Companies that don't file XBRL financial statements, such as most funds, have none.".formatted(companyCik)
                );
            }
        }

        try {
            var output = switch (rFetchType) {
                case STORE -> store(runContext, download, filter);
                case FETCH, FETCH_ONE -> fetch(download, filter, rFetchType);
                case NONE -> count(download, filter);
            };
            runContext.logger().info("Read {} XBRL value{} of CIK {}", output.getSize(), output.getSize() == 1 ? "" : "s", companyCik);
            return output;
        } finally {
            Files.deleteIfExists(download);
        }
    }

    private static Output store(RunContext runContext, Path download, FactsReader.Filter filter) throws Exception {
        var rows = runContext.workingDir().createTempFile(".ion");
        var size = new int[1];
        try (var output = new BufferedOutputStream(Files.newOutputStream(rows), FileSerde.BUFFER_SIZE)) {
            FactsReader.read(download, filter, fact -> {
                FileSerde.write(output, fact);
                size[0]++;
            });
        }
        return Output.builder().uri(runContext.storage().putFile(rows.toFile())).size(size[0]).build();
    }

    private static Output fetch(Path download, FactsReader.Filter filter, FetchType fetchType) throws Exception {
        var facts = new ArrayList<Fact>();
        FactsReader.read(download, filter, fact -> {
            if (fetchType == FetchType.FETCH_ONE && !facts.isEmpty()) {
                return;
            }
            if (facts.size() >= MAX_FETCHED_ROWS) {
                throw new IllegalArgumentException(
                    "More than %d XBRL values match. Narrow them with `concepts`, `taxonomies`, `units`, `forms` or the dates, or set `fetchType: STORE`."
                        .formatted(MAX_FETCHED_ROWS)
                );
            }
            facts.add(fact);
        });
        return fetchType == FetchType.FETCH_ONE
            ? Output.builder().row(facts.isEmpty() ? null : facts.getFirst()).size(facts.size()).build()
            : Output.builder().rows(facts).size(facts.size()).build();
    }

    private static Output count(Path download, FactsReader.Filter filter) throws Exception {
        var size = new int[1];
        FactsReader.read(download, filter, fact -> size[0]++);
        return Output.builder().size(size[0]).build();
    }

    private static Set<String> upperCase(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the ION file holding the values", description = "Set when `fetchType` is `STORE`.")
        private final URI uri;

        @Schema(title = "The values", description = "Set when `fetchType` is `FETCH`.")
        private final List<Fact> rows;

        @Schema(title = "The first value", description = """
            Set when `fetchType` is `FETCH_ONE`; empty when nothing matches.""")
        private final Fact row;

        @Schema(title = "Number of values returned", description = "For `FETCH_ONE`, 0 or 1.")
        private final Integer size;
    }
}

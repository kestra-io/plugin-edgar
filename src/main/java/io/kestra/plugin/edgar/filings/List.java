package io.kestra.plugin.edgar.filings;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.Collectors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.edgar.AbstractEdgarTask;
import io.kestra.plugin.edgar.CompanyTickers;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    title = "List a company's filings on SEC EDGAR",
    description = """
        Lists a company's filings, newest first, optionally filtered by form type and filing date. The company is \
        given by `ticker` or `cik`. SEC returns at least the last year (or 1,000) of filings at once; older filings \
        are read page by page, and only when `startDate` reaches them or more results are needed. \
        Each filing has its accession number, form, dates, 8-K item numbers and the URL of its primary document.

        SEC allows at most 10 requests per second from all your tools together; see `maxRequestsPerSecond`."""
)
@Plugin(
    examples = {
        @Example(
            title = "List a company's 8-K filings since the start of the year.",
            full = true,
            code = """
                id: edgar_list_8k
                namespace: company.team

                tasks:
                  - id: filings
                    type: io.kestra.plugin.edgar.filings.List
                    userAgent: "Example Co admin@example.com"
                    ticker: INTC
                    forms:
                      - 8-K
                    startDate: "{{ now() | date('yyyy') }}-01-01"

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.filings.size }} 8-K filings: {{ outputs.filings.rows | toJson }}"
                """
        ),
        @Example(
            title = "Store a company's full 10-K and 10-Q history by CIK in internal storage.",
            full = true,
            code = """
                id: edgar_list_history
                namespace: company.team

                tasks:
                  - id: filings
                    type: io.kestra.plugin.edgar.filings.List
                    userAgent: "Example Co admin@example.com"
                    cik: "320193"
                    forms:
                      - 10-K
                      - 10-Q
                    maxResults: 10000
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractEdgarTask implements RunnableTask<List.Output> {
    @Schema(title = "Ticker of the company", description = "For example `INTC`. Set either `ticker` or `cik`.")
    @PluginProperty(group = "main")
    private Property<String> ticker;

    @Schema(title = "CIK of the company", description = """
        With or without leading zeros, for example `50863` or `0000050863`. Set either `ticker` or `cik`.""")
    @PluginProperty(group = "main")
    private Property<String> cik;

    @Schema(
        title = "Form types to keep",
        description = """
            For example `8-K` or `10-K`, matched exactly and case-insensitively; amendments have their own form type, such \
            as `10-K/A`. All forms when empty."""
    )
    @PluginProperty(group = "processing")
    private Property<java.util.List<String>> forms;

    @Schema(title = "Earliest filing date to keep", description = "Inclusive, for example `2026-01-01`.")
    @PluginProperty(group = "processing")
    private Property<LocalDate> startDate;

    @Schema(title = "Latest filing date to keep", description = "Inclusive, for example `2026-06-30`.")
    @PluginProperty(group = "processing")
    private Property<LocalDate> endDate;

    @Schema(
        title = "Maximum number of filings to return",
        description = "The newest filings that match are returned first. Defaults to 100, at most 10000."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults = Property.ofValue(100);

    @Schema(
        title = "How to return the filings",
        description = """
            `FETCH` (default) returns them in `rows`, `FETCH_ONE` returns the newest in `row`, `STORE` writes them to an \
            ION file in internal storage and returns its `uri`, `NONE` returns only `size`."""
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rTicker = runContext.render(this.ticker).as(String.class).orElse(null);
        var rCik = runContext.render(this.cik).as(String.class).orElse(null);
        var rForms = runContext.render(this.forms).asList(String.class).stream()
            .filter(form -> form != null && !form.isBlank())
            .map(form -> form.trim().toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        var rStartDate = runContext.render(this.startDate).as(LocalDate.class).orElse(null);
        var rEndDate = runContext.render(this.endDate).as(LocalDate.class).orElse(null);
        if (rStartDate != null && rEndDate != null && rStartDate.isAfter(rEndDate)) {
            throw new IllegalArgumentException("`startDate` (%s) must not be after `endDate` (%s).".formatted(rStartDate, rEndDate));
        }
        var rMaxResults = runContext.render(this.maxResults).as(Integer.class).orElse(100);
        if (rMaxResults < 1 || rMaxResults > 10000) {
            throw new IllegalArgumentException("`maxResults` must be between 1 and 10000, but was %d.".formatted(rMaxResults));
        }
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        java.util.List<Filing> filings;
        try (var client = client(runContext)) {
            var baseUri = baseUri(runContext);
            var companyCik = CompanyTickers.resolveCik(client, baseUri, rTicker, rCik);
            filings = Submissions.list(runContext, client, dataBaseUri(runContext), baseUri, companyCik,
                new Submissions.Query(rForms, rStartDate, rEndDate, rMaxResults));
        }
        runContext.logger().info("Found {} filing{} on SEC EDGAR", filings.size(), filings.size() == 1 ? "" : "s");

        var output = Output.builder().size(filings.size());
        switch (rFetchType) {
            case FETCH_ONE -> output.row(filings.isEmpty() ? null : filings.getFirst());
            case FETCH -> output.rows(filings);
            case STORE -> output.uri(store(runContext, filings));
            case NONE -> {
            }
        }
        return output.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The newest matching filing", description = """
            Set when `fetchType` is `FETCH_ONE`; empty when nothing matches.""")
        private final Filing row;

        @Schema(title = "The matching filings, newest first", description = "Set when `fetchType` is `FETCH`.")
        private final java.util.List<Filing> rows;

        @Schema(title = "URI of the ION file holding the filings", description = "Set when `fetchType` is `STORE`.")
        private final URI uri;

        @Schema(title = "Number of filings found")
        private final Integer size;
    }
}

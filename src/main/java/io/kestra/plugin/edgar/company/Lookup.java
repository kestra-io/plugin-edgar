package io.kestra.plugin.edgar.company;

import java.net.URI;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.edgar.AbstractEdgarTask;
import io.kestra.plugin.edgar.Company;
import io.kestra.plugin.edgar.CompanyTickers;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Look up companies on SEC EDGAR by ticker",
    description = """
        Resolves stock tickers to the company's CIK (the identifier the other EDGAR tasks use), name and exchange, \
        from SEC's company ticker file. Tickers are matched case-insensitively, and `BRK.B` also matches EDGAR's \
        `BRK-B`. The task fails, naming them, if SEC lists no company for one or more tickers.

        SEC allows at most 10 requests per second from all your tools together; see `maxRequestsPerSecond`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Look up a company's CIK and use it in the next task.",
            full = true,
            code = """
                id: edgar_lookup
                namespace: company.team

                tasks:
                  - id: lookup
                    type: io.kestra.plugin.edgar.company.Lookup
                    userAgent: "Example Co admin@example.com"
                    tickers:
                      - AAPL
                    fetchType: FETCH_ONE

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.lookup.row.name }} has CIK {{ outputs.lookup.row.cik }}"
                """
        ),
        @Example(
            title = "Look up several companies at once.",
            full = true,
            code = """
                id: edgar_lookup_many
                namespace: company.team

                tasks:
                  - id: lookup
                    type: io.kestra.plugin.edgar.company.Lookup
                    userAgent: "Example Co admin@example.com"
                    tickers:
                      - AAPL
                      - MSFT
                      - BRK.B
                """
        )
    }
)
public class Lookup extends AbstractEdgarTask implements RunnableTask<Lookup.Output> {
    @Schema(title = "Tickers to look up", description = "For example `AAPL` or `BRK.B`. Results keep this order.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> tickers;

    @Schema(
        title = "How to return the companies",
        description = """
            `FETCH` (default) returns them in `rows`, `FETCH_ONE` returns the first in `row`, `STORE` writes them to an \
            ION file in internal storage and returns its `uri`, `NONE` returns only `size`."""
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rTickers = runContext.render(this.tickers).asList(String.class);
        if (rTickers.isEmpty() || rTickers.stream().allMatch(ticker -> ticker == null || ticker.isBlank())) {
            throw new IllegalArgumentException("`tickers` must list at least one ticker, for example `AAPL`.");
        }
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        List<Company> companies;
        try (var client = client(runContext)) {
            companies = CompanyTickers.resolve(client, baseUri(runContext), rTickers.stream().filter(ticker -> ticker != null && !ticker.isBlank()).toList());
        }
        runContext.logger().info("Found {} compan{} on SEC EDGAR", companies.size(), companies.size() == 1 ? "y" : "ies");

        var output = Output.builder().size(companies.size());
        switch (rFetchType) {
            case FETCH_ONE -> output.row(companies.getFirst());
            case FETCH -> output.rows(companies);
            case STORE -> output.uri(store(runContext, companies));
            case NONE -> {
            }
        }
        return output.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The first company", description = "Set when `fetchType` is `FETCH_ONE`.")
        private final Company row;

        @Schema(title = "The companies, in the order of `tickers`", description = "Set when `fetchType` is `FETCH`.")
        private final List<Company> rows;

        @Schema(title = "URI of the ION file holding the companies", description = "Set when `fetchType` is `STORE`.")
        private final URI uri;

        @Schema(title = "Number of companies found")
        private final Integer size;
    }
}

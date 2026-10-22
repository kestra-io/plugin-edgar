package io.kestra.plugin.edgar;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Company {
    @Schema(title = "Ticker", description = "As listed by SEC; class shares use a hyphen, for example `BRK-B`.")
    String ticker;

    @Schema(title = "CIK", description = """
        SEC's Central Index Key, as the 10-digit zero-padded string EDGAR URLs use, for example `0000320193`.""")
    String cik;

    @Schema(title = "Company name", description = "As registered with SEC.")
    String name;

    @Schema(title = "Exchange", description = "For example `Nasdaq` or `NYSE`; empty when SEC lists none.")
    String exchange;
}

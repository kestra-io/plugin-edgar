package io.kestra.plugin.edgar.filings;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Filing {
    @Schema(title = "CIK of the filer", description = "The 10-digit zero-padded CIK.")
    String cik;

    @Schema(title = "Accession number", description = """
        SEC's identifier of the filing, for example `0000050863-26-000155`.""")
    String accessionNumber;

    @Schema(title = "Form type", description = "For example `8-K`, `10-Q` or `10-K/A`.")
    String form;

    @Schema(title = "Date the filing was made")
    LocalDate filingDate;

    @Schema(title = "Period or event date the filing reports on", description = "Empty when SEC lists none.")
    LocalDate reportDate;

    @Schema(title = "Time SEC accepted the filing")
    Instant acceptedAt;

    @Schema(title = "Item numbers", description = """
        For an 8-K, the items it reports, for example `2.02` (results of operations); empty for most other forms.""")
    List<String> items;

    @Schema(title = "File name of the primary document")
    String primaryDocument;

    @Schema(title = "URL of the primary document on SEC's website")
    URI primaryDocumentUrl;
}

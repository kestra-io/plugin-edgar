package io.kestra.plugin.edgar.filings;

import java.net.URI;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Document {
    @Schema(title = "Document type declared by the filer", description = """
        For example `8-K` for the primary document or `EX-99.1` for an exhibit.""")
    String type;

    @Schema(title = "File name in the filing")
    String fileName;

    @Schema(title = "Description given by the filer", description = "Often the same as the type.")
    String description;

    @Schema(title = "URL of the document on SEC's website")
    URI url;

    @Schema(title = "URI of the downloaded document in internal storage")
    URI uri;
}

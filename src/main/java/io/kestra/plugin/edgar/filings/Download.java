package io.kestra.plugin.edgar.filings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.edgar.AbstractEdgarTask;
import io.kestra.plugin.edgar.EdgarClient;

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
    title = "Download a filing's documents from SEC EDGAR",
    description = """
        Downloads a filing's primary document and, optionally, its exhibits to internal storage. Exhibits are chosen \
        by the document type the filer declared in the filing index, such as `EX-99.1` for an earnings release, \
        because exhibit file names follow no convention (one company's earnings release is `q226earningsrelease.htm`). \
        Documents are streamed to storage, so large filings are not held in memory.

        SEC allows at most 10 requests per second from all your tools together; see `maxRequestsPerSecond`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Download the latest 8-K of a company with its Exhibit 99 press releases.",
            full = true,
            code = """
                id: edgar_download_8k
                namespace: company.team

                tasks:
                  - id: latest
                    type: io.kestra.plugin.edgar.filings.List
                    userAgent: "Example Co admin@example.com"
                    ticker: INTC
                    forms:
                      - 8-K
                    maxResults: 1
                    fetchType: FETCH_ONE

                  - id: download
                    type: io.kestra.plugin.edgar.filings.Download
                    userAgent: "Example Co admin@example.com"
                    cik: "{{ outputs.latest.row.cik }}"
                    accessionNumber: "{{ outputs.latest.row.accessionNumber }}"
                    exhibits:
                      - EX-99*

                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Primary document {{ outputs.download.primaryDocument.uri }}, {{ outputs.download.exhibits | length }} exhibit(s)"
                """
        )
    }
)
public class Download extends AbstractEdgarTask implements RunnableTask<Download.Output> {
    private static final Pattern CIK = Pattern.compile("\\d{1,10}");
    private static final Pattern ACCESSION_NUMBER = Pattern.compile("\\d{10}-\\d{2}-\\d{6}");

    @Schema(title = "CIK of the filer", description = """
        With or without leading zeros, for example `50863`; `filings.List` returns it in each filing's `cik`.""")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> cik;

    @Schema(title = "Accession number of the filing", description = """
        For example `0000050863-26-000155`; `filings.List` returns it in each filing's `accessionNumber`.""")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> accessionNumber;

    @Schema(
        title = "Types of the exhibits to download",
        description = """
            Matched against the document type declared in the filing index, not the file name, and case-insensitively; \
            `*` matches any characters. For example `EX-99*` matches `EX-99.1` and `EX-99.2`. Use `EX-10.*` for material \
            contracts: `EX-10*` also matches the XBRL files, whose types start with `EX-101`. No exhibits are \
            downloaded when empty; a type that matches nothing is not an error."""
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> exhibits;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCik = runContext.render(this.cik).as(String.class).map(String::trim).orElse("");
        if (!CIK.matcher(rCik).matches()) {
            throw new IllegalArgumentException("`cik` must be a number of up to 10 digits, for example `50863`, but was `%s`.".formatted(rCik));
        }
        var rAccessionNumber = runContext.render(this.accessionNumber).as(String.class).map(String::trim).orElse("");
        if (!ACCESSION_NUMBER.matcher(rAccessionNumber).matches()) {
            throw new IllegalArgumentException(
                "`accessionNumber` must look like `0000050863-26-000155`, but was `%s`.".formatted(rAccessionNumber)
            );
        }
        var rExhibits = runContext.render(this.exhibits).asList(String.class).stream().filter(type -> type != null && !type.isBlank()).toList();
        var companyCik = "%010d".formatted(Long.parseLong(rCik));

        try (var client = client(runContext)) {
            var baseUri = baseUri(runContext);
            var folder = URI.create("%s/Archives/edgar/data/%d/%s".formatted(baseUri, Long.parseLong(rCik), rAccessionNumber.replace("-", "")));
            var indexHeaders = client.findText(URI.create(folder + "/" + rAccessionNumber + "-index-headers.html"))
                .orElseThrow(() -> new IllegalArgumentException(
                    "SEC has no filing %s for CIK %s. Check both values, for example from the output of `filings.List`.".formatted(rAccessionNumber, companyCik)
                ));
            var entries = FilingIndex.parse(indexHeaders);
            if (entries.isEmpty()) {
                throw new IllegalStateException("The index of filing %s lists no documents.".formatted(rAccessionNumber));
            }

            var primary = entries.stream().filter(entry -> entry.sequence() == 1).findFirst().orElse(entries.getFirst());
            var output = Output.builder().primaryDocument(download(runContext, client, folder, primary));

            var exhibitDocuments = new ArrayList<Document>();
            if (!rExhibits.isEmpty()) {
                var typePattern = FilingIndex.typePattern(rExhibits);
                for (var entry : entries) {
                    if (entry != primary && typePattern.matcher(entry.type()).matches()) {
                        exhibitDocuments.add(download(runContext, client, folder, entry));
                    }
                }
                if (exhibitDocuments.isEmpty()) {
                    runContext.logger().info("Filing {} has no exhibit of type {}", rAccessionNumber, String.join(", ", rExhibits));
                }
            }
            runContext.logger().info("Downloaded {} and {} exhibit{} of filing {}", primary.fileName(), exhibitDocuments.size(),
                exhibitDocuments.size() == 1 ? "" : "s", rAccessionNumber);
            return output.exhibits(exhibitDocuments).build();
        }
    }

    private static Document download(RunContext runContext, EdgarClient client, URI folder, FilingIndex.Entry entry) throws Exception {
        var url = URI.create(folder + "/" + entry.fileName());
        var file = runContext.workingDir().createTempFile();
        client.download(url, file);
        return Document.builder()
            .type(entry.type())
            .fileName(entry.fileName())
            .description(entry.description())
            .url(url)
            .uri(runContext.storage().putFile(file.toFile(), entry.fileName().substring(entry.fileName().lastIndexOf('/') + 1)))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The primary document", description = """
            Its `type` is the filing's form type, for example `8-K`.""")
        private final Document primaryDocument;

        @Schema(title = "The exhibits whose type matched `exhibits`", description = """
            In the order of the filing index; empty when none matched.""")
        private final List<Document> exhibits;
    }
}

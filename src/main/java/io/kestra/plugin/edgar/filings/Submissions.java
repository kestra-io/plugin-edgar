package io.kestra.plugin.edgar.filings;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.kestra.core.runners.RunContext;
import io.kestra.plugin.edgar.EdgarClient;

/**
 * Reads a company's filings from SEC's submissions API: the most recent ones (at least a year, or 1,000) come with
 * the company, older ones are in additional pages that are only fetched when the date range reaches them.
 */
final class Submissions {
    private Submissions() {
    }

    record Query(Set<String> forms, LocalDate startDate, LocalDate endDate, int maxResults) {
        boolean matches(String form, LocalDate filingDate) {
            return (forms.isEmpty() || forms.contains(form.toUpperCase(Locale.ROOT)))
                && (startDate == null || !filingDate.isBefore(startDate))
                && (endDate == null || !filingDate.isAfter(endDate));
        }
    }

    static List<Filing> list(RunContext runContext, EdgarClient client, URI dataBaseUri, URI baseUri, String cik, Query query) throws Exception {
        var company = client.findJson(URI.create(dataBaseUri + "/submissions/CIK" + cik + ".json"), CompanyFile.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "SEC has no filings for CIK %s. Check the CIK, or look it up from a ticker with `company.Lookup`.".formatted(cik)
            ));
        if (company.filings() == null || company.filings().recent() == null) {
            throw new IllegalStateException("SEC's submissions for CIK %s have an unexpected format: `filings.recent` is missing.".formatted(cik));
        }

        var filings = new ArrayList<Filing>();
        collect(company.filings().recent(), cik, baseUri, query, filings);

        for (var page : company.filings().files() == null ? List.<Page>of() : company.filings().files()) {
            if (filings.size() >= query.maxResults()) {
                break;
            }
            if (query.startDate() != null && page.filingTo() != null && page.filingTo().isBefore(query.startDate())) {
                // pages are listed newest first, so every following page is older still
                break;
            }
            if (query.endDate() != null && page.filingFrom() != null && page.filingFrom().isAfter(query.endDate())) {
                continue;
            }
            runContext.logger().debug("Reading older filings from {}", page.name());
            var columns = client.getJson(URI.create(dataBaseUri + "/submissions/" + page.name()), Columns.class);
            collect(columns, cik, baseUri, query, filings);
        }
        return filings;
    }

    /**
     * Returns the company's recent filings of the given forms (at least the last year, or 1,000 filings), without
     * reading older pages; empty when SEC has no filings for the CIK.
     */
    static List<Filing> recent(EdgarClient client, URI dataBaseUri, URI baseUri, String cik, Set<String> forms) throws Exception {
        var company = client.findJson(URI.create(dataBaseUri + "/submissions/CIK" + cik + ".json"), CompanyFile.class);
        if (company.isEmpty()) {
            throw new IllegalArgumentException("SEC has no filings for CIK %s. Check the CIK.".formatted(cik));
        }
        if (company.get().filings() == null || company.get().filings().recent() == null) {
            throw new IllegalStateException("SEC's submissions for CIK %s have an unexpected format: `filings.recent` is missing.".formatted(cik));
        }
        var filings = new ArrayList<Filing>();
        collect(company.get().filings().recent(), cik, baseUri, new Query(forms, null, null, Integer.MAX_VALUE), filings);
        return filings;
    }

    private static void collect(Columns columns, String cik, URI baseUri, Query query, List<Filing> collected) {
        if (columns.accessionNumber() == null || columns.form() == null || columns.filingDate() == null) {
            throw new IllegalStateException("SEC's submissions for CIK %s have an unexpected format: a filing column is missing.".formatted(cik));
        }
        for (var i = 0; i < columns.accessionNumber().size() && collected.size() < query.maxResults(); i++) {
            var form = columns.form().get(i);
            var filingDate = LocalDate.parse(columns.filingDate().get(i));
            if (!query.matches(form, filingDate)) {
                continue;
            }
            var accessionNumber = columns.accessionNumber().get(i);
            var primaryDocument = value(columns.primaryDocument(), i);
            collected.add(Filing.builder()
                .cik(cik)
                .accessionNumber(accessionNumber)
                .form(form)
                .filingDate(filingDate)
                .reportDate(blankToNull(value(columns.reportDate(), i), LocalDate::parse))
                .acceptedAt(blankToNull(value(columns.acceptanceDateTime(), i), Instant::parse))
                .items(items(value(columns.items(), i)))
                .primaryDocument(primaryDocument)
                .primaryDocumentUrl(primaryDocument == null ? null : documentUrl(baseUri, cik, accessionNumber, primaryDocument))
                .build());
        }
    }

    static URI documentUrl(URI baseUri, String cik, String accessionNumber, String document) {
        return URI.create("%s/Archives/edgar/data/%d/%s/%s".formatted(baseUri, Long.parseLong(cik), accessionNumber.replace("-", ""), document));
    }

    private static List<String> items(String items) {
        if (items == null) {
            return List.of();
        }
        return Arrays.stream(items.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static String value(List<String> column, int index) {
        return column == null || index >= column.size() ? null : column.get(index);
    }

    private static <T> T blankToNull(String value, Function<String, T> parser) {
        return value == null || value.isBlank() ? null : parser.apply(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompanyFile(String cik, String name, FilingsFile filings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FilingsFile(Columns recent, List<Page> files) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(String name, LocalDate filingFrom, LocalDate filingTo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Columns(
        List<String> accessionNumber,
        List<String> filingDate,
        List<String> reportDate,
        List<String> acceptanceDateTime,
        List<String> form,
        List<String> items,
        List<String> primaryDocument
    ) {
    }
}

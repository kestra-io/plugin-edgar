package io.kestra.plugin.edgar.filings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.edgar.EdgarTestBase;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListTest extends EdgarTestBase {
    private static final String SUBMISSIONS = "/submissions/CIK0000050863.json";
    private static final String PAGE_1 = "/submissions/CIK0000050863-submissions-001.json";
    private static final String PAGE_2 = "/submissions/CIK0000050863-submissions-002.json";

    @BeforeEach
    void stubSec() throws Exception {
        sec.stubFor(get("/files/company_tickers_exchange.json").willReturn(okJson(fixture("company_tickers_exchange.json"))));
        sec.stubFor(get(SUBMISSIONS).willReturn(okJson(fixture("CIK0000050863.json"))));
        sec.stubFor(get(PAGE_1).willReturn(okJson(fixture("CIK0000050863-submissions-001.json"))));
        // the oldest page is never stubbed: a test that needs it would fail with a 404
    }

    @Test
    void shouldListFilingsOfAFormInADateRangeWithTheirDetails() throws Exception {
        var task = builder().cik(Property.ofValue("50863")).forms(Property.ofValue(java.util.List.of("8-k")))
            .startDate(Property.ofValue(LocalDate.parse("2026-05-15"))).endDate(Property.ofValue(LocalDate.parse("2026-07-24"))).build();

        var output = task.run(runContextFactory.of());

        // the 10-Q filed on 2026-07-24 is in range but isn't an 8-K
        assertThat(output.getSize(), is(2));
        assertThat(output.getRows().stream().map(Filing::getAccessionNumber).toList(), contains("0000050863-26-000155", "0000050863-26-000118"));
        var earnings = output.getRows().getFirst();
        assertThat(earnings.getCik(), is("0000050863"));
        assertThat(earnings.getForm(), is("8-K"));
        assertThat(earnings.getFilingDate(), is(LocalDate.parse("2026-07-23")));
        assertThat(earnings.getReportDate(), is(LocalDate.parse("2026-07-23")));
        assertThat(earnings.getAcceptedAt().isBefore(Instant.parse("2026-07-24T00:00:00Z")), is(true));
        assertThat(earnings.getItems(), contains("2.02", "9.01"));
        assertThat(earnings.getPrimaryDocument(), is("intc-20260723.htm"));
        assertThat(earnings.getPrimaryDocumentUrl(), is(URI.create(sec.baseUrl() + "/Archives/edgar/data/50863/000005086326000155/intc-20260723.htm")));
        sec.verify(0, getRequestedFor(urlEqualTo(PAGE_1)));
    }

    @Test
    void shouldIncludeFilingsOnTheStartAndEndDates() throws Exception {
        var day = LocalDate.parse("2026-07-23");

        var output = builder().cik(Property.ofValue("50863")).startDate(Property.ofValue(day)).endDate(Property.ofValue(day)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Filing::getAccessionNumber).toList(), contains("0000050863-26-000155"));
    }

    @Test
    void shouldResolveATickerToItsCik() throws Exception {
        var output = builder().ticker(Property.ofValue("intc")).forms(Property.ofValue(java.util.List.of("10-K"))).maxResults(Property.ofValue(1)).build().run(runContextFactory.of());

        assertThat(output.getRows().getFirst().getAccessionNumber(), is("0000050863-26-000011"));
        sec.verify(1, getRequestedFor(urlEqualTo(SUBMISSIONS)));
    }

    @Test
    void shouldReadAnOlderPageOnlyWhenTheDateRangeReachesIt() throws Exception {
        var task = builder().cik(Property.ofValue("0000050863")).forms(Property.ofValue(java.util.List.of("10-K")))
            .startDate(Property.ofValue(LocalDate.parse("2018-01-01"))).build();

        var output = task.run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Filing::getFilingDate).toList(),
            contains(LocalDate.parse("2026-01-23"), LocalDate.parse("2019-02-01"), LocalDate.parse("2018-02-16")));
        sec.verify(1, getRequestedFor(urlEqualTo(PAGE_1)));
        sec.verify(0, getRequestedFor(urlEqualTo(PAGE_2)));
    }

    @Test
    void shouldStopAtMaxResultsWithoutReadingOlderPages() throws Exception {
        var output = builder().cik(Property.ofValue("50863")).maxResults(Property.ofValue(3)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Filing::getForm).toList(), contains("N-PX", "4", "8-K"));
        sec.verify(0, getRequestedFor(urlEqualTo(PAGE_1)));
    }

    @Test
    void shouldReturnNoFilingsWhenNothingMatches() throws Exception {
        var output = builder().cik(Property.ofValue("50863")).forms(Property.ofValue(java.util.List.of("S-1")))
            .startDate(Property.ofValue(LocalDate.parse("2020-01-01"))).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

        assertThat(output.getSize(), is(0));
        assertThat(output.getRow(), is(nullValue()));
    }

    @Test
    void shouldWriteFilingsToInternalStorageWhenStore() throws Exception {
        var runContext = runContextFactory.of();

        var output = builder().cik(Property.ofValue("50863")).forms(Property.ofValue(java.util.List.of("8-K")))
            .startDate(Property.ofValue(LocalDate.parse("2026-01-01"))).fetchType(Property.ofValue(FetchType.STORE)).build().run(runContext);

        assertThat(output.getSize(), is(3));
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8))) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertThat(rows.stream().map(row -> ((Map<?, ?>) row).get("accessionNumber")).toList(),
                contains("0001193125-26-346806", "0000050863-26-000155", "0000050863-26-000118"));
        }
    }

    @Test
    void shouldSayWhichCikHasNoFilings() {
        sec.stubFor(get("/submissions/CIK0000000042.json").willReturn(aResponse().withStatus(404)));

        var exception = assertThrows(IllegalArgumentException.class, () -> builder().cik(Property.ofValue("42")).build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("SEC has no filings for CIK 0000000042."));
    }

    @Test
    void shouldRequireExactlyOneOfTickerOrCik() {
        var neither = assertThrows(IllegalArgumentException.class, () -> builder().build().run(runContextFactory.of()));
        var both = assertThrows(IllegalArgumentException.class, () -> builder().ticker(Property.ofValue("INTC")).cik(Property.ofValue("50863")).build().run(runContextFactory.of()));

        assertThat(neither.getMessage(), containsString("Set exactly one of `ticker` or `cik`"));
        assertThat(both.getMessage(), containsString("Set exactly one of `ticker` or `cik`"));
    }

    @Test
    void shouldRejectAStartDateAfterTheEndDate() {
        var task = builder().cik(Property.ofValue("50863"))
            .startDate(Property.ofValue(LocalDate.parse("2026-02-01"))).endDate(Property.ofValue(LocalDate.parse("2026-01-01"))).build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`startDate` (2026-02-01) must not be after `endDate` (2026-01-01)."));
    }

    @Test
    void shouldRejectACikThatIsNotANumber() {
        var exception = assertThrows(IllegalArgumentException.class, () -> builder().cik(Property.ofValue("CIK50863")).build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`cik` must be a number of up to 10 digits"));
    }

    @Test
    void shouldReturnAnEmptyItemListForFormsWithoutItems() throws Exception {
        var output = builder().cik(Property.ofValue("50863")).forms(Property.ofValue(java.util.List.of("10-Q"))).maxResults(Property.ofValue(1)).build().run(runContextFactory.of());

        assertThat(output.getRows().getFirst().getItems(), is(empty()));
    }

    private List.ListBuilder<?, ?> builder() {
        return List.builder()
            .id(IdUtils.create())
            .type(List.class.getName())
            .userAgent(Property.ofValue(USER_AGENT))
            .baseUrl(Property.ofValue(sec.baseUrl()))
            .dataBaseUrl(Property.ofValue(sec.baseUrl()));
    }
}

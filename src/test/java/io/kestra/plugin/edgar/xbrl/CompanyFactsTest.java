package io.kestra.plugin.edgar.xbrl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CompanyFactsTest extends EdgarTestBase {
    private static final String APPLE = "/api/xbrl/companyfacts/CIK0000320193.json";

    @BeforeEach
    void stubSec() throws Exception {
        sec.stubFor(get("/files/company_tickers_exchange.json").willReturn(okJson(fixture("company_tickers_exchange.json"))));
        sec.stubFor(get(APPLE).willReturn(okJson(fixture("CIK0000320193-companyfacts.json"))));
    }

    @Test
    void shouldReturnFlatRowsForTheFilteredConcept() throws Exception {
        var output = builder().ticker(Property.ofValue("AAPL"))
            .taxonomies(Property.ofValue(List.of("us-gaap"))).concepts(Property.ofValue(List.of("netincomeloss")))
            .units(Property.ofValue(List.of("USD"))).forms(Property.ofValue(List.of("10-K")))
            .startDate(Property.ofValue(LocalDate.parse("2024-01-01"))).fetchType(Property.ofValue(FetchType.FETCH))
            .build().run(runContextFactory.of());

        assertThat(output.getSize(), is(3));
        assertThat(output.getUri(), is(nullValue()));
        var latest = output.getRows().getLast();
        assertThat(latest.getTaxonomy(), is("us-gaap"));
        assertThat(latest.getConcept(), is("NetIncomeLoss"));
        assertThat(latest.getUnit(), is("USD"));
        assertThat(latest.getValue(), is(new BigDecimal("112010000000")));
        assertThat(latest.getStart(), is(LocalDate.parse("2024-09-29")));
        assertThat(latest.getEnd(), is(LocalDate.parse("2025-09-27")));
        assertThat(latest.getFiscalYear(), is(2025));
        assertThat(latest.getFiscalPeriod(), is("FY"));
        assertThat(latest.getForm(), is("10-K"));
        assertThat(latest.getFiled(), is(LocalDate.parse("2025-10-31")));
        assertThat(latest.getAccessionNumber(), is("0000320193-25-000079"));
        assertThat(latest.getFrame(), is("CY2025"));
        assertThat(output.getRows().getFirst().getFrame(), is(nullValue()));
    }

    @Test
    void shouldStoreEveryFactByDefault() throws Exception {
        var runContext = runContextFactory.of();

        var output = builder().cik(Property.ofValue("320193")).build().run(runContext);

        assertThat(output.getSize(), is(13));
        assertThat(output.getRows(), is(nullValue()));
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8))) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertThat(rows.size(), is(13));
            var eps = rows.stream().map(row -> (Map<?, ?>) row)
                .filter(row -> "EarningsPerShareBasic".equals(row.get("concept")) && "2025-09-27".equals(String.valueOf(row.get("end"))))
                .findFirst().orElseThrow();
            assertThat(new BigDecimal(String.valueOf(eps.get("value"))), is(new BigDecimal("7.49")));
        }
    }

    @Test
    void shouldKeepOnlyTheGivenTaxonomyAndPointInTimeValuesHaveNoStart() throws Exception {
        var output = builder().cik(Property.ofValue("320193")).taxonomies(Property.ofValue(List.of("DEI"))).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Fact::getConcept).toList(), contains("EntityCommonStockSharesOutstanding", "EntityCommonStockSharesOutstanding"));
        assertThat(output.getRows().getFirst().getStart(), is(nullValue()));
        assertThat(output.getRows().getFirst().getUnit(), is("shares"));
    }

    @Test
    void shouldKeepOnlyTheGivenUnit() throws Exception {
        var output = builder().cik(Property.ofValue("320193")).units(Property.ofValue(List.of("usd/shares"))).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Fact::getValue).toList(), contains(new BigDecimal("6.11"), new BigDecimal("7.49")));
    }

    @Test
    void shouldKeepOnlyTheGivenForm() throws Exception {
        var output = builder().cik(Property.ofValue("320193")).forms(Property.ofValue(List.of("10-K/A"))).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Fact::getAccessionNumber).toList(), contains("0001193125-10-012091"));
    }

    @Test
    void shouldIncludeValuesEndingOnTheStartAndEndDates() throws Exception {
        var day = LocalDate.parse("2025-09-27");

        var output = builder().cik(Property.ofValue("320193")).startDate(Property.ofValue(day)).endDate(Property.ofValue(day)).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

        assertThat(output.getRows().stream().map(Fact::getConcept).toList(), containsInAnyOrder("NetIncomeLoss", "EarningsPerShareBasic"));
    }

    @Test
    void shouldReturnTheFirstMatchWhenFetchOne() throws Exception {
        var output = builder().cik(Property.ofValue("320193")).concepts(Property.ofValue(List.of("Revenues"))).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

        assertThat(output.getRow(), is(notNullValue()));
        assertThat(output.getRow().getValue(), is(new BigDecimal("53265000000")));
        assertThat(output.getSize(), is(1));
    }

    @Test
    void shouldOnlyCountWhenNone() throws Exception {
        var output = builder().cik(Property.ofValue("320193")).concepts(Property.ofValue(List.of("Revenues"))).fetchType(Property.ofValue(FetchType.NONE)).build().run(runContextFactory.of());

        assertThat(output.getSize(), is(3));
        assertThat(output.getRows(), is(nullValue()));
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void shouldRefuseToFetchMoreThanTheCapAndPointToStore() {
        var facts = new StringBuilder();
        for (int i = 0; i <= CompanyFacts.MAX_FETCHED_ROWS; i++) {
            facts.append(i == 0 ? "" : ",").append("""
                {"end":"2025-09-27","val":%d,"accn":"0000320193-25-000079","fy":2025,"fp":"FY","form":"10-K","filed":"2025-10-31"}""".formatted(i));
        }
        sec.stubFor(get(APPLE).willReturn(okJson("""
            {"cik":320193,"entityName":"Apple Inc.","facts":{"us-gaap":{"Many":{"label":"x","units":{"USD":[%s]}}}}}""".formatted(facts))));

        var exception = assertThrows(IllegalArgumentException.class, () -> builder().cik(Property.ofValue("320193")).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("More than 10000 XBRL values match."));
        assertThat(exception.getMessage(), containsString("set `fetchType: STORE`"));
    }

    @Test
    void shouldSayWhichCikHasNoFacts() {
        sec.stubFor(get("/api/xbrl/companyfacts/CIK0000000042.json").willReturn(aResponse().withStatus(404)));

        var exception = assertThrows(IllegalArgumentException.class, () -> builder().cik(Property.ofValue("42")).build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("SEC has no XBRL facts for CIK 0000000042."));
    }

    @Test
    void shouldRequireExactlyOneOfTickerOrCik() {
        var exception = assertThrows(IllegalArgumentException.class, () -> builder().ticker(Property.ofValue("AAPL")).cik(Property.ofValue("320193")).build().run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("Set exactly one of `ticker` or `cik`"));
    }

    @Test
    void shouldRejectAStartDateAfterTheEndDate() {
        var task = builder().cik(Property.ofValue("320193"))
            .startDate(Property.ofValue(LocalDate.parse("2025-02-01"))).endDate(Property.ofValue(LocalDate.parse("2025-01-01"))).build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`startDate` (2025-02-01) must not be after `endDate` (2025-01-01)."));
    }

    private CompanyFacts.CompanyFactsBuilder<?, ?> builder() {
        return CompanyFacts.builder()
            .id(IdUtils.create())
            .type(CompanyFacts.class.getName())
            .userAgent(Property.ofValue(USER_AGENT))
            .baseUrl(Property.ofValue(sec.baseUrl()))
            .dataBaseUrl(Property.ofValue(sec.baseUrl()));
    }
}

package io.kestra.plugin.edgar.company;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.edgar.EdgarTestBase;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class LookupTest extends EdgarTestBase {
    private static final String TICKERS = "/files/company_tickers_exchange.json";

    @Test
    void shouldReturnTheCompanyAndSendTheUserAgentWhenFetchOne() throws Exception {
        sec.stubFor(get(TICKERS).willReturn(okJson(fixture("company_tickers_exchange.json"))));

        var output = task(List.of("AAPL"), FetchType.FETCH_ONE).run(runContextFactory.of());

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow().getTicker(), is("AAPL"));
        assertThat(output.getRow().getCik(), is("0000320193"));
        assertThat(output.getRow().getName(), is("Apple Inc."));
        assertThat(output.getRow().getExchange(), is("Nasdaq"));
        assertThat(output.getRows(), is(nullValue()));
        sec.verify(1, getRequestedFor(urlEqualTo(TICKERS)).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    void shouldReturnCompaniesInTickerOrderAndMatchDottedClassSharesWhenFetch() throws Exception {
        sec.stubFor(get(TICKERS).willReturn(okJson(fixture("company_tickers_exchange.json"))));

        var output = task(List.of("msft", " BRK.B ", "INBT"), FetchType.FETCH).run(runContextFactory.of());

        assertThat(output.getSize(), is(3));
        assertThat(output.getRows().stream().map(company -> company.getTicker()).toList(), contains("MSFT", "BRK-B", "INBT"));
        assertThat(output.getRows().get(1).getCik(), is("0001067983"));
        assertThat(output.getRows().get(2).getExchange(), is(nullValue()));
    }

    @Test
    void shouldWriteCompaniesToInternalStorageWhenStore() throws Exception {
        sec.stubFor(get(TICKERS).willReturn(okJson(fixture("company_tickers_exchange.json"))));
        var runContext = runContextFactory.of();

        var output = task(List.of("GOOGL", "INTC"), FetchType.STORE).run(runContext);

        assertThat(output.getSize(), is(2));
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8))) {
            var rows = FileSerde.readAll(reader).collectList().block();
            assertThat(rows.stream().map(row -> ((Map<?, ?>) row).get("cik")).toList(), contains("0001652044", "0000050863"));
        }
    }

    @Test
    void shouldNameEveryUnknownTicker() throws Exception {
        sec.stubFor(get(TICKERS).willReturn(okJson(fixture("company_tickers_exchange.json"))));

        var exception = assertThrows(IllegalArgumentException.class, () -> task(List.of("AAPL", "ZZZZ", "QQQQX"), FetchType.FETCH).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("SEC lists no company with the ticker ZZZZ, QQQQX."));
    }

    @Test
    void shouldRejectAUserAgentWithoutAContactEmailBeforeCallingSec() {
        var task = task(List.of("AAPL"), FetchType.FETCH, "Example Co");

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`userAgent` must give a name and a contact email"));
        sec.verify(0, getRequestedFor(urlEqualTo(TICKERS)));
    }

    @Test
    void shouldRetryWhenSecKeepsAnswering429() throws Exception {
        sec.stubFor(get(TICKERS).inScenario("rate limit").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("still limited"));
        sec.stubFor(get(TICKERS).inScenario("rate limit").whenScenarioStateIs("still limited")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")).willSetStateTo("ok"));
        sec.stubFor(get(TICKERS).inScenario("rate limit").whenScenarioStateIs("ok")
            .willReturn(okJson(fixture("company_tickers_exchange.json"))));

        var output = task(List.of("AAPL"), FetchType.FETCH_ONE).run(runContextFactory.of());

        assertThat(output.getRow().getCik(), is("0000320193"));
        sec.verify(3, getRequestedFor(urlEqualTo(TICKERS)));
    }

    @Test
    void shouldExplainA403() {
        sec.stubFor(get(TICKERS).willReturn(aResponse().withStatus(403)));

        var exception = assertThrows(IllegalStateException.class, () -> task(List.of("AAPL"), FetchType.FETCH).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("(HTTP 403). Check that `userAgent` gives a name and a contact email"));
    }

    private Lookup task(List<String> tickers, FetchType fetchType) {
        return task(tickers, fetchType, USER_AGENT);
    }

    private Lookup task(List<String> tickers, FetchType fetchType, String userAgent) {
        return Lookup.builder()
            .id(IdUtils.create())
            .type(Lookup.class.getName())
            .userAgent(Property.ofValue(userAgent))
            .baseUrl(Property.ofValue(sec.baseUrl()))
            .tickers(Property.ofValue(tickers))
            .fetchType(Property.ofValue(fetchType))
            .build();
    }
}

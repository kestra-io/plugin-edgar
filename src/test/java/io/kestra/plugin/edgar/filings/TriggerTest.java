package io.kestra.plugin.edgar.filings;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.edgar.EdgarTestBase;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class TriggerTest extends EdgarTestBase {
    private static final String INTEL = "0000050863";
    private static final String APPLE = "0000320193";

    private Instant now;

    @BeforeEach
    void stubTickers() throws Exception {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        sec.stubFor(get("/files/company_tickers_exchange.json").willReturn(okJson(fixture("company_tickers_exchange.json"))));
    }

    @Test
    void shouldRecordTheExistingFilingsAndFireNothingOnTheFirstPoll() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).build());

        assertThat(poll.run().isPresent(), is(false));
        assertThat(poll.run().isPresent(), is(false));
    }

    @Test
    void shouldFireANewFilingOnceAndNeverAgain() throws Exception {
        var old = filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1)));
        publish(INTEL, old);
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).build());
        poll.run();

        var fresh = filing("0000050863-26-000002", "8-K", now);
        publish(INTEL, fresh, old);

        assertThat(accessions(poll.run()), contains("0000050863-26-000002"));
        assertThat(poll.run().isPresent(), is(false));
    }

    @Test
    void shouldFireFilingsAcceptedInTheSameSecondWithoutRepeatingTheOneAlreadySeen() throws Exception {
        var second = now.minus(Duration.ofMinutes(5));
        var first = filing("0000050863-26-000010", "8-K", second);
        publish(INTEL, first);
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).build());
        poll.run();

        publish(INTEL, filing("0000050863-26-000012", "8-K", second), filing("0000050863-26-000011", "8-K", second), first);

        assertThat(accessions(poll.run()), contains("0000050863-26-000011", "0000050863-26-000012"));
    }

    @Test
    void shouldNotAdvanceTheStateWhenAPollFailsAndPickUpTheMissedFilingAfterwards() throws Exception {
        var old = filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1)));
        publish(INTEL, old);
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).build());
        poll.run();
        var stateBefore = poll.state(INTEL);

        sec.stubFor(get("/submissions/CIK" + INTEL + ".json").willReturn(aResponse().withStatus(500)));
        assertThat(poll.run().isPresent(), is(false));
        assertThat(poll.state(INTEL), is(stateBefore));

        publish(INTEL, filing("0000050863-26-000002", "8-K", now), old);
        assertThat(accessions(poll.run()), contains("0000050863-26-000002"));
    }

    @Test
    void shouldKeepCompaniesIndependentWhenOneFails() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        publish(APPLE, filing("0000320193-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).tickers(Property.ofValue(List.of("AAPL"))).build());
        poll.run();
        var intelState = poll.state(INTEL);

        sec.stubFor(get("/submissions/CIK" + INTEL + ".json").willReturn(aResponse().withStatus(500)));
        publish(APPLE, filing("0000320193-26-000002", "8-K", now), filing("0000320193-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        assertThat(accessions(poll.run()), contains("0000320193-26-000002"));
        assertThat(poll.state(INTEL), is(intelState));

        publish(INTEL, filing("0000050863-26-000002", "8-K", now.minus(Duration.ofMinutes(1))), filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        assertThat(accessions(poll.run()), contains("0000050863-26-000002"));
    }

    @Test
    void shouldFireOnceWithinTheLookbackOnTheFirstPollOnly() throws Exception {
        publish(INTEL,
            filing("0000050863-26-000003", "8-K", now.minus(Duration.ofMinutes(30))),
            filing("0000050863-26-000002", "8-K", now.minus(Duration.ofHours(1))),
            filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(3))));
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).lookback(Property.ofValue(Duration.ofHours(2))).build());

        var first = poll.run();

        assertThat(accessions(first), contains("0000050863-26-000002", "0000050863-26-000003"));
        assertThat(first.orElseThrow().getTrigger().getVariables().get("count"), is(2));
        assertThat(poll.run().isPresent(), is(false));
    }

    @Test
    void shouldFireAFilingPublishedLateWithinSevenDaysButNotOlder() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).build());
        poll.run();

        publish(INTEL,
            filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))),
            filing("0000050863-26-000005", "8-K", now.minus(Duration.ofDays(2))),
            filing("0000050863-26-000004", "8-K", now.minus(Duration.ofDays(8))));

        assertThat(accessions(poll.run()), contains("0000050863-26-000005"));
    }

    @Test
    void shouldFireOnlyTheWatchedForms() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).forms(Property.ofValue(List.of("8-k"))).build());
        poll.run();

        publish(INTEL, filing("0000050863-26-000003", "10-Q", now), filing("0000050863-26-000002", "8-K", now.minus(Duration.ofMinutes(1))),
            filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));

        assertThat(accessions(poll.run()), contains("0000050863-26-000002"));
    }

    @Test
    void shouldWatchTheKnownTickersWhenOneIsUnknown() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        var poll = poll(builder().tickers(Property.ofValue(List.of("INTC", "ZZZZ"))).build());
        poll.run();

        publish(INTEL, filing("0000050863-26-000002", "8-K", now), filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));

        assertThat(accessions(poll.run()), contains("0000050863-26-000002"));
    }

    @Test
    void shouldStopThePollWhenInterruptedInsteadOfMovingToTheNextCompany() throws Exception {
        publish(INTEL, filing("0000050863-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        publish(APPLE, filing("0000320193-26-000001", "8-K", now.minus(Duration.ofHours(1))));
        // one request per second, so reading the second company waits, and that wait sees the interrupt
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863", "320193"))).maxRequestsPerSecond(Property.ofValue(1)).build());

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, poll::run);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldKeepTheStatesOfDifferentTriggersApart() {
        var one = TriggerContext.builder().namespace("company.team").flowId("a_b").triggerId("c").build();
        var other = TriggerContext.builder().namespace("company.team").flowId("a").triggerId("b_c").build();

        assertThat(Trigger.stateKey(one, INTEL), not(Trigger.stateKey(other, INTEL)));
    }

    @Test
    void shouldRefuseAnIntervalShorterThanAMinute() {
        var poll = poll(builder().ciks(Property.ofValue(List.of("50863"))).interval(Duration.ofSeconds(30)).build());

        var exception = assertThrows(IllegalArgumentException.class, poll::run);

        assertThat(exception.getMessage(), containsString("`interval` must be at least PT1M"));
    }

    @Test
    void shouldRequireAtLeastOneCompany() {
        var exception = assertThrows(IllegalArgumentException.class, poll(builder().build())::run);

        assertThat(exception.getMessage(), containsString("Set `tickers`, `ciks` or both"));
    }

    private Trigger.TriggerBuilder<?, ?> builder() {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .userAgent(Property.ofValue(USER_AGENT))
            .baseUrl(Property.ofValue(sec.baseUrl()))
            .dataBaseUrl(Property.ofValue(sec.baseUrl()));
    }

    private Poll poll(Trigger trigger) {
        var entry = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, entry.getKey(), entry.getValue());
    }

    private record Poll(Trigger trigger, ConditionContext conditionContext, TriggerContext triggerContext) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(conditionContext, triggerContext);
        }

        Object state(String cik) throws Exception {
            return conditionContext.getRunContext().namespaceKv(triggerContext.getNamespace())
                .getValue(Trigger.stateKey(triggerContext, cik)).map(value -> value.value() instanceof byte[] bytes ? new String(bytes) : String.valueOf(value.value())).orElse(null);
        }
    }

    private record FakeFiling(String accessionNumber, String form, Instant acceptedAt) {
    }

    private static FakeFiling filing(String accessionNumber, String form, Instant acceptedAt) {
        return new FakeFiling(accessionNumber, form, acceptedAt);
    }

    /**
     * Serves the company's recent filings, newest first, as SEC's submissions API does.
     */
    private void publish(String cik, FakeFiling... filings) throws Exception {
        var columns = Map.<String, List<Object>>of(
            "accessionNumber", new ArrayList<>(), "filingDate", new ArrayList<>(), "reportDate", new ArrayList<>(),
            "acceptanceDateTime", new ArrayList<>(), "form", new ArrayList<>(), "items", new ArrayList<>(), "primaryDocument", new ArrayList<>()
        );
        for (var filing : filings) {
            columns.get("accessionNumber").add(filing.accessionNumber());
            columns.get("filingDate").add(filing.acceptedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString());
            columns.get("reportDate").add("");
            columns.get("acceptanceDateTime").add(filing.acceptedAt().toString());
            columns.get("form").add(filing.form());
            columns.get("items").add("");
            columns.get("primaryDocument").add("doc.htm");
        }
        var body = JacksonMapper.ofJson().writeValueAsString(Map.of("cik", cik, "filings", Map.of("recent", columns, "files", List.of())));
        sec.stubFor(get("/submissions/CIK" + cik + ".json").willReturn(okJson(body)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> accessions(Optional<Execution> execution) {
        var filings = (List<Map<String, Object>>) execution.orElseThrow().getTrigger().getVariables().get("filings");
        return filings.stream().map(filing -> (String) filing.get("accessionNumber")).toList();
    }
}

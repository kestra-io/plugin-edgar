package io.kestra.plugin.edgar.filings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.edgar.CompanyTickers;
import io.kestra.plugin.edgar.EdgarClient;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    title = "Trigger a flow when watched companies file on SEC EDGAR",
    description = """
        Polls SEC EDGAR every `interval` and starts one execution per poll when one or more of the watched companies \
        have new filings of the given forms; `{{ trigger.filings }}` lists them, oldest first, in the same shape as \
        `filings.List` rows.

        Each poll makes one request per company, plus one for SEC's ticker file when `tickers` is set, and reads only \
        each company's recent filings. The default `interval` of 10 minutes keeps that far under SEC's limit of 10 \
        requests per second; the minimum is 1 minute.

        The first poll records the filings already on SEC and fires nothing, unless `lookback` is set: then it fires \
        once for the filings accepted within `lookback`. After that, each filing fires once, by accession number, so \
        filings accepted in the same second are neither skipped nor repeated. A filing that SEC publishes late, with \
        an earlier acceptance time, still fires as long as it was accepted within the last 7 days. If a company's poll \
        fails (SEC error or timeout), nothing is recorded for it and its filings are checked again on the next poll; \
        other companies are not affected. The state is kept per company in the flow's namespace KV store."""
)
@Plugin(
    examples = {
        @Example(
            title = "Start a flow when a company files an 8-K.",
            full = true,
            code = """
                id: edgar_new_8k
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} new 8-K: {{ trigger.filings | toJson }}"

                triggers:
                  - id: new_8k
                    type: io.kestra.plugin.edgar.filings.Trigger
                    userAgent: "Example Co admin@example.com"
                    tickers:
                      - INTC
                      - AAPL
                    forms:
                      - 8-K
                """
        ),
        @Example(
            title = "Watch annual and quarterly reports by CIK, firing on the first poll for the last 7 days.",
            full = true,
            code = """
                id: edgar_new_reports
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.filings | toJson }}"

                triggers:
                  - id: new_reports
                    type: io.kestra.plugin.edgar.filings.Trigger
                    userAgent: "Example Co admin@example.com"
                    ciks:
                      - "320193"
                    forms:
                      - 10-K
                      - 10-Q
                    interval: PT30M
                    lookback: P7D
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    private static final Pattern CIK = Pattern.compile("\\d{1,10}");
    private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Schema(title = "Tickers of the companies to watch", description = """
        For example `INTC`. Set `tickers`, `ciks` or both.""")
    @PluginProperty(group = "main")
    private Property<List<String>> tickers;

    @Schema(title = "CIKs of the companies to watch", description = """
        With or without leading zeros, for example `320193`. Set `tickers`, `ciks` or both.""")
    @PluginProperty(group = "main")
    private Property<List<String>> ciks;

    @Schema(
        title = "Form types to watch",
        description = """
            For example `8-K` or `10-K`, matched exactly and case-insensitively; amendments have their own form type, such \
            as `10-K/A`. All forms when empty."""
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> forms;

    @Schema(title = "Time between polls", description = "Defaults to 10 minutes; at least 1 minute.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(10);

    @Schema(
        title = "How far back the first poll fires",
        description = """
            For example `P7D`. Used only on the first poll, when no state is saved yet; by default the first poll fires \
            nothing."""
    )
    @PluginProperty(group = "processing")
    private Property<Duration> lookback;

    @Schema(
        title = "User-Agent sent to SEC",
        description = """
            SEC requires automated tools to identify themselves with a name and a contact email, for example \
            `Example Co admin@example.com`, and rejects requests without one. See \
            [SEC fair access](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)."""
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> userAgent;

    @Schema(
        title = "Maximum requests per second",
        description = """
            Requests made by each poll are spaced so they stay at or under this rate. Defaults to 5, at most 10, which \
            is SEC's limit. A request rejected with HTTP 429 or 503 is retried after SEC's `Retry-After` delay, then up \
            to twice more, 10 and 20 seconds later."""
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<@Min(1) @Max(10) Integer> maxRequestsPerSecond = Property.ofValue(EdgarClient.DEFAULT_MAX_REQUESTS_PER_SECOND);

    @Schema(
        title = "Base URL of the SEC website",
        description = "Where company tickers are read from. Change it only to go through a proxy or a test server."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> baseUrl = Property.ofValue(EdgarClient.DEFAULT_BASE_URL);

    @Schema(
        title = "Base URL of SEC's data API",
        description = "Where filing lists are read from. Change it only to go through a proxy or a test server."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> dataBaseUrl = Property.ofValue(EdgarClient.DEFAULT_DATA_BASE_URL);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("`interval` must be at least %s to respect SEC's fair access rules, but was %s.".formatted(MIN_INTERVAL, interval));
        }

        var rTickers = nonBlank(runContext.render(this.tickers).asList(String.class));
        var rCiks = nonBlank(runContext.render(this.ciks).asList(String.class));
        if (rTickers.isEmpty() && rCiks.isEmpty()) {
            throw new IllegalArgumentException("Set `tickers`, `ciks` or both to say which companies to watch.");
        }
        for (var cik : rCiks) {
            if (!CIK.matcher(cik).matches()) {
                throw new IllegalArgumentException("Each of `ciks` must be a number of up to 10 digits, for example `320193`, but `%s` isn't.".formatted(cik));
            }
        }
        var rForms = nonBlank(runContext.render(this.forms).asList(String.class)).stream()
            .map(form -> form.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        var rLookback = runContext.render(this.lookback).as(Duration.class).orElse(null);
        var rUserAgent = runContext.render(this.userAgent).as(String.class).orElse(null);
        var rMaxRequestsPerSecond = runContext.render(this.maxRequestsPerSecond).as(Integer.class).orElse(EdgarClient.DEFAULT_MAX_REQUESTS_PER_SECOND);
        var baseUri = EdgarClient.baseUri(runContext.render(this.baseUrl).as(String.class).orElse(EdgarClient.DEFAULT_BASE_URL), "baseUrl");
        var dataBaseUri = EdgarClient.baseUri(runContext.render(this.dataBaseUrl).as(String.class).orElse(EdgarClient.DEFAULT_DATA_BASE_URL), "dataBaseUrl");

        var kv = runContext.namespaceKv(context.getNamespace());
        var newFilings = new ArrayList<Filing>();
        try (var client = EdgarClient.of(runContext, rUserAgent, rMaxRequestsPerSecond)) {
            var companies = new LinkedHashMap<String, String>();
            rCiks.forEach(cik -> companies.putIfAbsent("%010d".formatted(Long.parseLong(cik)), "CIK " + cik));
            if (!rTickers.isEmpty()) {
                try {
                    var found = CompanyTickers.lookup(client, baseUri, rTickers);
                    rTickers.stream().filter(ticker -> !found.containsKey(ticker))
                        .forEach(ticker -> logger.warn("SEC lists no company with the ticker {}; it is not watched. Check the symbol.", ticker));
                    found.forEach((ticker, company) -> companies.putIfAbsent(company.getCik(), ticker));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    logger.warn("SEC's ticker file could not be read, so the companies given by `tickers` are checked on the next poll: {}", e.getMessage());
                }
            }

            for (var company : companies.entrySet()) {
                var key = stateKey(context, company.getKey());
                try {
                    var recent = Submissions.recent(client, dataBaseUri, baseUri, company.getKey(), rForms);
                    var result = FilingWatermark.evaluate(readState(kv, key), recent, Instant.now(), rLookback);
                    writeState(kv, key, result.state());
                    newFilings.addAll(result.newFilings());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    logger.warn("The filings of {} could not be read and will be checked on the next poll: {}", company.getValue(), e.getMessage());
                }
            }
        }

        if (newFilings.isEmpty()) {
            return Optional.empty();
        }
        newFilings.sort(Comparator.comparing(FilingWatermark::acceptedAt).thenComparing(Filing::getAccessionNumber));
        logger.info("Found {} new filing{} on SEC EDGAR", newFilings.size(), newFilings.size() == 1 ? "" : "s");
        var output = Output.builder().filings(newFilings).count(newFilings.size()).build();
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    /**
     * Each part is prefixed with its length, so that different namespaces, flows and triggers can't share a key.
     */
    static String stateKey(TriggerContext context, String cik) {
        return "edgar_filings_%d_%s_%d_%s_%d_%s_%s".formatted(
            context.getNamespace().length(), context.getNamespace(),
            context.getFlowId().length(), context.getFlowId(),
            context.getTriggerId().length(), context.getTriggerId(),
            cik
        );
    }

    private static FilingWatermark.State readState(KVStore kv, String key) throws Exception {
        var stored = kv.getValue(key);
        if (stored.isEmpty()) {
            return null;
        }
        Map<String, Object> json = switch (stored.get().value()) {
            case byte[] bytes -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case String text -> JacksonMapper.ofJson().readValue(text, MAP_TYPE);
            case Map<?, ?> map -> JacksonMapper.ofJson().convertValue(map, MAP_TYPE);
            default -> throw new IllegalStateException("The EDGAR trigger state '%s' has an unexpected type.".formatted(key));
        };
        var seen = ((List<?>) json.getOrDefault("seen", List.of())).stream().map(String::valueOf).collect(Collectors.toCollection(HashSet::new));
        return new FilingWatermark.State(Instant.parse(String.valueOf(json.get("since"))), seen);
    }

    private static void writeState(KVStore kv, String key, FilingWatermark.State state) throws Exception {
        var json = JacksonMapper.ofJson().writeValueAsString(Map.of("since", state.since().toString(), "seen", state.seen().stream().sorted().toList()));
        // no expiration: an expired state would make the next poll start over and miss filings
        kv.put(key, new KVValueAndMetadata(new KVMetadata("SEC EDGAR filings trigger state", (Duration) null), json.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> nonBlank(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "New filings, oldest first", description = """
            In the same shape as `filings.List` rows. Use `{{ trigger.filings }}`.""")
        private final List<Filing> filings;

        @Schema(title = "Number of new filings")
        private final Integer count;
    }
}

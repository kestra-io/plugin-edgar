package io.kestra.plugin.edgar;

import java.io.BufferedOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

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
public abstract class AbstractEdgarTask extends Task {
    @Schema(
        title = "User-Agent sent to SEC",
        description = """
            SEC requires automated tools to identify themselves with a name and a contact email, for example \
            `Example Co admin@example.com`, and rejects requests without one. See \
            [SEC fair access](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data)."""
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> userAgent;

    @Schema(
        title = "Maximum requests per second",
        description = """
            Requests made by this task are spaced so they stay at or under this rate. Defaults to 5, at most 10, \
            which is SEC's limit. The limit applies to each task run: tasks that run in parallel each count \
            against SEC's limit, so lower this value when several EDGAR tasks run at the same time. A request \
            rejected with HTTP 429 or 503 is retried after SEC's `Retry-After` delay, then up to twice more, 10 and \
            20 seconds later."""
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    protected Property<@Min(1) @Max(10) Integer> maxRequestsPerSecond = Property.ofValue(EdgarClient.DEFAULT_MAX_REQUESTS_PER_SECOND);

    @Schema(
        title = "Base URL of the SEC website",
        description = """
            Where company tickers and filing documents are read from. Change it only to go through a proxy or a test \
            server."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<String> baseUrl = Property.ofValue(EdgarClient.DEFAULT_BASE_URL);

    @Schema(
        title = "Base URL of SEC's data API",
        description = """
            Where filing lists and XBRL data are read from. Change it only to go through a proxy or a test server."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected Property<String> dataBaseUrl = Property.ofValue(EdgarClient.DEFAULT_DATA_BASE_URL);

    protected EdgarClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        var rUserAgent = runContext.render(this.userAgent).as(String.class).orElse(null);
        var rMaxRequestsPerSecond = runContext.render(this.maxRequestsPerSecond).as(Integer.class).orElse(EdgarClient.DEFAULT_MAX_REQUESTS_PER_SECOND);
        return EdgarClient.of(runContext, rUserAgent, rMaxRequestsPerSecond);
    }

    protected URI baseUri(RunContext runContext) throws IllegalVariableEvaluationException {
        return EdgarClient.baseUri(runContext.render(this.baseUrl).as(String.class).orElse(EdgarClient.DEFAULT_BASE_URL), "baseUrl");
    }

    protected URI dataBaseUri(RunContext runContext) throws IllegalVariableEvaluationException {
        return EdgarClient.baseUri(runContext.render(this.dataBaseUrl).as(String.class).orElse(EdgarClient.DEFAULT_DATA_BASE_URL), "dataBaseUrl");
    }

    protected static URI store(RunContext runContext, List<?> rows) throws Exception {
        var file = runContext.workingDir().createTempFile(".ion");
        try (var output = new BufferedOutputStream(Files.newOutputStream(file), FileSerde.BUFFER_SIZE)) {
            for (var row : rows) {
                FileSerde.write(output, row);
            }
        }
        return runContext.storage().putFile(file.toFile());
    }
}

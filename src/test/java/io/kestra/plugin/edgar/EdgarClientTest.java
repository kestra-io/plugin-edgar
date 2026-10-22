package io.kestra.plugin.edgar;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EdgarClientTest extends EdgarTestBase {
    @Test
    void shouldSpaceRequestsToStayUnderMaxRequestsPerSecond() throws Exception {
        sec.stubFor(get(anyUrl()).willReturn(okJson("{}")));

        try (var client = EdgarClient.of(runContextFactory.of(), USER_AGENT, 2)) {
            for (int i = 0; i < 3; i++) {
                client.getJson(URI.create(sec.baseUrl() + "/" + i), Map.class);
            }
        }

        var times = sec.findAll(anyRequestedFor(anyUrl())).stream().map(request -> request.getLoggedDate().getTime()).sorted().toList();
        var gaps = new ArrayList<Long>();
        for (int i = 1; i < times.size(); i++) {
            gaps.add(times.get(i) - times.get(i - 1));
        }
        // 2 requests per second means one every 500 ms; allow a little clock and logging slack
        assertThat(gaps, everyItem(greaterThanOrEqualTo(450L)));
    }

    @Test
    void shouldRejectMoreThanTenRequestsPerSecond() {
        var exception = assertThrows(IllegalArgumentException.class, () -> EdgarClient.of(runContextFactory.of(), USER_AGENT, 11));

        assertThat(exception.getMessage(), containsString("`maxRequestsPerSecond` must be between 1 and 10 (SEC's limit), but was 11."));
    }

    @Test
    void shouldRejectAUserAgentThatIsOnlyAnEmail() {
        var exception = assertThrows(IllegalArgumentException.class, () -> EdgarClient.of(runContextFactory.of(), "admin@example.com", 5));

        assertThat(exception.getMessage(), containsString("`userAgent` must give a name and a contact email"));
    }

    @Test
    void shouldFailClearlyOnAnEmptyResponse() throws Exception {
        sec.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)));

        try (var client = EdgarClient.of(runContextFactory.of(), USER_AGENT, 5)) {
            var exception = assertThrows(IllegalStateException.class, () -> client.getJson(URI.create(sec.baseUrl() + "/empty"), Map.class));

            assertThat(exception.getMessage(), containsString("SEC EDGAR returned an empty response for " + sec.baseUrl() + "/empty."));
        }
    }
}

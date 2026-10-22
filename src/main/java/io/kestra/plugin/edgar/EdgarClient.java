package io.kestra.plugin.edgar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

/**
 * HTTP client for SEC EDGAR, created for one task run and closed at its end. It sends the required User-Agent,
 * spaces requests to stay under the configured rate, and lets Kestra's HTTP client retry 429 and 503 responses.
 */
public final class EdgarClient implements AutoCloseable {
    public static final String DEFAULT_BASE_URL = "https://www.sec.gov";
    public static final String DEFAULT_DATA_BASE_URL = "https://data.sec.gov";
    public static final int DEFAULT_MAX_REQUESTS_PER_SECOND = 5;
    public static final int MAX_REQUESTS_PER_SECOND = 10;

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final List<Integer> RETRIED_STATUS_CODES = List.of(429, 503);

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final String userAgent;
    private final long minIntervalNanos;
    private long nextRequestAt;

    private EdgarClient(RunContext runContext, HttpClient httpClient, String userAgent, int maxRequestsPerSecond) {
        this.runContext = runContext;
        this.httpClient = httpClient;
        this.userAgent = userAgent;
        this.minIntervalNanos = Duration.ofSeconds(1).toNanos() / maxRequestsPerSecond;
        this.nextRequestAt = System.nanoTime();
    }

    public static EdgarClient of(RunContext runContext, String userAgent, int maxRequestsPerSecond) {
        if (userAgent == null || userAgent.isBlank() || !EMAIL.matcher(userAgent).find() || EMAIL.matcher(userAgent.trim()).matches()) {
            throw new IllegalArgumentException(
                "`userAgent` must give a name and a contact email, for example `Example Co admin@example.com`: SEC rejects automated requests without them."
            );
        }
        if (maxRequestsPerSecond < 1 || maxRequestsPerSecond > MAX_REQUESTS_PER_SECOND) {
            throw new IllegalArgumentException(
                "`maxRequestsPerSecond` must be between 1 and %d (SEC's limit), but was %d.".formatted(MAX_REQUESTS_PER_SECOND, maxRequestsPerSecond)
            );
        }

        // Apache's own retry honours Retry-After once per attempt; Kestra's retry between attempts doesn't read it,
        // so its waits start at 10 s, longer than SEC usually asks for.
        var configuration = HttpConfiguration.builder()
            .retryOnStatusCodes(Property.ofValue(RETRIED_STATUS_CODES))
            .retry(Exponential.builder()
                .interval(Duration.ofSeconds(10))
                .delayFactor(2.0)
                .maxInterval(Duration.ofSeconds(60))
                .maxAttempts(3)
                .build())
            .build();

        try {
            var httpClient = HttpClient.builder().runContext(runContext).configuration(configuration).build();
            return new EdgarClient(runContext, httpClient, userAgent.trim(), maxRequestsPerSecond);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the HTTP client for SEC EDGAR: %s".formatted(e.getMessage()), e);
        }
    }

    public static URI baseUri(String url, String property) {
        try {
            var uri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("`%s` must be an absolute URL such as `%s`, but was `%s`.".formatted(property, DEFAULT_BASE_URL, url));
        }
    }

    public <T> T getJson(URI uri, Class<T> type) throws IOException, InterruptedException {
        return parse(uri, get(uri, false), type);
    }

    /**
     * Same as {@link #getJson(URI, Class)}, but empty when SEC answers 404, so the caller can say what wasn't found.
     */
    public <T> Optional<T> findJson(URI uri, Class<T> type) throws IOException, InterruptedException {
        var body = get(uri, true);
        return body == null ? Optional.empty() : Optional.of(parse(uri, body, type));
    }

    /**
     * Returns the body of a text document such as a filing index, or empty when SEC answers 404.
     */
    public Optional<String> findText(URI uri) throws InterruptedException {
        return Optional.ofNullable(get(uri, true));
    }

    /**
     * Streams a document to {@code target} without holding it in memory.
     */
    public void download(URI uri, Path target) throws InterruptedException {
        download(uri, target, false);
    }

    /**
     * Same as {@link #download(URI, Path)}, but returns {@code false} when SEC answers 404, so the caller can say what wasn't found.
     */
    public boolean downloadIfExists(URI uri, Path target) throws InterruptedException {
        return download(uri, target, true);
    }

    private boolean download(URI uri, Path target, boolean notFoundAsFalse) throws InterruptedException {
        waitForRateLimit();
        runContext.logger().debug("GET {}", uri);
        try {
            httpClient.request(request(uri, "*/*"), response -> {
                try (var body = response.getBody()) {
                    if (body == null) {
                        throw new IllegalStateException("SEC EDGAR returned an empty response for %s.".formatted(uri));
                    }
                    Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return true;
        } catch (HttpClientResponseException e) {
            var status = e.getResponse() != null ? e.getResponse().getStatus().getCode() : -1;
            if (status == 404 && notFoundAsFalse) {
                return false;
            }
            throw new IllegalStateException(errorMessage(uri, status), e);
        } catch (HttpClientException e) {
            throw new IllegalStateException("Could not reach SEC EDGAR at %s: %s".formatted(uri.getHost(), e.getMessage()), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Download from SEC EDGAR failed for %s: %s".formatted(uri, e.getMessage()), e);
        }
    }

    private static <T> T parse(URI uri, String body, Class<T> type) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("SEC EDGAR returned an empty response for %s.".formatted(uri));
        }
        return JacksonMapper.ofJson().readValue(body, type);
    }

    private String get(URI uri, boolean notFoundAsNull) throws InterruptedException {
        waitForRateLimit();
        runContext.logger().debug("GET {}", uri);

        try {
            return httpClient.request(request(uri, "application/json, text/html;q=0.9, */*;q=0.8"), String.class).getBody();
        } catch (HttpClientResponseException e) {
            var status = e.getResponse() != null ? e.getResponse().getStatus().getCode() : -1;
            if (status == 404 && notFoundAsNull) {
                return null;
            }
            throw new IllegalStateException(errorMessage(uri, status), e);
        } catch (HttpClientException e) {
            throw new IllegalStateException("Could not reach SEC EDGAR at %s: %s".formatted(uri.getHost(), e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException("Request to SEC EDGAR failed for %s: %s".formatted(uri, e.getMessage()), e);
        }
    }

    private HttpRequest request(URI uri, String accept) {
        return HttpRequest.builder()
            .uri(uri)
            .method("GET")
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept", accept)
            .build();
    }

    private void waitForRateLimit() throws InterruptedException {
        var now = System.nanoTime();
        if (nextRequestAt > now) {
            Thread.sleep(Duration.ofNanos(nextRequestAt - now));
            now = System.nanoTime();
        }
        nextRequestAt = Math.max(now, nextRequestAt) + minIntervalNanos;
    }

    private static String errorMessage(URI uri, int status) {
        return switch (status) {
            case 403 -> "SEC refused the request for %s (HTTP 403). Check that `userAgent` gives a name and a contact email; SEC also answers 403 when the request rate is too high."
                .formatted(uri);
            case 404 -> "SEC has no document at %s (HTTP 404).".formatted(uri);
            case 429, 503 -> "SEC kept rejecting the request for %s (HTTP %d) after retries. Lower `maxRequestsPerSecond` or run fewer EDGAR tasks at the same time."
                .formatted(uri, status);
            default -> "SEC returned HTTP %d for %s.".formatted(status, uri);
        };
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}

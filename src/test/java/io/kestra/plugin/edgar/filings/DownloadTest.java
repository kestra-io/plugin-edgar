package io.kestra.plugin.edgar.filings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.edgar.EdgarTestBase;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class DownloadTest extends EdgarTestBase {
    // Intel's Q2 2026 earnings 8-K: the earnings release is exhibit EX-99.1, filed as q226earningsrelease.htm
    private static final String EARNINGS = "/Archives/edgar/data/50863/000005086326000155";
    // Intel's August 2026 8-K: exhibits EX-1.1, EX-5.1, EX-99.1 and EX-99.2, plus XBRL files and graphics
    private static final String OFFERING = "/Archives/edgar/data/50863/000119312526346806";

    @BeforeEach
    void stubSec() throws IOException {
        sec.stubFor(get(EARNINGS + "/0000050863-26-000155-index-headers.html").willReturn(ok(fixture("0000050863-26-000155-index-headers.html"))));
        sec.stubFor(get(EARNINGS + "/intc-20260723.htm").willReturn(ok("<html>Intel 8-K</html>")));
        sec.stubFor(get(EARNINGS + "/q226earningsrelease.htm").willReturn(ok("<html>Intel reports second-quarter 2026 results</html>")));

        sec.stubFor(get(OFFERING + "/0001193125-26-346806-index-headers.html").willReturn(ok(fixture("0001193125-26-346806-index-headers.html"))));
        for (var file : List.of("d117670d8k.htm", "d117670dex11.htm", "d117670dex51.htm", "d117670dex991.htm", "d117670dex992.htm")) {
            sec.stubFor(get(OFFERING + "/" + file).willReturn(ok("<html>" + file + "</html>")));
        }
    }

    @Test
    void shouldFindAnExhibitByItsDeclaredTypeWhenTheFileNameGivesNoHint() throws Exception {
        var runContext = runContextFactory.of();

        var output = task("50863", "0000050863-26-000155", List.of("EX-99.1")).run(runContext);

        assertThat(output.getPrimaryDocument().getType(), is("8-K"));
        assertThat(output.getPrimaryDocument().getFileName(), is("intc-20260723.htm"));
        assertThat(read(runContext, output.getPrimaryDocument().getUri()), is("<html>Intel 8-K</html>"));
        assertThat(output.getExhibits().size(), is(1));
        var release = output.getExhibits().getFirst();
        assertThat(release.getType(), is("EX-99.1"));
        assertThat(release.getFileName(), is("q226earningsrelease.htm"));
        assertThat(release.getUrl(), is(URI.create(sec.baseUrl() + EARNINGS + "/q226earningsrelease.htm")));
        assertThat(read(runContext, release.getUri()), is("<html>Intel reports second-quarter 2026 results</html>"));
        assertThat(release.getUri().getPath().endsWith("/q226earningsrelease.htm"), is(true));
        // only the index, the primary document and the exhibit are fetched, no XBRL files or graphics
        assertThat(sec.findAll(anyRequestedFor(urlMatching(EARNINGS + "/.*"))).size(), is(3));
    }

    @Test
    void shouldNotMatchExhibitsByFileName() throws Exception {
        var output = task("50863", "0000050863-26-000155", List.of("*EARNINGS*")).run(runContextFactory.of());

        assertThat(output.getExhibits(), is(empty()));
        sec.verify(0, getRequestedFor(urlEqualTo(EARNINGS + "/q226earningsrelease.htm")));
    }

    @Test
    void shouldMatchSeveralExhibitsWithAWildcardInIndexOrder() throws Exception {
        var output = task("0000050863", "0001193125-26-346806", List.of("EX-99*")).run(runContextFactory.of());

        assertThat(output.getExhibits().stream().map(Document::getType).toList(), contains("EX-99.1", "EX-99.2"));
        assertThat(output.getExhibits().stream().map(Document::getFileName).toList(), contains("d117670dex991.htm", "d117670dex992.htm"));
    }

    @Test
    void shouldMatchAnExactTypeCaseInsensitively() throws Exception {
        var output = task("50863", "0001193125-26-346806", List.of("ex-5.1")).run(runContextFactory.of());

        assertThat(output.getExhibits().stream().map(Document::getType).toList(), contains("EX-5.1"));
    }

    @Test
    void shouldNotListThePrimaryDocumentAsAnExhibit() throws Exception {
        var output = task("50863", "0000050863-26-000155", List.of("8-K")).run(runContextFactory.of());

        assertThat(output.getPrimaryDocument().getType(), is("8-K"));
        assertThat(output.getExhibits(), is(empty()));
    }

    @Test
    void shouldDownloadOnlyThePrimaryDocumentWhenNoExhibitsAreRequested() throws Exception {
        var output = task("50863", "0001193125-26-346806", null).run(runContextFactory.of());

        assertThat(output.getPrimaryDocument().getFileName(), is("d117670d8k.htm"));
        assertThat(output.getExhibits(), is(empty()));
        assertThat(sec.findAll(anyRequestedFor(urlMatching(OFFERING + "/.*"))).size(), is(2));
    }

    @Test
    void shouldSucceedWithoutExhibitsWhenNoTypeMatches() throws Exception {
        var output = task("50863", "0000050863-26-000155", List.of("EX-3*")).run(runContextFactory.of());

        assertThat(output.getExhibits(), is(empty()));
    }

    @Test
    void shouldTreatTheWildcardLiterallySoEx10AlsoMatchesXbrlTypes() throws Exception {
        sec.stubFor(get(EARNINGS + "/intc-20260723.xsd").willReturn(ok("<xsd/>")));
        sec.stubFor(get(EARNINGS + "/intc-20260723_lab.xml").willReturn(ok("<lab/>")));
        sec.stubFor(get(EARNINGS + "/intc-20260723_pre.xml").willReturn(ok("<pre/>")));

        var wide = task("50863", "0000050863-26-000155", List.of("EX-10*")).run(runContextFactory.of());
        var contractsOnly = task("50863", "0000050863-26-000155", List.of("EX-10.*")).run(runContextFactory.of());

        assertThat(wide.getExhibits().stream().map(Document::getType).toList(), contains("EX-101.SCH", "EX-101.LAB", "EX-101.PRE"));
        assertThat(contractsOnly.getExhibits(), is(empty()));
    }

    @Test
    void shouldStoreALargeDocumentIntact() throws Exception {
        var large = "x".repeat(5 * 1024 * 1024);
        sec.stubFor(get(EARNINGS + "/intc-20260723.htm").willReturn(ok(large)));
        var runContext = runContextFactory.of();

        var output = task("50863", "0000050863-26-000155", null).run(runContext);

        assertThat(read(runContext, output.getPrimaryDocument().getUri()).length(), is(large.length()));
    }

    @Test
    void shouldSayWhichFilingDoesNotExist() {
        sec.stubFor(get(urlMatching("/Archives/edgar/data/50863/000005086326999999/.*")).willReturn(aResponse().withStatus(404)));

        var exception = assertThrows(IllegalArgumentException.class, () -> task("50863", "0000050863-26-999999", null).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("SEC has no filing 0000050863-26-999999 for CIK 0000050863."));
    }

    @Test
    void shouldFailClearlyWhenADocumentIsMissing() {
        sec.stubFor(get(EARNINGS + "/q226earningsrelease.htm").willReturn(aResponse().withStatus(404)));

        var exception = assertThrows(IllegalStateException.class, () -> task("50863", "0000050863-26-000155", List.of("EX-99.1")).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("SEC has no document at " + sec.baseUrl() + EARNINGS + "/q226earningsrelease.htm (HTTP 404)."));
    }

    @Test
    void shouldRejectAMalformedAccessionNumber() {
        var exception = assertThrows(IllegalArgumentException.class, () -> task("50863", "000005086326000155", null).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`accessionNumber` must look like `0000050863-26-000155`"));
    }

    @Test
    void shouldRejectACikThatIsNotANumber() {
        var exception = assertThrows(IllegalArgumentException.class, () -> task("INTC", "0000050863-26-000155", null).run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("`cik` must be a number of up to 10 digits"));
    }

    private Download task(String cik, String accessionNumber, List<String> exhibits) {
        var builder = Download.builder()
            .id(IdUtils.create())
            .type(Download.class.getName())
            .userAgent(Property.ofValue(USER_AGENT))
            .baseUrl(Property.ofValue(sec.baseUrl()))
            .cik(Property.ofValue(cik))
            .accessionNumber(Property.ofValue(accessionNumber));
        if (exhibits != null) {
            builder.exhibits(Property.ofValue(exhibits));
        }
        return builder.build();
    }

    private static String read(RunContext runContext, URI uri) throws IOException {
        try (var input = runContext.storage().getFile(uri)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

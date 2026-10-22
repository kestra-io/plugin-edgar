package io.kestra.plugin.edgar;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Resolves tickers to companies with SEC's ticker file, which lists every company with a ticker, its CIK and its exchange.
 */
public final class CompanyTickers {
    static final String PATH = "/files/company_tickers_exchange.json";
    private static final Pattern CIK = Pattern.compile("\\d{1,10}");

    private CompanyTickers() {
    }

    /**
     * Returns one company per ticker, in the order given.
     *
     * @throws IllegalArgumentException naming every ticker SEC doesn't list
     */
    public static List<Company> resolve(EdgarClient client, URI baseUri, List<String> tickers) throws Exception {
        var found = lookup(client, baseUri, tickers);
        var unknown = tickers.stream().map(String::trim).filter(ticker -> !found.containsKey(ticker)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "SEC lists no company with the ticker %s. Check the symbol; EDGAR writes class shares with a hyphen, for example `BRK-B`."
                    .formatted(String.join(", ", unknown))
            );
        }
        return tickers.stream().map(ticker -> found.get(ticker.trim())).toList();
    }

    /**
     * Returns the company of each ticker SEC lists, keyed by the ticker as given (trimmed), in the order given; unknown
     * tickers are left out.
     */
    public static Map<String, Company> lookup(EdgarClient client, URI baseUri, List<String> tickers) throws Exception {
        var byTicker = index(client.getJson(URI.create(baseUri + PATH), TickerFile.class));
        var found = new LinkedHashMap<String, Company>();
        for (var ticker : tickers) {
            var normalized = ticker.trim().toUpperCase(Locale.ROOT);
            var company = byTicker.containsKey(normalized) ? byTicker.get(normalized) : byTicker.get(normalized.replace('.', '-'));
            if (company != null) {
                found.putIfAbsent(ticker.trim(), company);
            }
        }
        return found;
    }

    /**
     * Returns the 10-digit CIK of the company given by exactly one of {@code ticker} or {@code cik}.
     *
     * @throws IllegalArgumentException when both or neither are set, or the CIK isn't a number
     */
    public static String resolveCik(EdgarClient client, URI baseUri, String ticker, String cik) throws Exception {
        var rTicker = ticker == null || ticker.isBlank() ? null : ticker.trim();
        var rCik = cik == null || cik.isBlank() ? null : cik.trim();
        if ((rTicker == null) == (rCik == null)) {
            throw new IllegalArgumentException("Set exactly one of `ticker` or `cik` to say which company to read.");
        }
        if (rCik != null) {
            if (!CIK.matcher(rCik).matches()) {
                throw new IllegalArgumentException("`cik` must be a number of up to 10 digits, for example `50863` or `0000050863`, but was `%s`.".formatted(rCik));
            }
            return "%010d".formatted(Long.parseLong(rCik));
        }
        return resolve(client, baseUri, List.of(rTicker)).getFirst().getCik();
    }

    private static Map<String, Company> index(TickerFile file) {
        if (file.fields() == null || file.data() == null) {
            throw new IllegalStateException("SEC's ticker file %s has an unexpected format: `fields` or `data` is missing.".formatted(PATH));
        }
        var cik = column(file, "cik");
        var name = column(file, "name");
        var ticker = column(file, "ticker");
        var exchange = column(file, "exchange");

        var byTicker = new HashMap<String, Company>();
        for (var row : file.data()) {
            var symbol = (String) row.get(ticker);
            if (symbol == null) {
                continue;
            }
            byTicker.putIfAbsent(symbol.toUpperCase(Locale.ROOT), Company.builder()
                .ticker(symbol)
                .cik("%010d".formatted(((Number) row.get(cik)).longValue()))
                .name((String) row.get(name))
                .exchange((String) row.get(exchange))
                .build());
        }
        return byTicker;
    }

    private static int column(TickerFile file, String field) {
        var index = file.fields().indexOf(field);
        if (index < 0) {
            throw new IllegalStateException("SEC's ticker file %s has no `%s` column.".formatted(PATH, field));
        }
        return index;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TickerFile(List<String> fields, List<List<Object>> data) {
    }
}

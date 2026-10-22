package io.kestra.plugin.edgar.xbrl;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import io.kestra.core.serializers.JacksonMapper;

/**
 * Reads SEC's company facts JSON token by token, so a file of tens of megabytes is never held in memory: taxonomies
 * and concepts that the filter excludes are skipped without being parsed, and each fact is handed over as it is read.
 */
final class FactsReader {
    private FactsReader() {
    }

    record Filter(Set<String> taxonomies, Set<String> concepts, Set<String> units, Set<String> forms, LocalDate startDate, LocalDate endDate) {
        private static boolean keeps(Set<String> values, String value) {
            return values.isEmpty() || (value != null && values.contains(value.toUpperCase(Locale.ROOT)));
        }

        boolean keepsTaxonomy(String taxonomy) {
            return keeps(taxonomies, taxonomy);
        }

        boolean keepsConcept(String concept) {
            return keeps(concepts, concept);
        }

        boolean keepsUnit(String unit) {
            return keeps(units, unit);
        }

        boolean keeps(RawFact fact) {
            return keeps(forms, fact.form())
                && (startDate == null || (fact.end() != null && !fact.end().isBefore(startDate)))
                && (endDate == null || (fact.end() != null && !fact.end().isAfter(endDate)));
        }
    }

    @FunctionalInterface
    interface Sink {
        void accept(Fact fact) throws IOException;
    }

    /**
     * Calls {@code sink} with every fact that passes {@code filter}.
     */
    static void read(Path file, Filter filter, Sink sink) throws IOException {
        var mapper = JacksonMapper.ofJson();
        try (var parser = mapper.createParser(file.toFile())) {
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                parser.nextToken();
                if ("facts".equals(field)) {
                    readTaxonomies(parser, filter, sink);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private static void readTaxonomies(JsonParser parser, Filter filter, Sink sink) throws IOException {
        expect(parser.currentToken(), JsonToken.START_OBJECT);
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            var taxonomy = parser.currentName();
            parser.nextToken();
            if (!filter.keepsTaxonomy(taxonomy)) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var concept = parser.currentName();
                parser.nextToken();
                if (!filter.keepsConcept(concept)) {
                    parser.skipChildren();
                    continue;
                }
                readConcept(parser, taxonomy, concept, filter, sink);
            }
        }
    }

    private static void readConcept(JsonParser parser, String taxonomy, String concept, Filter filter, Sink sink) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            var field = parser.currentName();
            parser.nextToken();
            if (!"units".equals(field)) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var unit = parser.currentName();
                parser.nextToken();
                if (!filter.keepsUnit(unit)) {
                    parser.skipChildren();
                    continue;
                }
                expect(parser.currentToken(), JsonToken.START_ARRAY);
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    var fact = parser.readValueAs(RawFact.class);
                    if (filter.keeps(fact)) {
                        sink.accept(Fact.builder()
                            .taxonomy(taxonomy)
                            .concept(concept)
                            .unit(unit)
                            .value(fact.val())
                            .start(fact.start())
                            .end(fact.end())
                            .fiscalYear(fact.fy())
                            .fiscalPeriod(fact.fp())
                            .form(fact.form())
                            .filed(fact.filed())
                            .accessionNumber(fact.accn())
                            .frame(fact.frame())
                            .build());
                    }
                }
            }
        }
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) {
            throw new IllegalStateException("SEC's company facts have an unexpected format: expected %s but found %s.".formatted(expected, actual));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawFact(LocalDate start, LocalDate end, BigDecimal val, String accn, Integer fy, String fp, String form, LocalDate filed, String frame) {
    }
}

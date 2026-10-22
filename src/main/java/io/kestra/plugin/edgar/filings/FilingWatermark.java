package io.kestra.plugin.edgar.filings;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which of a company's filings are new since the last poll. The state is a window, starting at {@code since},
 * and the accession numbers already seen in it: a filing is new when it was accepted in the window and its accession
 * number hasn't been seen. Deciding by accession number rather than by time means filings accepted in the same second
 * are neither skipped nor fired twice. The window reaches back {@link #LATE_FILING_WINDOW} from the latest poll, so a
 * filing that SEC publishes late, with an earlier acceptance time, is still found.
 */
final class FilingWatermark {
    static final Duration LATE_FILING_WINDOW = Duration.ofDays(7);

    private FilingWatermark() {
    }

    record State(Instant since, Set<String> seen) {
    }

    record Result(List<Filing> newFilings, State state) {
    }

    /**
     * @param state the state saved by the previous successful poll, or {@code null} on the first one
     * @param recent the company's recent filings, already filtered on the watched forms
     * @param lookback on the first poll only, how far back to fire for; {@code null} to fire nothing
     */
    static Result evaluate(State state, List<Filing> recent, Instant now, Duration lookback) {
        var windowStart = now.minus(LATE_FILING_WINDOW);
        if (state == null) {
            var fireFrom = lookback == null ? null : now.minus(lookback);
            var since = fireFrom != null && fireFrom.isBefore(windowStart) ? fireFrom : windowStart;
            var inWindow = recent.stream().filter(filing -> !acceptedAt(filing).isBefore(since)).toList();
            var newFilings = fireFrom == null ? List.<Filing>of() : inWindow.stream().filter(filing -> !acceptedAt(filing).isBefore(fireFrom)).toList();
            return new Result(sorted(newFilings), new State(since, accessions(inWindow)));
        }

        var inWindow = recent.stream().filter(filing -> !acceptedAt(filing).isBefore(state.since())).toList();
        var newFilings = inWindow.stream().filter(filing -> !state.seen().contains(filing.getAccessionNumber())).toList();
        var since = state.since().isAfter(windowStart) ? state.since() : windowStart;
        var stillInWindow = inWindow.stream().filter(filing -> !acceptedAt(filing).isBefore(since)).toList();
        return new Result(sorted(newFilings), new State(since, accessions(stillInWindow)));
    }

    static Instant acceptedAt(Filing filing) {
        return filing.getAcceptedAt() != null ? filing.getAcceptedAt() : filing.getFilingDate().atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static Set<String> accessions(List<Filing> filings) {
        return filings.stream().map(Filing::getAccessionNumber).collect(Collectors.toCollection(HashSet::new));
    }

    private static List<Filing> sorted(List<Filing> filings) {
        return filings.stream().sorted(Comparator.comparing(FilingWatermark::acceptedAt).thenComparing(Filing::getAccessionNumber)).toList();
    }
}

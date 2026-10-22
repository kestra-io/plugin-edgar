package io.kestra.plugin.edgar.filings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The documents of one filing, read from SEC's {@code <accession>-index-headers.html}. Each document comes with the
 * type the filer declared (for example {@code EX-99.1}): exhibit file names follow no convention, so the type is the
 * only reliable way to find an exhibit.
 */
final class FilingIndex {
    private static final Pattern TYPE = tagPattern("TYPE");
    private static final Pattern SEQUENCE = tagPattern("SEQUENCE");
    private static final Pattern FILENAME = tagPattern("FILENAME");
    private static final Pattern DESCRIPTION = tagPattern("DESCRIPTION");

    private FilingIndex() {
    }

    record Entry(String type, int sequence, String fileName, String description) {
    }

    static List<Entry> parse(String indexHeaders) {
        var text = indexHeaders.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        var entries = new ArrayList<Entry>();
        var blocks = text.split("<DOCUMENT>");
        for (var i = 1; i < blocks.length; i++) {
            var type = tag(blocks[i], TYPE);
            var fileName = tag(blocks[i], FILENAME);
            if (type == null || fileName == null) {
                continue;
            }
            var sequence = tag(blocks[i], SEQUENCE);
            entries.add(new Entry(type, sequence == null ? entries.size() + 1 : Integer.parseInt(sequence), fileName, tag(blocks[i], DESCRIPTION)));
        }
        return entries;
    }

    /**
     * Turns type patterns such as {@code EX-99*} into one case-insensitive matcher; {@code *} matches any characters.
     */
    static Pattern typePattern(List<String> patterns) {
        var alternatives = patterns.stream()
            .map(pattern -> pattern.trim().toUpperCase(Locale.ROOT))
            .filter(pattern -> !pattern.isEmpty())
            .map(pattern -> String.join(".*", Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote).toList()))
            .toList();
        return Pattern.compile(String.join("|", alternatives), Pattern.CASE_INSENSITIVE);
    }

    private static Pattern tagPattern(String name) {
        return Pattern.compile("<" + name + ">([^\\r\\n<]*)");
    }

    private static String tag(String block, Pattern pattern) {
        var matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        var value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }
}

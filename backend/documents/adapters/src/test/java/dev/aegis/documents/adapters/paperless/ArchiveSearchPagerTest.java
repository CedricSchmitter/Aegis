package dev.aegis.documents.adapters.paperless;

import static org.assertj.core.api.Assertions.assertThat;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.SearchLimitation;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ArchiveSearchPagerTest {
    private static final String QUERY = "text=clause&fields=id&page_size=200";
    private static final String URL = "https://archive.test/api/documents/?" + QUERY;
    private final JsonMapper json = JsonMapper.builder().build();

    private ArchiveSearchPager.Page page(int number, String results, String next, String count) {
        return new ArchiveSearchPager.Page(json.readTree("{\"results\":[" + results + "],\"count\":" + count
                + ",\"next\":" + (next == null ? "null" : "\"" + next + "\"") + "}"),
                URI.create(URL + (number == 1 ? "" : "&page=" + number)));
    }

    private static String ids(int start, int end) {
        return IntStream.rangeClosed(start, end).mapToObj(id -> "{\"id\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 1000})
    void traversesHouseholdScaleSyntheticResults(int total) {
        AtomicInteger calls = new AtomicInteger();
        var result = new ArchiveSearchPager().search((number, remaining) -> {
            calls.incrementAndGet();
            int end = Math.min(number * 200, total);
            return page(number, ids((number - 1) * 200 + 1, end),
                    end == total ? null : URL + "&page=" + (number + 1), Integer.toString(total));
        });
        assertThat(result.state()).isEqualTo(ContentSearchState.READY);
        assertThat(result.ids()).hasSize(total).contains(201, total);
        assertThat(result.archiveTotal()).isEqualTo((long) total);
        assertThat(calls.get()).isEqualTo((total + 199) / 200);
    }

    @Test
    void duplicateIdsAreCollectedOnce() {
        var result = new ArchiveSearchPager().search((number, remaining) -> number == 1
                ? page(1, ids(1, 2), URL + "&page=2", "3") : page(2, ids(2, 3), null, "3"));
        assertThat(result.state()).isEqualTo(ContentSearchState.READY);
        assertThat(result.ids()).containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test/api/documents/?text=clause&fields=id&page_size=200&page=2",
            "//evil.test/api/documents/?text=clause&fields=id&page_size=200&page=2",
            "http://archive.test/api/documents/?text=clause&fields=id&page_size=200&page=2",
            "https://archive.test/api/trash/?text=clause&fields=id&page_size=200&page=2",
            "https://user:pass@archive.test/api/documents/?text=clause&fields=id&page_size=200&page=2"})
    void unsafeLinksAreNeverFetched(String next) {
        AtomicInteger calls = new AtomicInteger();
        var result = new ArchiveSearchPager().search((number, remaining) -> {
            calls.incrementAndGet(); return page(1, ids(1, 1), next, "3");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.limitation()).isEqualTo(SearchLimitation.UNSAFE_PAGINATION);
        assertThat(result.state()).isEqualTo(ContentSearchState.LIMITED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"&page=1", "&page=3", "&page=2&page=2", "&page=2&extra=x"})
    void loopsSkippedPagesAndChangedParametersStopTraversal(String suffix) {
        AtomicInteger calls = new AtomicInteger();
        var result = new ArchiveSearchPager().search((number, remaining) -> {
            calls.incrementAndGet(); return page(1, ids(1, 1), URL + suffix, "3");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.limitation()).isEqualTo(SearchLimitation.INVALID_PAGINATION);
    }

    @Test
    void requestAndHitBoundsRetainAccumulatedResults() {
        var request = new ArchiveSearchPager(1, 20, Duration.ofSeconds(1), () -> 0)
                .search((number, remaining) -> page(1, ids(1, 2), URL + "&page=2", "4"));
        var hit = new ArchiveSearchPager(4, 3, Duration.ofSeconds(1), () -> 0)
                .search((number, remaining) -> page(number, ids(1, 4), null, "4"));
        assertThat(request.ids()).containsExactly(1, 2);
        assertThat(request.limitation()).isEqualTo(SearchLimitation.REQUEST_LIMIT);
        assertThat(hit.ids()).containsExactly(1, 2, 3);
        assertThat(hit.limitation()).isEqualTo(SearchLimitation.HIT_LIMIT);
    }

    @Test
    void elapsedTimeAndTransportDeadlineCannotBecomeComplete() {
        AtomicLong now = new AtomicLong();
        var result = new ArchiveSearchPager(10, 100, Duration.ofNanos(100), now::get)
                .search((number, remaining) -> {
                    assertThat(remaining).isEqualTo(Duration.ofNanos(100));
                    now.set(101); return page(1, ids(1, 2), null, "2");
                });
        assertThat(result.ids()).containsExactly(1, 2);
        assertThat(result.limitation()).isEqualTo(SearchLimitation.TIME_LIMIT);
        var timeout = new ArchiveSearchPager().search((number, remaining) -> {
            throw new java.util.concurrent.TimeoutException();
        });
        assertThat(timeout.state()).isEqualTo(ContentSearchState.UNAVAILABLE);
        assertThat(timeout.limitation()).isEqualTo(SearchLimitation.TIME_LIMIT);
    }

    @Test
    void missingCountCanCompleteButMissingOrInconsistentPaginationCannot() {
        assertThat(new ArchiveSearchPager().search((number, remaining) -> page(1, ids(1, 1), null, "null"))
                .state()).isEqualTo(ContentSearchState.READY);
        var earlyEnd = new ArchiveSearchPager().search((number, remaining) -> page(1, ids(1, 1), null, "5"));
        assertThat(earlyEnd.limitation()).isEqualTo(SearchLimitation.INVALID_PAGINATION);
        var changed = new ArchiveSearchPager().search((number, remaining) -> number == 1
                ? page(1, ids(1, 1), URL + "&page=2", "3") : page(2, ids(2, 3), null, "4"));
        assertThat(changed.limitation()).isEqualTo(SearchLimitation.INVALID_PAGINATION);
        assertThat(changed.archiveTotal()).isNull();
        var missingNext = new ArchiveSearchPager().search((number, remaining) -> new ArchiveSearchPager.Page(
                json.readTree("{\"results\":[{\"id\":1}]}"), URI.create(URL)));
        assertThat(missingNext.state()).isEqualTo(ContentSearchState.LIMITED);
    }
}

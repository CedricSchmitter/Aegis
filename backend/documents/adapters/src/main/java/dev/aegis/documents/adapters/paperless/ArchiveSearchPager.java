package dev.aegis.documents.adapters.paperless;

import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.SearchLimitation;
import dev.aegis.documents.application.ports.ArchiveSearchResult;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;

/** Bounded traversal. Response URLs are validated, never fetched; the caller rebuilds every request. */
final class ArchiveSearchPager {
    static final int PAGE_SIZE = 200;
    static final int MAX_REQUESTS = 10;
    static final int MAX_HITS = 2_000;
    static final Duration MAX_TIME = Duration.ofSeconds(10);

    record Page(JsonNode body, URI request) { }
    interface Fetch { Page get(int page, Duration remaining) throws java.util.concurrent.TimeoutException; }

    private final int requests;
    private final int hits;
    private final Duration time;
    private final LongSupplier nanos;

    ArchiveSearchPager() { this(MAX_REQUESTS, MAX_HITS, MAX_TIME, System::nanoTime); }

    ArchiveSearchPager(int requests, int hits, Duration time, LongSupplier nanos) {
        this.requests = requests;
        this.hits = hits;
        this.time = time;
        this.nanos = nanos;
    }

    ArchiveSearchResult search(Fetch fetch) {
        long started = nanos.getAsLong();
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        Long total = null;
        int received = 0;
        for (int number = 1; number <= requests; number++) {
            long remaining = time.toNanos() - (nanos.getAsLong() - started);
            if (remaining <= 0) return limited(ids, received, total, SearchLimitation.TIME_LIMIT);
            Page response;
            try {
                response = fetch.get(number, Duration.ofNanos(remaining));
            } catch (java.util.concurrent.TimeoutException timeout) {
                return limited(ids, received, total, SearchLimitation.TIME_LIMIT);
            } catch (RuntimeException failure) {
                return limited(ids, received, total, SearchLimitation.ARCHIVE_FAILURE);
            }
            received++;
            JsonNode body = response.body();
            if (body == null || !body.path("results").isArray() || body.path("results").size() > PAGE_SIZE)
                return limited(ids, received, total, SearchLimitation.INVALID_PAGINATION);
            int previousHits = ids.size();
            for (JsonNode node : body.path("results")) {
                JsonNode id = node.path("id");
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() <= 0)
                    return limited(ids, received, total, SearchLimitation.INVALID_PAGINATION);
                if (!ids.contains(id.asInt()) && ids.size() == hits)
                    return limited(ids, received, total, SearchLimitation.HIT_LIMIT);
                ids.add(id.asInt());
            }
            if (nanos.getAsLong() - started >= time.toNanos())
                return limited(ids, received, total, SearchLimitation.TIME_LIMIT);
            JsonNode count = body.path("count");
            if (!count.isMissingNode() && !count.isNull()) {
                if (!count.isIntegralNumber() || !count.canConvertToLong() || count.asLong() < ids.size()
                        || (total != null && total != count.asLong()))
                    return limited(ids, received, null, SearchLimitation.INVALID_PAGINATION);
                total = count.asLong();
            } else if (total != null) {
                return limited(ids, received, null, SearchLimitation.INVALID_PAGINATION);
            }
            JsonNode next = body.path("next");
            if (next.isNull()) {
                if (total != null && total != ids.size())
                    return limited(ids, received, total, SearchLimitation.INVALID_PAGINATION);
                return new ArchiveSearchResult(List.copyOf(ids), ContentSearchState.READY, null, total);
            }
            if (!next.isTextual() || next.asText().isBlank() || body.path("results").isEmpty()
                    || (total != null && total <= ids.size()) || ids.size() == previousHits)
                return limited(ids, received, total, SearchLimitation.INVALID_PAGINATION);
            SearchLimitation invalid = validateNext(response.request(), next.asText(), number + 1);
            if (invalid != null) return limited(ids, received, total, invalid);
            if (ids.size() == hits) return limited(ids, received, total, SearchLimitation.HIT_LIMIT);
        }
        return limited(ids, received, total, SearchLimitation.REQUEST_LIMIT);
    }

    private static ArchiveSearchResult limited(LinkedHashSet<Integer> ids, int received, Long total,
            SearchLimitation reason) {
        return new ArchiveSearchResult(List.copyOf(ids), received == 0 ? ContentSearchState.UNAVAILABLE
                : ContentSearchState.LIMITED, reason, total);
    }

    private static SearchLimitation validateNext(URI request, String raw, int expectedPage) {
        try {
            URI next = raw.startsWith("?")
                    ? URI.create(request.getScheme() + "://" + request.getRawAuthority() + request.getRawPath() + raw)
                    : request.resolve(raw);
            if (!request.getScheme().equalsIgnoreCase(next.getScheme())
                    || !request.getHost().equalsIgnoreCase(next.getHost()) || request.getPort() != next.getPort()
                    || !request.getRawPath().equals(next.getRawPath()) || next.getRawUserInfo() != null
                    || next.getRawFragment() != null) return SearchLimitation.UNSAFE_PAGINATION;
            Map<String, String> expected = parameters(request);
            expected.put("page", Integer.toString(expectedPage));
            return expected.equals(parameters(next)) ? null : SearchLimitation.INVALID_PAGINATION;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return SearchLimitation.INVALID_PAGINATION;
        }
    }

    private static Map<String, String> parameters(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] parts = parameter.split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Missing parameter value");
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate parameter");
        }
        return values;
    }
}

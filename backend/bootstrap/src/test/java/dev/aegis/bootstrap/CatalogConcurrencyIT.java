package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two members moving folders into each other at the same time. The exclusive catalog lock serializes structural
 * changes, so exactly one move wins and the stored tree can still be loaded (no cycle).
 */
class CatalogConcurrencyIT extends PostgresIT {
    @Autowired
    CategoryCommands categories;
    @Autowired
    CatalogQueries queries;
    @Autowired
    MemberCredentialsQuery members;

    @Test
    void concurrentOppositeMovesCannotFormACycle() throws Exception {
        Actor lisa = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        Actor cedric = new Actor(members.findCredentials(BEN).orElseThrow().id());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        CategoryView first = categories.create(lisa, new CreateCategory(null, null, "Concurrent A " + suffix));
        CategoryView second = categories.create(lisa, new CreateCategory(null, null, "Concurrent B " + suffix));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> moves = List.of(
                    attempt(() -> categories.move(lisa, first.id(), second.id(), first.version())),
                    attempt(() -> categories.move(cedric, second.id(), first.id(), second.version())));
            List<Future<String>> results = pool.invokeAll(moves);
            List<String> outcomes = List.of(results.get(0).get(), results.get(1).get());

            assertThat(outcomes).as(outcomes.toString()).containsOnlyOnce("moved");
            assertThat(outcomes).anySatisfy(outcome -> assertThat(outcome).startsWith("rejected"));
        } finally {
            pool.shutdownNow();
        }

        var tree = queries.categories(lisa).categories();
        assertThat(tree).extracting(CategoryView::id).contains(first.id(), second.id());
        long nested = tree.stream().filter(view -> view.id().equals(first.id()) && second.id().equals(view.parentId())
                || view.id().equals(second.id()) && first.id().equals(view.parentId())).count();
        assertThat(nested).as("exactly one of the two folders moved").isEqualTo(1);
    }

    private static Callable<String> attempt(Runnable move) {
        return () -> {
            try {
                move.run();
                return "moved";
            } catch (RuntimeException rejected) {
                return "rejected: " + rejected.getClass().getSimpleName();
            }
        };
    }
}

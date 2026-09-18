package dev.aegis.assistant.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.RunStep;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.StepKind;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.bootstrap.AegisApplication;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The PostgreSQL run store: round trip, optimistic versions, the single active run per key and cost sums. */
@SpringBootTest(classes = AegisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiRunStoreIT extends PostgresIT {
    /** In the past, so runs started here never count as occupying a slot for the other integration tests. */
    static final Instant AT = Instant.parse("2020-01-15T10:00:00Z").truncatedTo(ChronoUnit.MICROS);

    @Autowired
    AiRunRepository runs;
    @Autowired
    JdbcEffectLedger ledger;
    @Autowired
    PlatformTransactionManager transactions;

    private static AiRun newRun(String key) {
        return AiRun.request(RunId.random(), Workload.DOCUMENT_CLASSIFICATION, key, null, MemberId.random(), AT,
                new ModelSelection("anthropic", "claude-haiku-4-5"),
                new RunBudget(Duration.ofMinutes(2), Money.of("0.05", "USD"), 4, 6, 2048, 3),
                List.of(new SourceRef("document", UUID.randomUUID().toString(), 7)),
                Map.of("reason", "store test"));
    }

    @Test
    void runRoundTripsWithSourcesParametersUsageAndSteps() {
        AiRun run = newRun("store:" + UUID.randomUUID());
        runs.save(run);
        AiRun started = runs.findById(run.id()).orElseThrow();
        started.start(AT.plusSeconds(1));
        started.recordModelCall(1, "claude-haiku-4-5-20251001", "stop.end", new TokenUsage(1200, 80, 5, 0),
                Money.of("0.0016", "USD"), AT.plusSeconds(2));
        started.fail(1, FailureCategory.TIMEOUT, "provider.timeout", "slow", Duration.ofSeconds(90),
                AT.plusSeconds(3));
        runs.save(started);

        AiRun loaded = runs.findById(run.id()).orElseThrow();

        assertThat(loaded.state()).isEqualTo(RunState.WAITING_RETRY);
        assertThat(loaded.nextAttemptAt()).contains(AT.plusSeconds(93));
        assertThat(loaded.sources()).isEqualTo(run.sources());
        assertThat(loaded.parameters()).isEqualTo(Map.of("reason", "store test"));
        assertThat(loaded.tokens()).isEqualTo(new TokenUsage(1200, 80, 5, 0));
        assertThat(loaded.cost()).isEqualTo(Money.of("0.0016", "USD"));
        assertThat(loaded.failure()).get().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo(FailureCategory.TIMEOUT);
            assertThat(failure.code()).isEqualTo("provider.timeout");
        });
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(runs.steps(run.id())).extracting(RunStep::kind).containsExactly(StepKind.ATTEMPT_STARTED,
                StepKind.MODEL_CALL, StepKind.ATTEMPT_FAILED);
        assertThat(runs.costSince(AT, Currency.getInstance("USD")).amount())
                .isGreaterThanOrEqualTo(Money.of("0.0016", "USD").amount());
        assertThat(runs.findActiveByKey(run.idempotencyKey())).get().extracting(AiRun::id).isEqualTo(run.id());
    }

    @Test
    void staleCopyCannotOverwriteANewerRun() {
        AiRun run = newRun("store:" + UUID.randomUUID());
        runs.save(run);
        AiRun first = runs.findById(run.id()).orElseThrow();
        AiRun second = runs.findById(run.id()).orElseThrow();
        first.start(AT);
        runs.save(first);

        second.requestCancel(MemberId.random(), AT);

        assertThatThrownBy(() -> runs.save(second)).isInstanceOf(StaleVersionException.class);
        assertThat(runs.findById(run.id()).orElseThrow().state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void lockedLoadSerializesAConcurrentCancellationInsteadOfFailingIt() throws Exception {
        AiRun run = newRun("store:" + UUID.randomUUID());
        runs.save(run);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> step = pool.submit(() -> tx.executeWithoutResult(status -> {
                AiRun executing = runs.findByIdForUpdate(run.id()).orElseThrow();
                locked.countDown();
                executing.start(AT);
                sleep();
                runs.save(executing);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            tx.executeWithoutResult(status -> {
                AiRun cancelling = runs.findByIdForUpdate(run.id()).orElseThrow();
                assertThat(cancelling.state()).as("waited for the execution step").isEqualTo(RunState.RUNNING);
                cancelling.requestCancel(MemberId.random(), AT);
                runs.save(cancelling);
            });
            step.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        AiRun stored = runs.findById(run.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(RunState.RUNNING);
        assertThat(stored.cancelRequested()).isTrue();
        assertThat(stored.version()).isEqualTo(2);
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void onlyOneActiveRunPerKeyCanExist() {
        String key = "store:" + UUID.randomUUID();
        runs.save(newRun(key));

        assertThatThrownBy(() -> runs.save(newRun(key))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void effectKeyCanBeClaimedOnce() {
        AiRun run = newRun("store:" + UUID.randomUUID());
        runs.save(run);
        String effect = "effect:" + UUID.randomUUID();

        assertThat(ledger.claim(effect, run.id(), AT)).isTrue();
        assertThat(ledger.claim(effect, run.id(), AT)).isFalse();
    }
}

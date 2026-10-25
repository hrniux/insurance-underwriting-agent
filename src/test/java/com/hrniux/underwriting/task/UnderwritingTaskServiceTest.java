package com.hrniux.underwriting.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import com.hrniux.underwriting.agent.EvaluationRequest;
import com.hrniux.underwriting.agent.EvaluationSubmissionResult;
import com.hrniux.underwriting.agent.EvaluationSubmissionService;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.model.ModelUnavailableException;
import com.hrniux.underwriting.shared.config.UnderwritingTaskProperties;
import com.hrniux.underwriting.shared.error.ConflictException;
import com.hrniux.underwriting.shared.error.ServiceCapacityException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class UnderwritingTaskServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC);
    private static final UnderwritingTaskProperties PROPERTIES =
            new UnderwritingTaskProperties(1, 1, 1, 10, Duration.ofHours(1));

    private EvaluationSubmissionService submissions;
    private InMemoryUnderwritingTaskRepository repository;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        submissions = mock(EvaluationSubmissionService.class);
        repository = new InMemoryUnderwritingTaskRepository();
        metrics = new SimpleMeterRegistry();
    }

    @Test
    void executesOnceAndReplaysTheSameTaskForTheSameIdempotencyKey() {
        EvaluationRequest request = new EvaluationRequest(null, "P-1001", "异步核保");
        UnderwritingEvaluation evaluation = evaluation("EVAL-ASYNC-1");
        when(submissions.submit(request, null)).thenReturn(new EvaluationSubmissionResult(evaluation, false));
        UnderwritingTaskService service = service(Runnable::run);

        UnderwritingTaskSubmissionResult first = service.submit(request, "task-key-1");
        UnderwritingTaskSubmissionResult replay = service.submit(request, "task-key-1");

        assertThat(first.replayed()).isFalse();
        assertThat(first.task().status()).isEqualTo(UnderwritingTaskStatus.SUCCEEDED);
        assertThat(first.task().evaluationId()).isEqualTo("EVAL-ASYNC-1");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.task().id()).isEqualTo(first.task().id());
        verify(submissions, times(1)).submit(request, null);
        assertThat(metrics.get("underwriting.task.submissions").tag("outcome", "replayed").counter().count())
                .isEqualTo(1);
    }

    @Test
    void rejectsChangedPayloadForAnExistingTaskKey() {
        EvaluationRequest original = new EvaluationRequest(null, "P-1001", "原始问题");
        UnderwritingEvaluation evaluation = evaluation("EVAL-ASYNC-2");
        when(submissions.submit(original, null))
                .thenReturn(new EvaluationSubmissionResult(evaluation, false));
        UnderwritingTaskService service = service(Runnable::run);
        service.submit(original, "task-key-2");

        assertThatThrownBy(() -> service.submit(
                new EvaluationRequest(null, "P-2001", "变更后的问题"), "task-key-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different task request");
    }

    @Test
    void convertsModelFailureToASafeTerminalSnapshot() {
        EvaluationRequest request = new EvaluationRequest(null, "P-1001", "模型失败");
        when(submissions.submit(request, null)).thenThrow(
                new ModelUnavailableException("MODEL_TIMEOUT", "Model request timed out", 3, null));
        UnderwritingTaskService service = service(Runnable::run);

        UnderwritingTask task = service.submit(request, null).task();

        assertThat(task.status()).isEqualTo(UnderwritingTaskStatus.FAILED);
        assertThat(task.failure().errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(task.failure().message()).isEqualTo("Model request timed out");
        assertThat(task.evaluationId()).isNull();
    }

    @Test
    void rollsBackARejectedQueueSubmissionAndReturnsCapacityError() {
        Executor rejecting = ignored -> {
            throw new TaskRejectedException("queue full");
        };
        UnderwritingTaskService service = service(rejecting);

        assertThatThrownBy(() -> service.submit(
                new EvaluationRequest(null, "P-1001", "队列已满"), "task-key-rejected"))
                .isInstanceOf(ServiceCapacityException.class)
                .hasMessageContaining("at capacity");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void neverAcknowledgesAReplayBeforeItsQueueAdmissionSucceeds() throws Exception {
        CountDownLatch admissionStarted = new CountDownLatch(1);
        CountDownLatch rejectAdmission = new CountDownLatch(1);
        Executor rejecting = ignored -> {
            admissionStarted.countDown();
            try {
                if (!rejectAdmission.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to reject admission");
                }
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            throw new RejectedExecutionException("queue full");
        };
        UnderwritingTaskService service = service(rejecting);
        EvaluationRequest request = new EvaluationRequest(null, "P-1001", "concurrent rejected submission");
        CountDownLatch replayStarted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> service.submit(request, "rejected-race"));
            assertThat(admissionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var replay = pool.submit(() -> {
                replayStarted.countDown();
                return service.submit(request, "rejected-race");
            });
            try {
                assertThat(replayStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> replay.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }
            finally {
                rejectAdmission.countDown();
            }

            assertThatThrownBy(() -> owner.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceCapacityException.class);
            assertThatThrownBy(() -> replay.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceCapacityException.class);
        }
        finally {
            rejectAdmission.countDown();
        }
        assertThat(service.list()).isEmpty();
    }

    @Test
    void replaysAPendingTaskAndEnforcesTheRetainedTaskBound() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        Executor holding = captured::set;
        UnderwritingTaskProperties oneTask =
                new UnderwritingTaskProperties(1, 1, 1, 1, Duration.ofHours(1));
        UnderwritingTaskService service = service(holding, oneTask);
        EvaluationRequest firstRequest = new EvaluationRequest(null, "P-1001", "排队任务");

        UnderwritingTaskSubmissionResult first = service.submit(firstRequest, "pending-key");
        UnderwritingTaskSubmissionResult replay = service.submit(firstRequest, "pending-key");

        assertThat(first.task().status()).isEqualTo(UnderwritingTaskStatus.PENDING);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.task().id()).isEqualTo(first.task().id());
        assertThat(captured.get()).isNotNull();

        assertThatThrownBy(() -> service.submit(
                new EvaluationRequest(null, "P-2001", "第二个任务"), null))
                .isInstanceOfSatisfying(ServiceCapacityException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("TASK_CAPACITY_EXCEEDED"));
    }

    private UnderwritingTaskService service(Executor executor) {
        return service(executor, PROPERTIES);
    }

    private UnderwritingTaskService service(Executor executor, UnderwritingTaskProperties properties) {
        return new UnderwritingTaskService(repository, submissions, properties, executor, metrics, CLOCK);
    }

    private UnderwritingEvaluation evaluation(String id) {
        UnderwritingEvaluation evaluation = mock(UnderwritingEvaluation.class);
        when(evaluation.id()).thenReturn(id);
        return evaluation;
    }
}

package com.hrniux.underwriting.task;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hrniux.underwriting.agent.EvaluationIdempotency;
import com.hrniux.underwriting.agent.EvaluationRequest;
import com.hrniux.underwriting.agent.EvaluationSubmissionResult;
import com.hrniux.underwriting.agent.EvaluationSubmissionService;
import com.hrniux.underwriting.model.ModelUnavailableException;
import com.hrniux.underwriting.shared.config.UnderwritingTaskProperties;
import com.hrniux.underwriting.shared.error.ConflictException;
import com.hrniux.underwriting.shared.error.DomainException;
import com.hrniux.underwriting.shared.error.ResourceNotFoundException;
import com.hrniux.underwriting.shared.error.ServiceCapacityException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class UnderwritingTaskService {

    private static final String SUBMISSION_METRIC = "underwriting.task.submissions";
    private static final String TRANSITION_METRIC = "underwriting.task.transitions";
    private static final String DURATION_METRIC = "underwriting.task.duration";

    private final UnderwritingTaskRepository tasks;
    private final EvaluationSubmissionService submissions;
    private final UnderwritingTaskProperties properties;
    private final Executor executor;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Map<String, IdempotentTask> idempotency = new HashMap<>();

    @Autowired
    public UnderwritingTaskService(
            UnderwritingTaskRepository tasks,
            EvaluationSubmissionService submissions,
            UnderwritingTaskProperties properties,
            @Qualifier(UnderwritingTaskConfiguration.EXECUTOR_BEAN) Executor executor,
            MeterRegistry metrics) {
        this(tasks, submissions, properties, executor, metrics, Clock.systemUTC());
    }

    UnderwritingTaskService(
            UnderwritingTaskRepository tasks,
            EvaluationSubmissionService submissions,
            UnderwritingTaskProperties properties,
            Executor executor,
            MeterRegistry metrics,
            Clock clock) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.properties = properties;
        this.executor = executor;
        this.metrics = metrics;
        this.clock = clock;
    }

    public UnderwritingTaskSubmissionResult submit(EvaluationRequest request, String rawIdempotencyKey) {
        SubmissionOutcome outcome = SubmissionOutcome.FAILED;
        try {
            Objects.requireNonNull(request, "request must not be null");
            String key = EvaluationIdempotency.normalizeKey(rawIdempotencyKey);
            // A replay must not acknowledge a reservation before executor admission succeeds.
            synchronized (idempotency) {
                TaskClaim claim = claim(request, key);
                UnderwritingTask task = claim.task();
                if (claim.replayed()) {
                    outcome = SubmissionOutcome.REPLAYED;
                    return new UnderwritingTaskSubmissionResult(task, true);
                }

                try {
                    String taskId = task.id();
                    executor.execute(() -> run(taskId));
                }
                catch (RejectedExecutionException rejected) {
                    rollback(task.id(), key);
                    outcome = SubmissionOutcome.REJECTED;
                    throw new ServiceCapacityException(
                            "TASK_EXECUTOR_SATURATED",
                            "Underwriting task executor is at capacity; retry later");
                }

                outcome = SubmissionOutcome.ACCEPTED;
                return new UnderwritingTaskSubmissionResult(tasks.findById(task.id()).orElse(task), false);
            }
        }
        catch (ConflictException conflict) {
            outcome = SubmissionOutcome.CONFLICT;
            throw conflict;
        }
        catch (ServiceCapacityException capacity) {
            outcome = SubmissionOutcome.REJECTED;
            throw capacity;
        }
        finally {
            metrics.counter(SUBMISSION_METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
        }
    }

    public UnderwritingTask get(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("UNDERWRITING_TASK_NOT_FOUND", taskId));
    }

    public List<UnderwritingTask> list() {
        return tasks.findAll();
    }

    private TaskClaim claim(EvaluationRequest request, String key) {
        synchronized (idempotency) {
            Instant now = clock.instant();
            removeExpired(now);
            String fingerprint = EvaluationIdempotency.fingerprint(request);
            if (key != null) {
                IdempotentTask existing = idempotency.get(key);
                if (existing != null) {
                    if (!existing.fingerprint().equals(fingerprint)) {
                        throw new ConflictException(
                                "IDEMPOTENCY_KEY_CONFLICT",
                                "Idempotency-Key has already been used with a different task request");
                    }
                    UnderwritingTask existingTask = tasks.findById(existing.taskId()).orElse(null);
                    if (existingTask != null) {
                        return new TaskClaim(existingTask, true);
                    }
                    idempotency.remove(key);
                }
            }

            if (tasks.findAll().size() >= properties.maxEntries()) {
                throw new ServiceCapacityException(
                        "TASK_CAPACITY_EXCEEDED",
                        "Too many underwriting tasks are retained; retry after completed tasks expire");
            }

            UnderwritingTask created = UnderwritingTask.pending(
                    "TASK-" + UUID.randomUUID().toString().replace("-", ""), request, now);
            tasks.save(created);
            if (key != null) {
                idempotency.put(key, new IdempotentTask(fingerprint, created.id()));
            }
            return new TaskClaim(created, false);
        }
    }

    private void run(String taskId) {
        Timer.Sample timer = Timer.start(metrics);
        TaskOutcome outcome = TaskOutcome.FAILED;
        UnderwritingTask running = update(taskId, task -> task.start(clock.instant()));
        metrics.counter(TRANSITION_METRIC, "status", "running").increment();
        try {
            EvaluationSubmissionResult result = submissions.submit(running.request(), null);
            update(taskId, task -> task.succeed(result.evaluation().id(), clock.instant()));
            metrics.counter(TRANSITION_METRIC, "status", "succeeded").increment();
            outcome = TaskOutcome.SUCCEEDED;
        }
        catch (Throwable error) {
            update(taskId, task -> task.fail(failure(error), clock.instant()));
            metrics.counter(TRANSITION_METRIC, "status", "failed").increment();
            if (error instanceof Error fatal) {
                throw fatal;
            }
        }
        finally {
            timer.stop(metrics.timer(DURATION_METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT)));
        }
    }

    private UnderwritingTask update(
            String taskId,
            UnaryOperator<UnderwritingTask> transition) {
        UnderwritingTask current = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task disappeared while executing: " + taskId));
        return tasks.save(transition.apply(current));
    }

    private UnderwritingTaskFailure failure(Throwable error) {
        if (error instanceof DomainException domain) {
            return new UnderwritingTaskFailure(domain.errorCode(), safeMessage(domain.getMessage()));
        }
        if (error instanceof ModelUnavailableException model) {
            return new UnderwritingTaskFailure(model.errorCode(), safeMessage(model.getMessage()));
        }
        return new UnderwritingTaskFailure("TASK_EXECUTION_FAILED", "Unexpected task execution failure");
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Task execution failed";
        }
        String normalized = message.trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private void removeExpired(Instant now) {
        tasks.findAll().stream()
                .filter(UnderwritingTask::terminal)
                .filter(task -> !task.completedAt().plus(properties.retention()).isAfter(now))
                .forEach(task -> tasks.deleteById(task.id()));
        idempotency.entrySet().removeIf(entry -> tasks.findById(entry.getValue().taskId()).isEmpty());
    }

    private void rollback(String taskId, String key) {
        synchronized (idempotency) {
            tasks.deleteById(taskId);
            if (key != null) {
                idempotency.computeIfPresent(key,
                        (ignored, value) -> value.taskId().equals(taskId) ? null : value);
            }
        }
    }

    private enum SubmissionOutcome {
        ACCEPTED,
        REPLAYED,
        CONFLICT,
        REJECTED,
        FAILED
    }

    private enum TaskOutcome {
        SUCCEEDED,
        FAILED
    }

    private record TaskClaim(UnderwritingTask task, boolean replayed) {
    }

    private record IdempotentTask(String fingerprint, String taskId) {
    }
}

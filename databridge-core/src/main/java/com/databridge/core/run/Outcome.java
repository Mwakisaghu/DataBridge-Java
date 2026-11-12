package com.databridge.core.run;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The result of a step, built so a crash can never be read as a pass (fail closed).
 * {@link #isSuccess()} is true only for SUCCEEDED; any exception becomes ERROR.
 */
public final class Outcome<T> {

    private final RunStatus status;
    private final T value;
    private final String step;
    private final String error;

    private Outcome(RunStatus status, T value, String step, String error) {
        this.status = status;
        this.value = value;
        this.step = step;
        this.error = error;
    }

    /** Runs {@code work}; returns SUCCEEDED with its value, or ERROR if it throws. */
    public static <T> Outcome<T> run(String step, Callable<T> work) {
        try {
            T v = work.call();
            return new Outcome<>(RunStatus.SUCCEEDED, v, step, null);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            return error(step, t);
        }
    }

    public static <T> Outcome<T> failed(String step, T value, String reason) {
        return new Outcome<>(RunStatus.FAILED, value, step, reason);
    }

    public static <T> Outcome<T> error(String step, Throwable t) {
        String msg = t.getMessage() == null ? "" : ": " + t.getMessage();
        return new Outcome<>(RunStatus.ERROR, null, step, t.getClass().getSimpleName() + msg);
    }

    public RunStatus status() { return status; }
    public String step() { return step; }
    public boolean isSuccess() { return status == RunStatus.SUCCEEDED; }
    public Optional<String> error() { return Optional.ofNullable(error); }

    /** The value, available for SUCCEEDED and FAILED; throws for ERROR. */
    public T value() {
        if (status == RunStatus.ERROR) throw new IllegalStateException(step + " did not complete: " + error);
        return value;
    }
}

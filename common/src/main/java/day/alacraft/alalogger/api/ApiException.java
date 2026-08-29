package day.alacraft.alalogger.api;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The only exception this client completes a future with.
 *
 * <p>Unchecked because every call is asynchronous: a checked exception cannot
 * cross a {@link java.util.concurrent.CompletableFuture} boundary without being
 * wrapped anyway, and forcing {@code try/catch} into lambdas buys nothing.
 *
 * <p>What the caller should read is {@link #error()} — the message on the
 * exception is for a log file, the {@link ApiErrorCode} is for deciding what to
 * do and what to say.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ApiError error;

    public ApiException(ApiError error) {
        this(error, null);
    }

    public ApiException(ApiError error, Throwable cause) {
        super(error.code().wireName() + ": " + error.message(), cause);
        this.error = error;
    }

    public ApiError error() {
        return error;
    }

    /** Shorthand for {@code error().code()} — the value nearly every caller switches on. */
    public ApiErrorCode code() {
        return error.code();
    }

    /**
     * The {@code ApiException} behind a failed future.
     *
     * <p>{@code CompletableFuture} hands failures back wrapped in {@link
     * CompletionException} or {@link ExecutionException}, so the obvious
     * {@code catch (ApiException e)} around {@code join()} never fires and every
     * call site would otherwise unwrap by hand. Anything that is not one of ours
     * — a bug in a callback, say — comes back as {@link ApiErrorCode#INTERNAL}
     * with the original attached as the cause, so nothing is swallowed.
     */
    public static ApiException of(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ApiException api) {
                return api;
            }
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                current = current.getCause();
                continue;
            }
            break;
        }

        Throwable cause = current == null ? throwable : current;
        String message = cause == null ? "Unknown failure." : cause.toString();

        return new ApiException(ApiError.client(ApiErrorCode.INTERNAL, message), cause);
    }
}

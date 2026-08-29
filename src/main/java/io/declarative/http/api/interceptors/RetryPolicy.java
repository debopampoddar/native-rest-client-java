package io.declarative.http.api.interceptors;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for {@link RetryOnServerErrorInterceptor}.
 *
 * <p>The policy intentionally does not make non-idempotent methods replayable;
 * the interceptor remains limited to {@code GET} and {@code HEAD}. When a server
 * supplies {@code Retry-After}, that delay is used but capped at
 * {@link #maxBackoff()} to prevent an unbounded blocked call.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double jitterFactor;

    /**
     * Creates validated policy state.
     *
     * @param maxAttempts total attempts including the initial request
     * @param initialBackoff first retry delay
     * @param maxBackoff maximum retry delay
     * @param jitterFactor random adjustment fraction in the inclusive range [0, 1]
     */
    private RetryPolicy(int maxAttempts, Duration initialBackoff,
                        Duration maxBackoff, double jitterFactor) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.jitterFactor = jitterFactor;
    }

    /**
     * Starts a retry-policy builder with conservative defaults.
     *
     * @return a new policy builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return total attempts including the first request */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** @return the delay before the first retry */
    public Duration initialBackoff() {
        return initialBackoff;
    }

    /** @return the maximum delay used for exponential or server-provided backoff */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** @return the random adjustment fraction in the range [0, 1] */
    public double jitterFactor() {
        return jitterFactor;
    }

    /**
     * Fluent builder for {@link RetryPolicy}.
     */
    public static final class Builder {

        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(30);
        private double jitterFactor = 0.2d;

        /**
         * Sets the total number of attempts, including the initial request.
         *
         * @param maxAttempts positive attempt count
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the first retry delay.
         *
         * @param initialBackoff non-negative delay
         * @return this builder
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = requireNonNegative(initialBackoff, "initialBackoff");
            return this;
        }

        /**
         * Caps exponential and {@code Retry-After} delays.
         *
         * @param maxBackoff non-negative delay
         * @return this builder
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = requireNonNegative(maxBackoff, "maxBackoff");
            return this;
        }

        /**
         * Sets the random adjustment applied to locally computed delays.
         *
         * @param jitterFactor value in the inclusive range [0, 1]
         * @return this builder
         */
        public Builder jitterFactor(double jitterFactor) {
            if (jitterFactor < 0d || jitterFactor > 1d || Double.isNaN(jitterFactor)) {
                throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
            }
            this.jitterFactor = jitterFactor;
            return this;
        }

        /**
         * Builds an immutable policy.
         *
         * @return the configured policy
         */
        public RetryPolicy build() {
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
            }
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, jitterFactor);
        }

        /**
         * Validates a duration accepted by the builder.
         *
         * @param value duration to validate
         * @param name property name for diagnostics
         * @return the validated duration
         */
        private static Duration requireNonNegative(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}

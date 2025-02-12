/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.wiring.counters;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility for counting the number of objects in various parts of the pipeline. Will apply backpressure if the number
 * of objects exceeds a specified capacity.
 */
public class BackpressureObjectCounter extends ObjectCounter {

    private final String name;
    private final AtomicLong count = new AtomicLong(0);
    private final long capacity;

    /**
     * When back pressure needs to be applied due to lack of capacity, this object is used to efficiently sleep on the
     * fork join pool.
     */
    private final ManagedBlocker onRampBlocker;

    /**
     * When waiting for the count to reach zero, this object is used to efficiently sleep on the fork join pool.
     */
    private final ManagedBlocker waitUntilEmptyBlocker;

    /**
     * Constructor.
     *
     * @param name          the name of the object counter, used creating more informative exceptions
     * @param capacity      the maximum number of objects that can be in the part of the system that this object is
     *                      being used to monitor before backpressure is applied
     * @param sleepDuration when a method needs to block, the duration to sleep while blocking
     */
    public BackpressureObjectCounter(
            @NonNull final String name, final long capacity, @NonNull final Duration sleepDuration) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }

        this.name = Objects.requireNonNull(name);
        this.capacity = capacity;

        final long sleepNanos = sleepDuration.toNanos();

        onRampBlocker = new BackpressureBlocker(count, capacity, sleepNanos);
        waitUntilEmptyBlocker = new EmptyBlocker(count, sleepNanos);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRamp() {
        while (true) {
            final long currentCount = count.get();
            if (currentCount < capacity) {
                final boolean success = count.compareAndSet(currentCount, currentCount + 1);
                if (success) {
                    return;
                }
            }

            // Slow case. Capacity wasn't reserved, so we may need to block.

            try {
                // This will block until capacity is available and the count has been incremented.
                //
                // This is logically equivalent to the following pseudocode.
                // Note that the managed block is thread safe when onRamp() is being called from multiple threads,
                // even though this pseudocode is not.
                //
                //                while (count >= capacity) {
                //                    Thread.sleep(sleepNanos);
                //                }
                //                count++;
                //
                // The reason why we use the managedBlock() strategy instead of something simpler has to do with
                // the fork join pool paradigm. Unlike traditional thread pools where we have more threads than
                // CPUs, blocking (e.g. Thread.sleep()) on a fork join pool may monopolize an entire CPU core.
                // The managedBlock() pattern allows us to block while yielding the physical CPU core to other
                // tasks.
                ForkJoinPool.managedBlock(onRampBlocker);
                return;
            } catch (final InterruptedException ex) {
                // This should be impossible.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while blocking on an onRamp() for " + name);
            } catch (final RejectedExecutionException ex) {
                // We've exhausted our supply of background threads, we have no choice but to busy wait.
                Thread.onSpinWait();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean attemptOnRamp() {
        while (true) {
            final long currentCount = count.get();
            if (currentCount >= capacity) {
                return false;
            }

            if (count.compareAndSet(currentCount, currentCount + 1)) {
                return true;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceOnRamp() {
        count.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offRamp() {
        count.decrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        return count.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilEmpty() {
        if (count.get() == 0) {
            return;
        }

        try {
            ForkJoinPool.managedBlock(waitUntilEmptyBlocker);
        } catch (final InterruptedException e) {
            // This should be impossible.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while blocking on an waitUntilEmpty() for " + name);
        }
    }
}

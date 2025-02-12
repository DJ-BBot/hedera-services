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

package com.swirlds.platform.test.consensus;

import com.swirlds.platform.test.consensus.framework.TestInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.junit.jupiter.api.function.ThrowingConsumer;

public class ConsensusTestRunner {
    private ConsensusTestParams params;
    private ThrowingConsumer<TestInput> test;
    private int iterations = 1;
    private int eventsToGenerate = 10_000;

    public static @NonNull ConsensusTestRunner create() {
        return new ConsensusTestRunner();
    }

    public @NonNull ConsensusTestRunner setParams(@NonNull final ConsensusTestParams params) {
        this.params = params;
        return this;
    }

    public @NonNull ConsensusTestRunner setTest(@NonNull final ThrowingConsumer<TestInput> test) {
        this.test = test;
        return this;
    }

    public @NonNull ConsensusTestRunner setIterations(final int iterations) {
        this.iterations = iterations;
        return this;
    }

    public void run() {
        try {
            for (final long seed : params.seeds()) {
                System.out.println("Running seed: " + seed);

                test.accept(new TestInput(params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
            }

            for (int i = 0; i < iterations; i++) {
                final long seed = new Random().nextLong();
                System.out.println("Running seed: " + seed);
                test.accept(new TestInput(params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
            }
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }
}

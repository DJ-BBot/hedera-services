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

package com.swirlds.common.wiring.model;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.wiring.model.internal.StandardWiringModel;
import com.swirlds.common.wiring.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.common.wiring.schedulers.builders.TaskSchedulerMetricsBuilder;
import com.swirlds.common.wiring.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * A wiring model is a collection of task schedulers and the wires connecting them. It can be used to analyze the wiring
 * of a system and to generate diagrams.
 */
public interface WiringModel extends Startable, Stoppable {

    /**
     * Build a new wiring model instance.
     *
     * @param platformContext the platform context
     * @param time            provides wall clock time
     * @param defaultPool     the default fork join pool, schedulers not explicitly assigned a pool will use this one
     * @return a new wiring model instance
     */
    @NonNull
    static WiringModel create(
            @NonNull final PlatformContext platformContext,
            @NonNull final Time time,
            @NonNull final ForkJoinPool defaultPool) {
        return new StandardWiringModel(platformContext.getMetrics(), time, defaultPool);
    }

    /**
     * Get a new task scheduler builder.
     *
     * @param name the name of the task scheduler. Used for metrics and debugging. Must be unique. Must only contain
     *             alphanumeric characters and underscores.
     * @param <O>  the data type of the scheduler's primary output wire
     * @return a new task scheduler builder
     */
    @NonNull
    <O> TaskSchedulerBuilder<O> schedulerBuilder(@NonNull final String name);

    /**
     * Get a new task scheduler metrics builder. Can be passed to
     * {@link TaskSchedulerBuilder#withMetricsBuilder(TaskSchedulerMetricsBuilder)} to add metrics to the task
     * scheduler.
     *
     * @return a new task scheduler metrics builder
     */
    @NonNull
    TaskSchedulerMetricsBuilder metricsBuilder();

    /**
     * Check to see if there is cyclic backpressure in the wiring model. Cyclical back pressure can lead to deadlocks,
     * and so it should be avoided at all costs.
     *
     * <p>
     * If this method finds cyclical backpressure, it will log a message that will fail standard platform tests.
     *
     * @return true if there is cyclical backpressure, false otherwise
     */
    boolean checkForCyclicalBackpressure();

    /**
     * Task schedulers using the {@link TaskSchedulerType#DIRECT} strategy have very strict rules about how data can be
     * added to input wires. This method checks to see if these rules are being followed.
     *
     * <p>
     * If this method finds illegal direct scheduler usage, it will log a message that will fail standard platform
     * tests.
     *
     * @return true if there is illegal direct scheduler usage, false otherwise
     */
    boolean checkForIllegalDirectSchedulerUsage();

    /**
     * Check to see if there are any input wires that are unbound.
     *
     * <p>
     * If this method detects unbound input wires in the model, it will log a message that will fail standard platform
     * tests.
     *
     * @return true if there are unbound input wires, false otherwise
     */
    boolean checkForUnboundInputWires();

    /**
     * Generate a mermaid style wiring diagram.
     *
     * @param groups        optional groupings of vertices
     * @param substitutions edges to substitute
     * @param manualLinks   manual links to add to the diagram
     * @return a mermaid style wiring diagram
     */
    @NonNull
    String generateWiringDiagram(
            @NonNull final List<ModelGroup> groups,
            @NonNull final List<ModelEdgeSubstitution> substitutions,
            @NonNull final List<ModelManualLink> manualLinks);

    /**
     * Start everything in the model that needs to be started. Performs static analysis of the wiring topology and
     * writes errors to the logs if problems are detected.
     */
    @Override
    void start();

    /**
     * Stops everything in the model that needs to be stopped.
     */
    @Override
    void stop();
}

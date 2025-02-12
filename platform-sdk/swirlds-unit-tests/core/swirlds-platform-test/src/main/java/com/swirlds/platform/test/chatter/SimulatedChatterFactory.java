/*
 * Copyright (C) 2018-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.test.chatter;

import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Supplier;

public interface SimulatedChatterFactory {
    /**
     * @param selfId
     * 		the ID of the node
     * @param nodeIds
     * 		IDs of all the nodes in the network
     * @param eventHandler
     * 		is provided all new events received through chatter
     */
    SimulatedChatter build(
            @NonNull final NodeId selfId,
            @NonNull final Iterable<NodeId> nodeIds,
            @NonNull final GossipEventObserver eventHandler,
            @NonNull final Supplier<Instant> now);
}

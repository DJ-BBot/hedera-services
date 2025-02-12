/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.mono.fees.calculation.consensus.txns;

import static com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder.getConsensusUpdateTopicFee;
import static com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder.getUpdateTopicRbsIncrease;

import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.mono.context.primitives.StateView;
import com.hedera.node.app.service.mono.fees.calculation.TxnResourceUsageEstimator;
import com.hedera.node.app.service.mono.legacy.core.jproto.JKey;
import com.hedera.node.app.service.mono.state.merkle.MerkleTopic;
import com.hedera.node.app.service.mono.utils.EntityNum;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.InvalidKeyException;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class UpdateTopicResourceUsage implements TxnResourceUsageEstimator {
    private static final Logger log = LogManager.getLogger(UpdateTopicResourceUsage.class);

    @Inject
    public UpdateTopicResourceUsage() {
        /* No-op */
    }

    @Override
    public boolean applicableTo(final TransactionBody txn) {
        return txn.hasConsensusUpdateTopic();
    }

    @Override
    public FeeData usageGiven(
            @Nullable final TransactionBody txnBody, final SigValueObj sigUsage, @Nullable final StateView view) {
        if (view == null) {
            throw new IllegalStateException("No StateView present !!");
        }

        final var merkleTopic = view.topics()
                .get(EntityNum.fromTopicId(txnBody.getConsensusUpdateTopic().getTopicID()));
        return usageGivenExplicit(txnBody, sigUsage, merkleTopic);
    }

    public FeeData usageGivenExplicit(
            @NonNull final TransactionBody txnBody,
            @NonNull final SigValueObj sigUsage,
            @Nullable final MerkleTopic merkleTopic) {
        long rbsIncrease = 0;
        if (merkleTopic != null && merkleTopic.hasAdminKey()) {
            final var expiry = Timestamp.newBuilder()
                    .setSeconds(merkleTopic.getExpirationTimestamp().getSeconds())
                    .build();
            try {
                rbsIncrease = getUpdateTopicRbsIncrease(
                        txnBody.getTransactionID().getTransactionValidStart(),
                        JKey.mapJKey(merkleTopic.getAdminKey()),
                        JKey.mapJKey(merkleTopic.getSubmitKey()),
                        merkleTopic.getMemo(),
                        merkleTopic.hasAutoRenewAccountId(),
                        expiry,
                        txnBody.getConsensusUpdateTopic());
            } catch (final InvalidKeyException ignore) {
                // The key was validated before setting on MerkleTopic, so this should never happen
                log.warn("Usage estimation unexpectedly failed for {}!", txnBody, ignore);
            }
        }
        return getConsensusUpdateTopicFee(txnBody, rbsIncrease, sigUsage);
    }
}

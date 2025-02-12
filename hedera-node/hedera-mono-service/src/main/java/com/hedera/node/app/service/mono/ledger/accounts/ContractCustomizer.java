/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.mono.ledger.accounts;

import static com.hedera.node.app.service.mono.ledger.accounts.HederaAccountCustomizer.hasStakedId;
import static com.hedera.node.app.service.mono.ledger.properties.AccountProperty.AUTO_RENEW_ACCOUNT_ID;
import static com.hedera.node.app.service.mono.ledger.properties.AccountProperty.AUTO_RENEW_PERIOD;
import static com.hedera.node.app.service.mono.ledger.properties.AccountProperty.EXPIRY;
import static com.hedera.node.app.service.mono.ledger.properties.AccountProperty.KEY;
import static com.hedera.node.app.service.mono.ledger.properties.AccountProperty.MEMO;
import static com.hedera.node.app.service.mono.legacy.core.jproto.JKey.denotesImmutableEntity;
import static com.hedera.node.app.service.mono.utils.MiscUtils.asKeyUnchecked;

import com.hedera.node.app.service.mono.ledger.TransactionalLedger;
import com.hedera.node.app.service.mono.ledger.properties.AccountProperty;
import com.hedera.node.app.service.mono.legacy.core.jproto.JContractIDKey;
import com.hedera.node.app.service.mono.legacy.core.jproto.JKey;
import com.hedera.node.app.service.mono.state.migration.HederaAccount;
import com.hedera.node.app.service.mono.state.submerkle.EntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hyperledger.besu.datatypes.Address;

/**
 * Encapsulates a set of customizations to a smart contract. Primarily delegates to an {@link
 * HederaAccountCustomizer}, but with a bit of extra logic to deal with {@link JContractIDKey}
 * management.
 */
public class ContractCustomizer {
    // Null if the contract is immutable; then its key derives from its entity id
    private final JKey cryptoAdminKey;
    private final HederaAccountCustomizer accountCustomizer;
    private final Address customizerAppliesToAddress;

    public ContractCustomizer(final HederaAccountCustomizer accountCustomizer) {
        this(null, accountCustomizer, null);
    }

    public ContractCustomizer(
            final @Nullable JKey cryptoAdminKey,
            final HederaAccountCustomizer accountCustomizer,
            final @Nullable Address customizerAppliesToAddress) {
        this.cryptoAdminKey = cryptoAdminKey;
        this.accountCustomizer = accountCustomizer;
        this.customizerAppliesToAddress = customizerAppliesToAddress;
    }

    /**
     * Given a {@link ContractCreateTransactionBody}, a decoded admin key, and the current consensus
     * time, returns a customizer appropriate for the contract created from this HAPI operation.
     *
     * @param decodedKey the key implied by the HAPI operation
     * @param consensusTime the consensus time of the ContractCreate
     * @param op the details of the HAPI operation
     * @param customizerAppliesToAddress the address of the contract being created, or null if not applicable
     * @return an appropriate top-level customizer
     */
    public static ContractCustomizer fromHapiCreation(
            final JKey decodedKey,
            final Instant consensusTime,
            final ContractCreateTransactionBody op,
            final Address customizerAppliesToAddress) {
        final var autoRenewPeriod = op.getAutoRenewPeriod().getSeconds();
        final var expiry = consensusTime.getEpochSecond() + autoRenewPeriod;

        final var key = (decodedKey instanceof JContractIDKey) ? null : decodedKey;
        // The customizer ignores null values, so use null if no auto-renew account is specified
        final var autoRenewAccount =
                op.hasAutoRenewAccountId() ? EntityId.fromGrpcAccountId(op.getAutoRenewAccountId()) : null;
        final var customizer = new HederaAccountCustomizer()
                .memo(op.getMemo())
                .expiry(expiry)
                .autoRenewPeriod(op.getAutoRenewPeriod().getSeconds())
                .isDeclinedReward(op.getDeclineReward())
                .autoRenewAccount(autoRenewAccount)
                .maxAutomaticAssociations(op.getMaxAutomaticTokenAssociations())
                .isSmartContract(true);

        if (hasStakedId(op.getStakedIdCase().name())) {
            customizer.customizeStakedId(op.getStakedIdCase().name(), op.getStakedAccountId(), op.getStakedNodeId());
        }

        return new ContractCustomizer(key, customizer, customizerAppliesToAddress);
    }

    /**
     * Given a {@link TransactionalLedger} containing the sponsor contract, returns a customizer
     * appropriate to use for contracts created by the sponsor via internal {@code
     * CONTRACT_CREATION} message calls.
     *
     * @param sponsor the sending contract
     * @param ledger the containing ledger
     * @param customizerAppliesToAddress the address of the contract being created, or null if not applicable
     * @return an appropriate child customizer
     */
    public static ContractCustomizer fromSponsorContract(
            final AccountID sponsor,
            final TransactionalLedger<AccountID, AccountProperty, HederaAccount> ledger,
            final Address customizerAppliesToAddress) {
        var key = (JKey) ledger.get(sponsor, KEY);
        if (key instanceof JContractIDKey) {
            key = null;
        }
        final var customizer = getAccountCustomizer(sponsor, ledger);
        return new ContractCustomizer(key, customizer, customizerAppliesToAddress);
    }

    public static ContractCustomizer fromSponsorContractWithoutKey(
            final AccountID sponsor, final TransactionalLedger<AccountID, AccountProperty, HederaAccount> ledger) {
        final var customizer = getAccountCustomizer(sponsor, ledger);
        return new ContractCustomizer(customizer);
    }

    private static HederaAccountCustomizer getAccountCustomizer(
            final AccountID sponsor, final TransactionalLedger<AccountID, AccountProperty, HederaAccount> ledger) {
        return new HederaAccountCustomizer()
                .memo((String) ledger.get(sponsor, MEMO))
                .expiry((long) ledger.get(sponsor, EXPIRY))
                .autoRenewPeriod((long) ledger.get(sponsor, AUTO_RENEW_PERIOD))
                .autoRenewAccount((EntityId) ledger.get(sponsor, AUTO_RENEW_ACCOUNT_ID))
                .maxAutomaticAssociations((int) ledger.get(sponsor, AccountProperty.MAX_AUTOMATIC_ASSOCIATIONS))
                .stakedId((long) ledger.get(sponsor, AccountProperty.STAKED_ID))
                .isDeclinedReward((boolean) ledger.get(sponsor, AccountProperty.DECLINE_REWARD))
                .isSmartContract(true);
    }

    /**
     * Given a target contract account id and the containing ledger, makes various calls to {@link
     * TransactionalLedger#set(Object, Enum, Object)} to initialize the contract's properties.
     *
     * @param id the id of the target contract
     * @param ledger its containing ledger
     */
    public void customize(
            final AccountID id, final TransactionalLedger<AccountID, AccountProperty, HederaAccount> ledger) {
        accountCustomizer.customize(id, ledger);
        // A CREATE2 operation that is finalizing a hollow account can itself create children in its spawned
        // CONTRACT_CREATION message; if we didn't have the second check here for denotesImmutableEntity(),
        // the children would _inherit_ empty key lists, and be incorrectly interpreted as hollow accounts
        final var newKey = (cryptoAdminKey == null || denotesImmutableEntity(cryptoAdminKey))
                ? new JContractIDKey(id.getShardNum(), id.getRealmNum(), id.getAccountNum())
                : cryptoAdminKey;
        ledger.set(id, KEY, newKey);
    }

    /**
     * Updates a synthetic {@link ContractCreateTransactionBody} to represent the creation of a
     * contract with this customizer's properties.
     *
     * @param op the synthetic creation to customize
     */
    public void customizeSynthetic(final ContractCreateTransactionBody.Builder op) {
        if (cryptoAdminKey != null) {
            op.setAdminKey(asKeyUnchecked(cryptoAdminKey));
        }
        accountCustomizer.customizeSynthetic(op);
    }

    public HederaAccountCustomizer accountCustomizer() {
        return accountCustomizer;
    }

    public boolean appliesTo(final Address address) {
        if (customizerAppliesToAddress == null) {
            throw new IllegalStateException("CustomizerAppliesToAddress is null");
        }
        return customizerAppliesToAddress.equals(address);
    }
}

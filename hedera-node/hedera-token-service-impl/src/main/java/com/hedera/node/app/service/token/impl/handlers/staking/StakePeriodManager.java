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

package com.hedera.node.app.service.token.impl.handlers.staking;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.api.StakingRewardsApi;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class manages the current stake period and the previous stake period.
 */
@Singleton
public class StakePeriodManager {
    // Sentinel value for a field that wasn't applicable to this transaction
    public static final long NA = Long.MIN_VALUE;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    public static final long DEFAULT_STAKING_PERIOD_MINS = 1440L;

    private final int numStoredPeriods;
    private final long stakingPeriodMins;
    private long currentStakePeriod;
    private long prevConsensusSecs;

    @Inject
    public StakePeriodManager(@NonNull final ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration().getConfigData(StakingConfig.class);
        numStoredPeriods = config.rewardHistoryNumStoredPeriods();
        stakingPeriodMins = config.periodMins();
    }

    /**
     * Returns the epoch second at the start of the given stake period. It is used in
     * {@link StakeRewardCalculatorImpl} to set the stakePeriodStart
     * on each {@link com.hedera.hapi.node.state.token.StakingNodeInfo} object
     * @param stakePeriod the stake period
     * @return the epoch second at the start of the given stake period
     */
    public long epochSecondAtStartOfPeriod(final long stakePeriod) {
        return StakingRewardsApi.epochSecondAtStartOfPeriod(stakePeriod, stakingPeriodMins);
    }

    /**
     * Returns the current stake period, based on the current consensus time.
     * Current staking period is very important to calculate rewards.
     * Since any account is rewarded only once per a stake period.
     * @param consensusNow the current consensus time
     * @return the current stake period
     */
    public long currentStakePeriod(@NonNull final Instant consensusNow) {
        final var currentConsensusSecs = consensusNow.getEpochSecond();
        if (prevConsensusSecs != currentConsensusSecs) {
            currentStakePeriod = StakingRewardsApi.stakePeriodAt(consensusNow, stakingPeriodMins);
            prevConsensusSecs = currentConsensusSecs;
        }
        return currentStakePeriod;
    }

    /**
     * Based on the stake period start, returns if the current consensus time is
     * rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @param consensusNow the current consensus time
     * @return true if the current consensus time is rewardable, false otherwise
     */
    public boolean isRewardable(
            final long stakePeriodStart,
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards,
            @NonNull final Instant consensusNow) {
        return stakePeriodStart > -1 && stakePeriodStart < firstNonRewardableStakePeriod(networkRewards, consensusNow);
    }

    /**
     * Returns the first stake period that is not rewardable. This is used to determine
     * if an account is eligible for a reward, as soon as staking rewards are activated.
     * @param rewardsStore the network rewards store
     * @param consensusNow the current consensus time
     * @return the first stake period that is not rewardable
     */
    public long firstNonRewardableStakePeriod(
            @NonNull final ReadableNetworkStakingRewardsStore rewardsStore, @NonNull final Instant consensusNow) {

        // The earliest period by which an account can have started staking, _without_ becoming
        // eligible for a reward; if staking is not active, this will return Long.MIN_VALUE so
        // no account can ever be eligible.
        // Remember that accounts are only rewarded for _full_ periods.
        // So if Alice started staking in the previous period (current - 1), she will not have
        // completed a full period until current has ended
        return rewardsStore.isStakingRewardsActivated() ? currentStakePeriod(consensusNow) - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns the effective stake period start, based on the current stake period and the
     * number of stored periods.
     * @param stakePeriodStart the stake period start
     * @return the effective stake period start
     */
    public long effectivePeriod(final long stakePeriodStart) {
        return StakingRewardsApi.clampedStakePeriodStart(stakePeriodStart, currentStakePeriod, numStoredPeriods);
    }

    /* ----------------------- estimated stake periods ----------------------- */
    /**
     * Returns the estimated current stake period, based on the current wall-clock time.
     * We use wall-clock time here, because this method is called in two places:
     * 1. When we get the first stakePeriod after staking rewards are activated, to see
     *    if any rewards can be triggered.
     * 2. When we do upgrade, if we need to migrate any staking rewards.
     * The default staking period is 1 day, so this will return the current day.
     * For testing we use a shorter staking period, so we can estimate staking period for
     * a shorter period.
     * @return the estimated current stake period
     */
    public long estimatedCurrentStakePeriod() {
        return StakingRewardsApi.estimatedCurrentStakePeriod(stakingPeriodMins);
    }

    /**
     * Returns the estimated first stake period that is not rewardable.
     * @param networkRewards the network rewards
     * @return the estimated first stake period that is not rewardable
     */
    public long estimatedFirstNonRewardableStakePeriod(
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return networkRewards.isStakingRewardsActivated() ? estimatedCurrentStakePeriod() - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns if the estimated stake period is rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @return true if the estimated stake period is rewardable, false otherwise
     */
    public boolean isEstimatedRewardable(
            final long stakePeriodStart, @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return StakingRewardsApi.isEstimatedRewardable(
                stakingPeriodMins, stakePeriodStart, networkRewards.isStakingRewardsActivated());
    }

    /**
     * Given the current and new staked ids for an account, as well as if it received a reward in
     * this transaction, returns the new {@code stakePeriodStart} for this account:
     *
     * <ol>
     *   <li>{@link com.hedera.node.app.service.mono.ledger.accounts.staking.StakingUtils#NA} if the {@code stakePeriodStart} doesn't need to change; or,
     *   <li>The value to which the {@code stakePeriodStart} should be changed.
     * </ol>
     *
     * @param originalAccount the original account before the transaction
     * @param modifiedAccount the modified account after the transaction
     * @param rewarded whether the account was rewarded during the transaction
     * @param stakeMetaChanged whether the account's stake metadata changed
     * @param consensusNow the current consensus time
     * @return either NA for no new stakePeriodStart, or the new value
     */
    public long startUpdateFor(
            @Nullable final Account originalAccount,
            @NonNull final Account modifiedAccount,
            final boolean rewarded,
            final boolean stakeMetaChanged,
            @NonNull final Instant consensusNow) {
        // Only worthwhile to update stakedPeriodStart for an account staking to a node
        if (modifiedAccount.hasStakedNodeId()) {
            if ((originalAccount != null && originalAccount.hasStakedAccountId()) || stakeMetaChanged) {
                // We just started staking to a node today
                return currentStakePeriod(consensusNow);
            } else if (rewarded) {
                // If we were just rewarded, stake period start is yesterday
                return currentStakePeriod(consensusNow) - 1;
            }
        }
        return -1;
    }

    @VisibleForTesting
    public long getPrevConsensusSecs() {
        return prevConsensusSecs;
    }
}

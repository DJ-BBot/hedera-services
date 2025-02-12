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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCall.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersCall.transferGasRequirement;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.Erc20TransfersTranslator.ERC_20_TRANSFER_FROM;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallRecordBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Implements the ERC-20 {@code transfer()} and {@code transferFrom()} calls of the HTS contract.
 */
public class Erc20TransfersCall extends AbstractHtsCall {
    private final long amount;

    @Nullable
    private final Address from;

    private final Address to;

    @Nullable
    private final TokenID tokenId;

    private final VerificationStrategy verificationStrategy;
    private final AccountID senderId;
    private final AddressIdConverter addressIdConverter;
    private final boolean requiresApproval;

    // too many parameters
    @SuppressWarnings("java:S107")
    public Erc20TransfersCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            final long amount,
            @Nullable final Address from,
            @NonNull final Address to,
            @Nullable final TokenID tokenId,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final AddressIdConverter addressIdConverter,
            final boolean requiresApproval) {
        super(gasCalculator, enhancement, false);
        this.amount = amount;
        this.from = from;
        this.to = requireNonNull(to);
        this.tokenId = tokenId;
        this.verificationStrategy = requireNonNull(verificationStrategy);
        this.senderId = requireNonNull(senderId);
        this.addressIdConverter = requireNonNull(addressIdConverter);
        this.requiresApproval = requiresApproval;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull PricedResult execute() {
        // https://eips.ethereum.org/EIPS/eip-20
        final var syntheticTransfer = syntheticTransferOrTransferFrom(senderId);
        final var selector = (from == null) ? ERC_20_TRANSFER.selector() : ERC_20_TRANSFER_FROM.selector();
        final var gasRequirement =
                transferGasRequirement(syntheticTransfer, gasCalculator, enhancement, senderId, selector);
        if (tokenId == null) {
            return reversionWith(INVALID_TOKEN_ID, gasRequirement);
        }
        final var recordBuilder = systemContractOperations()
                .dispatch(
                        syntheticTransferOrTransferFrom(senderId),
                        verificationStrategy,
                        senderId,
                        ContractCallRecordBuilder.class);
        final var status = recordBuilder.status();
        if (status != SUCCESS) {
            return gasOnly(revertResult(recordBuilder, gasRequirement), status, false);
        } else {
            final var encodedOutput = (from == null)
                    ? ERC_20_TRANSFER.getOutputs().encodeElements(true)
                    : ERC_20_TRANSFER_FROM.getOutputs().encodeElements(true);
            recordBuilder.contractCallResult(ContractFunctionResult.newBuilder()
                    .contractCallResult(Bytes.wrap(encodedOutput.array()))
                    .build());
            return gasOnly(successResult(encodedOutput, gasRequirement, recordBuilder), status, false);
        }
    }

    @NonNull
    @Override
    public PricedResult execute(final MessageFrame frame) {
        final var result = execute();

        if (result.fullResult().result().getState().equals(MessageFrame.State.COMPLETED_SUCCESS)) {
            final var tokenTransferLists = syntheticTransferOrTransferFrom(senderId)
                    .cryptoTransferOrThrow()
                    .tokenTransfersOrThrow();
            for (final var fungibleTransfers : tokenTransferLists) {
                TransferEventLoggingUtils.logSuccessfulFungibleTransfer(
                        requireNonNull(tokenId),
                        fungibleTransfers.transfersOrThrow(),
                        enhancement.nativeOperations().readableAccountStore(),
                        frame);
            }
        }
        return result;
    }

    private TransactionBody syntheticTransferOrTransferFrom(@NonNull final AccountID spenderId) {
        final var receiverId = addressIdConverter.convertCredit(to);
        final var ownerId = (from == null) ? spenderId : addressIdConverter.convert(from);
        return TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(tokenId)
                                .transfers(
                                        AccountAmount.newBuilder()
                                                .accountID(receiverId)
                                                .amount(amount)
                                                .build(),
                                        AccountAmount.newBuilder()
                                                .accountID(ownerId)
                                                .amount(-amount)
                                                .isApproval(requiresApproval || !spenderId.equals(ownerId))
                                                .build())
                                .build()))
                .build();
    }
}

/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.signed;

/**
 * Summary level data about the signatures on a signed state
 *
 * @param numTotalSigs
 * 		the total number of signatures
 * @param numValidSigs
 * 		the number of valid signatures
 * @param validWeight
 * 		the total amount of weight from the valid signatures
 */
public record SignatureSummary(int numTotalSigs, int numValidSigs, long validWeight) {}

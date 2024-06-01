/*
 * Copyright 2022-2023 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.example.model.api.request;

import javax.annotation.Nonnull;
import java.time.YearMonth;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record ToyPurchaseRequest(
		@Nonnull UUID toyId,
		@Nonnull UUID accountId,
		@Nonnull String creditCardNumber,
		@Nonnull YearMonth creditCardExpiration
) {
	@Nonnull
	public ToyPurchaseRequest withToyId(@Nonnull UUID toyId) {
		requireNonNull(toyId);
		return new ToyPurchaseRequest(toyId, accountId, creditCardNumber, creditCardExpiration);
	}

	@Nonnull
	public ToyPurchaseRequest withAccountId(@Nonnull UUID accountId) {
		requireNonNull(accountId);
		return new ToyPurchaseRequest(toyId, accountId, creditCardNumber, creditCardExpiration);
	}
}
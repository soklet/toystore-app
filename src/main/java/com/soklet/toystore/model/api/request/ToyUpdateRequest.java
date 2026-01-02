/*
 * Copyright 2022-2026 Revetware LLC.
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

package com.soklet.toystore.model.api.request;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record ToyUpdateRequest(
		@Nullable UUID toyId,
		@Nullable String name,
		@Nullable BigDecimal price,
		@Nullable Currency currency
) {
	@NonNull
	public ToyUpdateRequest withToyId(@Nullable UUID toyId) {
		return new ToyUpdateRequest(toyId, name, price, currency);
	}
}
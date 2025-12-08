/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example.model.db;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Maps to the {@code toy} table in the database.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record Toy(
		@Nonnull UUID toyId,
		@Nonnull String name,
		@Nonnull BigDecimal price,
		@Nonnull Currency currency,
		@Nonnull Instant createdAt
) {
	public Toy {
		requireNonNull(toyId);
		requireNonNull(name);
		requireNonNull(price);
		requireNonNull(currency);
		requireNonNull(createdAt);
	}
}
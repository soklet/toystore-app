/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.example.model.db.Role.RoleId;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Maps to the {@code account} table in the database.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record Account(
		@Nonnull UUID accountId,
		@Nonnull RoleId roleId,
		@Nonnull String name,
		@Nonnull String emailAddress,
		@Nonnull String passwordHash,
		@Nonnull ZoneId timeZone,
		@Nonnull Locale locale,
		@Nonnull Instant createdAt
) {
	public Account {
		requireNonNull(accountId);
		requireNonNull(roleId);
		requireNonNull(name);
		requireNonNull(emailAddress);
		requireNonNull(passwordHash);
		requireNonNull(timeZone);
		requireNonNull(locale);
		requireNonNull(createdAt);
	}
}
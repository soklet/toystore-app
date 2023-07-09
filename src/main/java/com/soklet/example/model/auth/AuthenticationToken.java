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

package com.soklet.example.model.auth;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.UUID;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record AuthenticationToken(
		@Nonnull UUID employeeId,
		@Nonnull String assertion,
		@Nonnull Instant expiration
) {
	@Nonnull
	public String encodeAsString() {
		return format("%s|%s|%s", employeeId(), assertion(), expiration().toEpochMilli());
	}

	@Nonnull
	public static AuthenticationToken decodeFromString(@Nonnull String string) {
		requireNonNull(string);

		String[] components = string.split("\\|");

		if (components.length != 3)
			throw new IllegalArgumentException(format("Invalid authentication token structure for '%s'", string));

		try {
			UUID employeeId = UUID.fromString(components[0]);
			String assertion = components[1];
			Instant expiration = Instant.ofEpochMilli(Long.valueOf(components[2]));

			return new AuthenticationToken(employeeId, assertion, expiration);
		} catch (Exception e) {
			throw new IllegalArgumentException(format("Invalid authentication token contents for '%s'", string), e);
		}
	}
}
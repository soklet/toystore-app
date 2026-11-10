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

package com.soklet.toystore.util;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Locale;
import java.util.Optional;

/**
 * Utilities for normalizing user-supplied input.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Normalizer {
	@NonNull
	public static Optional<String> normalizeEmailAddress(@Nullable String emailAddress) {
		emailAddress = trimAggressivelyToNull(emailAddress);

		if (!Validator.isValidEmailAddress(emailAddress))
			return Optional.empty();

		return Optional.of(emailAddress.toLowerCase(Locale.ROOT));
	}

	/**
	 * A "stronger" version of {@link String#trim()} which discards any kind of whitespace or invisible separator.
	 */
	@NonNull
	public static Optional<String> trimAggressively(@Nullable String string) {
		return Optional.ofNullable(com.soklet.Utilities.trimAggressively(string))
				.filter(value -> !value.isBlank());
	}

	@Nullable
	public static String trimAggressivelyToNull(@Nullable String string) {
		return trimAggressively(string).orElse(null);
	}

	private Normalizer() {
		// Non-instantiable
	}
}

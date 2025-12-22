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

package com.soklet.toystore.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Utilities for normalizing user-supplied input.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Normalizer {
	@Nonnull
	private static final Pattern HEAD_WHITESPACE_PATTERN;
	@Nonnull
	private static final Pattern TAIL_WHITESPACE_PATTERN;

	static {
		HEAD_WHITESPACE_PATTERN = Pattern.compile("^(\\p{Z})+");
		TAIL_WHITESPACE_PATTERN = Pattern.compile("(\\p{Z})+$");
	}

	@Nonnull
	public static Optional<String> normalizeEmailAddress(@Nullable String emailAddress) {
		emailAddress = trimAggressivelyToNull(emailAddress);

		if (!Validator.isValidEmailAddress(emailAddress))
			return Optional.empty();

		return Optional.of(emailAddress.toLowerCase(Locale.ROOT));
	}

	/**
	 * A "stronger" version of {@link String#trim()} which discards any kind of whitespace or invisible separator.
	 */
	@Nonnull
	public static Optional<String> trimAggressively(@Nullable String string) {
		if (string == null)
			return Optional.empty();

		string = HEAD_WHITESPACE_PATTERN.matcher(string).replaceAll("");

		if (string.length() == 0)
			return Optional.of(string);

		string = TAIL_WHITESPACE_PATTERN.matcher(string).replaceAll("");

		return Optional.of(string);
	}

	@Nullable
	public static String trimAggressivelyToNull(@Nullable String string) {
		return trimAggressively(string).orElse(null);
	}

	private Normalizer() {
		// Non-instantiable
	}
}

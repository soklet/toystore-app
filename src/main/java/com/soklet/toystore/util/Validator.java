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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.regex.Pattern;

/**
 * Utilities for validating user-supplied input.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Validator {
	private static final int EMAIL_ADDRESS_MAX_LENGTH;
	private static final int EMAIL_ADDRESS_MAX_LOCAL_PART_LENGTH;
	private static final int EMAIL_ADDRESS_MAX_DOMAIN_LENGTH;
	@NonNull
	private static final Pattern EMAIL_ADDRESS_LOCAL_PART_PATTERN;
	@NonNull
	private static final Pattern EMAIL_ADDRESS_DOMAIN_LABEL_PATTERN;

	static {
		EMAIL_ADDRESS_MAX_LENGTH = 320;
		EMAIL_ADDRESS_MAX_LOCAL_PART_LENGTH = 64;
		EMAIL_ADDRESS_MAX_DOMAIN_LENGTH = 255;
		EMAIL_ADDRESS_LOCAL_PART_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
		EMAIL_ADDRESS_DOMAIN_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
	}

	@NonNull
	public static Boolean isValidEmailAddress(@Nullable String emailAddress) {
		if (emailAddress == null)
			return false;

		String trimmed = Normalizer.trimAggressivelyToNull(emailAddress);

		if (trimmed == null || trimmed.length() > EMAIL_ADDRESS_MAX_LENGTH)
			return false;

		if (trimmed.chars().anyMatch(Character::isWhitespace))
			return false;

		int atIndex = trimmed.indexOf('@');

		if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@') || atIndex == trimmed.length() - 1)
			return false;

		String localPart = trimmed.substring(0, atIndex);
		String domain = trimmed.substring(atIndex + 1);

		if (localPart.length() > EMAIL_ADDRESS_MAX_LOCAL_PART_LENGTH || domain.length() > EMAIL_ADDRESS_MAX_DOMAIN_LENGTH)
			return false;

		if (!EMAIL_ADDRESS_LOCAL_PART_PATTERN.matcher(localPart).matches())
			return false;

		int lastDotIndex = domain.lastIndexOf('.');
		if (lastDotIndex <= 0 || lastDotIndex == domain.length() - 1)
			return false;

		String[] labels = domain.split("\\.");
		if (labels.length < 2)
			return false;

		for (String label : labels)
			if (!EMAIL_ADDRESS_DOMAIN_LABEL_PATTERN.matcher(label).matches())
				return false;

		if (labels[labels.length - 1].length() < 2)
			return false;

		return true;
	}

	@NonNull
	public static Boolean isValidCreditCardNumber(@Nullable String creditCardNumber) {
		if (creditCardNumber == null)
			return false;

		String trimmed = Normalizer.trimAggressivelyToNull(creditCardNumber);

		if (trimmed == null)
			return false;

		// Basic Luhn check
		StringBuilder digits = new StringBuilder(trimmed.length());

		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (Character.isDigit(c)) {
				digits.append(c);
			} else if (c == ' ' || c == '-') {
				// Ignore spaces and hyphens
			} else {
				return false;
			}
		}

		int length = digits.length();

		if (length < 12 || length > 19)
			return false;

		int sum = 0;
		boolean doubleDigit = false;

		for (int i = length - 1; i >= 0; i--) {
			int digit = digits.charAt(i) - '0';

			if (doubleDigit) {
				digit *= 2;
				if (digit > 9)
					digit -= 9;
			}

			sum += digit;
			doubleDigit = !doubleDigit;
		}

		return sum % 10 == 0;
	}

	private Validator() {
		// Non-instantiable
	}
}

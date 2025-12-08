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

package com.soklet.example.util;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.util.Currency;

import static java.util.Objects.requireNonNull;

/**
 * Example interface for performing credit card transactions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface CreditCardProcessor {
	@Nonnull
	String makePayment(@Nonnull String creditCardNumber,
										 @Nonnull BigDecimal amount,
										 @Nonnull Currency currency) throws CreditCardPaymentException;

	enum CreditCardPaymentFailureReason {
		INVALID_CARD_NUMBER,
		CARD_EXPIRED,
		DECLINED,
		UNKNOWN
	}

	@NotThreadSafe
	class CreditCardPaymentException extends Exception {
		@Nonnull
		CreditCardPaymentFailureReason failureReason;

		public CreditCardPaymentException(@Nonnull CreditCardPaymentFailureReason failureReason) {
			super(requireNonNull(failureReason).name());
			this.failureReason = failureReason;
		}

		@Nonnull
		public CreditCardPaymentFailureReason getFailureReason() {
			return this.failureReason;
		}
	}
}
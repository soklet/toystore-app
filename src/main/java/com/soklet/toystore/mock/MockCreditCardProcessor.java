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

package com.soklet.toystore.mock;

import com.soklet.toystore.util.CreditCardProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Mock implementation of {@link CreditCardProcessor} which pretends to process a credit-card transaction.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MockCreditCardProcessor implements CreditCardProcessor {
	@Nonnull
	private final Logger logger;

	public MockCreditCardProcessor() {
		this.logger = LoggerFactory.getLogger(getClass());
	}

	@Nonnull
	@Override
	public String makePayment(@Nonnull String creditCardNumber,
														@Nonnull BigDecimal amount,
														@Nonnull Currency currency) throws CreditCardPaymentException {
		requireNonNull(creditCardNumber);
		requireNonNull(amount);
		requireNonNull(currency);

		getLogger().info("Performing CC transaction for card number that begins with {}...", creditCardNumber.substring(0, 4));

		// Pretend to do some work
		try {
			Thread.sleep(250L);
		} catch (InterruptedException ignored) {
			// Don't care
		}

		return UUID.randomUUID().toString();
	}

	@Nonnull
	private Logger getLogger() {
		return this.logger;
	}
}
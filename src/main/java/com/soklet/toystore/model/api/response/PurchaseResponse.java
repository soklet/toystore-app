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

package com.soklet.toystore.model.api.response;

import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.db.Purchase;
import com.soklet.toystore.util.Formatter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Public-facing representation of a {@link Purchase}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class PurchaseResponse {
	@Nonnull
	private final UUID purchaseId;
	@Nonnull
	private final UUID accountId;
	@Nonnull
	private final UUID toyId;
	@Nonnull
	private final BigDecimal price;
	@Nonnull
	private final String priceDescription;
	@Nonnull
	private final String currencyCode;
	@Nonnull
	private final String currencySymbol;
	@Nonnull
	private final String currencyDescription;
	@Nonnull
	private final String creditCardTransactionId;
	@Nonnull
	private final Instant createdAt;
	@Nonnull
	private final String createdAtDescription;

	@ThreadSafe
	public interface PurchaseResponseFactory {
		@Nonnull
		PurchaseResponse create(@Nonnull Purchase purchase);
	}

	@AssistedInject
	public PurchaseResponse(@Nonnull Provider<CurrentContext> currentContextProvider,
													@Nonnull Formatter formatter,
													@Assisted @Nonnull Purchase purchase) {
		requireNonNull(currentContextProvider);
		requireNonNull(formatter);
		requireNonNull(purchase);

		// Tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getLocale();
		ZoneId currentTimeZone = currentContext.getTimeZone();
		NumberFormat currencyFormatter = formatter.currencyFormatter(currentLocale, purchase.currency());
		DateTimeFormatter dateTimeFormatter = formatter.dateTimeFormatter(
				new Formatter.DateTimeFormatterConfig(currentLocale, currentTimeZone, FormatStyle.MEDIUM, FormatStyle.SHORT)
		);

		this.purchaseId = purchase.toyId();
		this.accountId = purchase.accountId();
		this.toyId = purchase.toyId();
		this.price = purchase.price();
		this.priceDescription = currencyFormatter.format(purchase.price());
		this.currencyCode = purchase.currency().getCurrencyCode();
		this.currencySymbol = purchase.currency().getSymbol(currentLocale);
		this.currencyDescription = purchase.currency().getDisplayName(currentLocale);
		this.creditCardTransactionId = purchase.creditCardTransactionId();
		this.createdAt = purchase.createdAt();
		this.createdAtDescription = dateTimeFormatter.format(purchase.createdAt());
	}

	public record PurchaseResponseHolder(
			@Nonnull PurchaseResponse purchase
	) {
		public PurchaseResponseHolder {
			requireNonNull(purchase);
		}
	}

	@Nonnull
	public UUID getPurchaseId() {
		return this.purchaseId;
	}

	@Nonnull
	public UUID getAccountId() {
		return this.accountId;
	}

	@Nonnull
	public UUID getToyId() {
		return this.toyId;
	}

	@Nonnull
	public BigDecimal getPrice() {
		return this.price;
	}

	@Nonnull
	public String getPriceDescription() {
		return this.priceDescription;
	}

	@Nonnull
	public String getCurrencyCode() {
		return this.currencyCode;
	}

	@Nonnull
	public String getCurrencySymbol() {
		return this.currencySymbol;
	}

	@Nonnull
	public String getCurrencyDescription() {
		return this.currencyDescription;
	}

	@Nonnull
	public String getCreditCardTransactionId() {
		return this.creditCardTransactionId;
	}

	@Nonnull
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@Nonnull
	public String getCreatedAtDescription() {
		return this.createdAtDescription;
	}
}
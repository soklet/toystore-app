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

package com.soklet.toystore.model.api.response;

import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.db.Purchase;
import com.soklet.toystore.util.Formatter;
import org.jspecify.annotations.NonNull;

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
	@NonNull
	private final UUID purchaseId;
	@NonNull
	private final UUID accountId;
	@NonNull
	private final UUID toyId;
	@NonNull
	private final BigDecimal price;
	@NonNull
	private final String priceDescription;
	@NonNull
	private final String currencyCode;
	@NonNull
	private final String currencySymbol;
	@NonNull
	private final String currencyDescription;
	@NonNull
	private final String creditCardTransactionId;
	@NonNull
	private final Instant createdAt;
	@NonNull
	private final String createdAtDescription;

	@ThreadSafe
	public interface PurchaseResponseFactory {
		@NonNull
		PurchaseResponse create(@NonNull Purchase purchase);
	}

	@AssistedInject
	public PurchaseResponse(@NonNull Provider<CurrentContext> currentContextProvider,
													@NonNull Formatter formatter,
													@Assisted @NonNull Purchase purchase) {
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

		this.purchaseId = purchase.purchaseId();
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
			@NonNull PurchaseResponse purchase
	) {
		public PurchaseResponseHolder {
			requireNonNull(purchase);
		}
	}

	@NonNull
	public UUID getPurchaseId() {
		return this.purchaseId;
	}

	@NonNull
	public UUID getAccountId() {
		return this.accountId;
	}

	@NonNull
	public UUID getToyId() {
		return this.toyId;
	}

	@NonNull
	public BigDecimal getPrice() {
		return this.price;
	}

	@NonNull
	public String getPriceDescription() {
		return this.priceDescription;
	}

	@NonNull
	public String getCurrencyCode() {
		return this.currencyCode;
	}

	@NonNull
	public String getCurrencySymbol() {
		return this.currencySymbol;
	}

	@NonNull
	public String getCurrencyDescription() {
		return this.currencyDescription;
	}

	@NonNull
	public String getCreditCardTransactionId() {
		return this.creditCardTransactionId;
	}

	@NonNull
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@NonNull
	public String getCreatedAtDescription() {
		return this.createdAtDescription;
	}
}

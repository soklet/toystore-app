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
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.util.Formatter;
import com.soklet.toystore.util.Formatter.DateTimeFormatterConfig;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Public-facing representation of a {@link Toy}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyResponse {
	@NonNull
	private final UUID toyId;
	@NonNull
	private final String name;
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
	private final Instant createdAt;
	@NonNull
	private final String createdAtDescription;

	@ThreadSafe
	public interface ToyResponseFactory {
		@NonNull
		ToyResponse create(@NonNull Toy toy);
	}

	@AssistedInject
	public ToyResponse(@NonNull Provider<CurrentContext> currentContextProvider,
										 @NonNull Formatter formatter,
										 @Assisted @NonNull Toy toy) {
		requireNonNull(currentContextProvider);
		requireNonNull(formatter);
		requireNonNull(toy);

		// Tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getLocale();
		ZoneId currentTimeZone = currentContext.getTimeZone();
		NumberFormat currencyFormatter = formatter.currencyFormatter(currentLocale, toy.currency());
		DateTimeFormatter dateTimeFormatter = formatter.dateTimeFormatter(
				new DateTimeFormatterConfig(currentLocale, currentTimeZone, FormatStyle.MEDIUM, FormatStyle.SHORT)
		);

		this.toyId = toy.toyId();
		this.name = toy.name();
		this.price = toy.price();
		this.priceDescription = currencyFormatter.format(toy.price());
		this.currencyCode = toy.currency().getCurrencyCode();
		this.currencySymbol = toy.currency().getSymbol(currentLocale);
		this.currencyDescription = toy.currency().getDisplayName(currentLocale);
		this.createdAt = toy.createdAt();
		this.createdAtDescription = dateTimeFormatter.format(toy.createdAt());
	}

	public record ToyResponseHolder(
			@NonNull ToyResponse toy
	) {
		public ToyResponseHolder {
			requireNonNull(toy);
		}
	}

	public record ToysResponseHolder(
			@NonNull List<@NonNull ToyResponse> toys
	) {
		public ToysResponseHolder {
			requireNonNull(toys);
		}
	}

	@NonNull
	public UUID getToyId() {
		return this.toyId;
	}

	@NonNull
	public String getName() {
		return this.name;
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
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@NonNull
	public String getCreatedAtDescription() {
		return this.createdAtDescription;
	}
}

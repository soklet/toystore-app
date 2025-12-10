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

import javax.annotation.Nonnull;
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
	@Nonnull
	private final UUID toyId;
	@Nonnull
	private final String name;
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
	private final Instant createdAt;
	@Nonnull
	private final String createdAtDescription;

	@ThreadSafe
	public interface ToyResponseFactory {
		@Nonnull
		ToyResponse create(@Nonnull Toy toy);
	}

	@AssistedInject
	public ToyResponse(@Nonnull Provider<CurrentContext> currentContextProvider,
										 @Assisted @Nonnull Toy toy) {
		requireNonNull(currentContextProvider);
		requireNonNull(toy);

		// Tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getLocale();
		ZoneId currentTimeZone = currentContext.getTimeZone();

		// A real application would cache this formatter in a threadsafe way
		NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(currentLocale);
		currencyFormatter.setCurrency(toy.currency());

		this.toyId = toy.toyId();
		this.name = toy.name();
		this.price = toy.price();
		this.priceDescription = currencyFormatter.format(toy.price());
		this.currencyCode = toy.currency().getCurrencyCode();
		this.currencySymbol = toy.currency().getSymbol(currentLocale);
		this.currencyDescription = toy.currency().getDisplayName(currentLocale);
		this.createdAt = toy.createdAt();

		// A real application would cache this formatter
		this.createdAtDescription = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.localizedBy(currentLocale)
				.withZone(currentTimeZone)
				.format(toy.createdAt());
	}

	public record ToyResponseHolder(
			@Nonnull ToyResponse toy
	) {
		public ToyResponseHolder {
			requireNonNull(toy);
		}
	}

	public record ToysResponseHolder(
			@Nonnull List<ToyResponse> toys
	) {
		public ToysResponseHolder {
			requireNonNull(toys);
		}
	}

	@Nonnull
	public UUID getToyId() {
		return this.toyId;
	}

	@Nonnull
	public String getName() {
		return this.name;
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
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@Nonnull
	public String getCreatedAtDescription() {
		return this.createdAtDescription;
	}
}
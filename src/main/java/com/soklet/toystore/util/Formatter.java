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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Singleton;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.math.RoundingMode;
import java.text.Collator;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Provides safe and efficient access to formatters via bounded (LRU) caches - necessary to avoid expensive construction of {@link NumberFormat} instances, for example.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Singleton
@ThreadSafe
public class Formatter {
	@Nonnull
	private static final Cache<DateTimeFormatterConfig, DateTimeFormatter> DATE_TIME_FORMATTERS_BY_CONFIG;
	@Nonnull
	private static final Cache<IntegerFormatterConfig, NumberFormat> INTEGER_FORMATTERS_BY_CONFIG;
	@Nonnull
	private static final Cache<NumberFormatterConfig, NumberFormat> NUMBER_FORMATTERS_BY_CONFIG;
	@Nonnull
	private static final Cache<CurrencyFormatterConfig, NumberFormat> CURRENCY_FORMATTERS_BY_CONFIG;
	@Nonnull
	private static final Cache<NumberFormatterConfig, NumberFormat> PERCENT_FORMATTERS_BY_CONFIG;
	@Nonnull
	private static final Cache<Locale, Collator> COLLATORS_BY_LOCALE;

	public record DateTimeFormatterConfig(
			@Nonnull Locale locale,
			@Nonnull ZoneId timeZone,
			@Nullable FormatStyle dateFormatStyle,
			@Nullable FormatStyle timeFormatStyle
	) {
		public DateTimeFormatterConfig {
			requireNonNull(locale);
			requireNonNull(timeZone);
		}

		@Nonnull
		public static DateTimeFormatterConfig forLocaleAndTimeZone(@Nonnull Locale locale,
																															 @Nonnull ZoneId timeZone) {
			requireNonNull(locale);
			requireNonNull(timeZone);

			return new DateTimeFormatterConfig(locale, timeZone, null, null);
		}
	}

	public record IntegerFormatterConfig(
			@Nonnull Locale locale,
			@Nullable Integer minimumIntegerDigits,
			@Nullable Integer maximumIntegerDigits,
			@Nullable Boolean groupingUsed
	) {
		public IntegerFormatterConfig {
			requireNonNull(locale);
		}

		@Nonnull
		public static IntegerFormatterConfig forLocale(@Nonnull Locale locale) {
			requireNonNull(locale);
			return new IntegerFormatterConfig(locale, null, null, null);
		}
	}

	public record NumberFormatterConfig(
			@Nonnull Locale locale,
			@Nullable Integer minimumIntegerDigits,
			@Nullable Integer maximumIntegerDigits,
			@Nullable Integer minimumFractionDigits,
			@Nullable Integer maximumFractionDigits,
			@Nullable RoundingMode roundingMode,
			@Nullable Boolean groupingUsed
	) {
		public NumberFormatterConfig {
			requireNonNull(locale);
		}

		@Nonnull
		public static NumberFormatterConfig forLocale(@Nonnull Locale locale) {
			requireNonNull(locale);
			return new NumberFormatterConfig(locale, null, null, null, null, null, null);
		}
	}

	public record CurrencyFormatterConfig(
			@Nonnull Locale locale,
			@Nonnull Currency currency,
			@Nullable Integer minimumIntegerDigits,
			@Nullable Integer maximumIntegerDigits,
			@Nullable Integer minimumFractionDigits,
			@Nullable Integer maximumFractionDigits,
			@Nullable RoundingMode roundingMode,
			@Nullable Boolean groupingUsed
	) {
		public CurrencyFormatterConfig {
			requireNonNull(locale);
			requireNonNull(currency);
		}

		@Nonnull
		public static CurrencyFormatterConfig forLocaleAndCurrency(@Nonnull Locale locale,
																															 @Nonnull Currency currency) {
			requireNonNull(locale);
			requireNonNull(currency);

			return new CurrencyFormatterConfig(locale, currency, null, null, null, null, null, null);
		}
	}

	static {
		// Caffeine caches with maximum size (LRU eviction is the default)
		DATE_TIME_FORMATTERS_BY_CONFIG = Caffeine.newBuilder()
				.maximumSize(8)
				.build();

		INTEGER_FORMATTERS_BY_CONFIG = Caffeine.newBuilder()
				.maximumSize(4)
				.build();

		NUMBER_FORMATTERS_BY_CONFIG = Caffeine.newBuilder()
				.maximumSize(4)
				.build();

		CURRENCY_FORMATTERS_BY_CONFIG = Caffeine.newBuilder()
				.maximumSize(2)
				.build();

		PERCENT_FORMATTERS_BY_CONFIG = Caffeine.newBuilder()
				.maximumSize(2)
				.build();

		COLLATORS_BY_LOCALE = Caffeine.newBuilder()
				.maximumSize(2)
				.build();
	}

	@Nonnull
	public DateTimeFormatter dateTimeFormatter(@Nonnull Locale locale,
																						 @Nonnull ZoneId timeZone) {
		requireNonNull(locale);
		requireNonNull(timeZone);

		return dateTimeFormatter(DateTimeFormatterConfig.forLocaleAndTimeZone(locale, timeZone));
	}

	@Nonnull
	public DateTimeFormatter dateTimeFormatter(@Nonnull DateTimeFormatterConfig dateTimeFormatterConfig) {
		requireNonNull(dateTimeFormatterConfig);

		return DATE_TIME_FORMATTERS_BY_CONFIG.get(dateTimeFormatterConfig, dtfc -> {

			FormatStyle dateFormatStyle = dtfc.dateFormatStyle() == null ? FormatStyle.MEDIUM : dtfc.dateFormatStyle();
			FormatStyle timeFormatStyle = dtfc.timeFormatStyle() == null ? FormatStyle.SHORT : dtfc.timeFormatStyle();

			return DateTimeFormatter.ofLocalizedDateTime(dateFormatStyle, timeFormatStyle)
					.withLocale(dtfc.locale())
					.withZone(dtfc.timeZone());
		});
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat integerFormatter(@Nonnull Locale locale) {
		requireNonNull(locale);
		return integerFormatter(IntegerFormatterConfig.forLocale(locale));
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat integerFormatter(@Nonnull IntegerFormatterConfig integerFormatterConfig) {
		requireNonNull(integerFormatterConfig);

		// Cloning a cached instance is much faster than creating a new instance.
		// This ensures callers get a "fast" and safe (non-shared; NumberFormat is not threadsafe) copy.
		return (NumberFormat) INTEGER_FORMATTERS_BY_CONFIG.get(integerFormatterConfig, (ifc) -> {
			NumberFormat integerFormatter = NumberFormat.getIntegerInstance(ifc.locale());

			if (ifc.minimumIntegerDigits() != null)
				integerFormatter.setMinimumIntegerDigits(ifc.minimumIntegerDigits());
			if (ifc.maximumIntegerDigits() != null)
				integerFormatter.setMaximumIntegerDigits(ifc.maximumIntegerDigits());
			if (ifc.groupingUsed() != null)
				integerFormatter.setGroupingUsed(ifc.groupingUsed());

			return integerFormatter;
		}).clone();
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat numberFormatter(@Nonnull Locale locale) {
		requireNonNull(locale);
		return numberFormatter(NumberFormatterConfig.forLocale(locale));
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat numberFormatter(@Nonnull NumberFormatterConfig numberFormatterConfig) {
		requireNonNull(numberFormatterConfig);

		// Cloning a cached instance is much faster than creating a new instance.
		// This ensures callers get a "fast" and safe (non-shared; NumberFormat is not threadsafe) copy.
		return (NumberFormat) NUMBER_FORMATTERS_BY_CONFIG.get(numberFormatterConfig, (nfc) -> {
			NumberFormat numberFormatter = NumberFormat.getNumberInstance(nfc.locale());

			if (nfc.minimumIntegerDigits() != null)
				numberFormatter.setMinimumIntegerDigits(nfc.minimumIntegerDigits());
			if (nfc.maximumIntegerDigits() != null)
				numberFormatter.setMaximumIntegerDigits(nfc.maximumIntegerDigits());
			if (nfc.minimumFractionDigits() != null)
				numberFormatter.setMinimumFractionDigits(nfc.minimumFractionDigits());
			if (nfc.maximumFractionDigits() != null)
				numberFormatter.setMaximumFractionDigits(nfc.maximumFractionDigits());
			if (nfc.roundingMode() != null)
				numberFormatter.setRoundingMode(nfc.roundingMode());
			if (nfc.groupingUsed() != null)
				numberFormatter.setGroupingUsed(nfc.groupingUsed());

			return numberFormatter;
		}).clone();
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat currencyFormatter(@Nonnull Locale locale,
																				@Nonnull Currency currency) {
		requireNonNull(locale);
		requireNonNull(currency);

		return currencyFormatter(CurrencyFormatterConfig.forLocaleAndCurrency(locale, currency));
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat currencyFormatter(@Nonnull CurrencyFormatterConfig currencyFormatterConfig) {
		requireNonNull(currencyFormatterConfig);

		// Cloning a cached instance is much faster than creating a new instance.
		// This ensures callers get a "fast" and safe (non-shared; NumberFormat is not threadsafe) copy.
		return (NumberFormat) CURRENCY_FORMATTERS_BY_CONFIG.get(currencyFormatterConfig, (cfc) -> {
			NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(cfc.locale());
			currencyFormatter.setCurrency(cfc.currency());

			if (cfc.minimumIntegerDigits() != null)
				currencyFormatter.setMinimumIntegerDigits(cfc.minimumIntegerDigits());
			if (cfc.maximumIntegerDigits() != null)
				currencyFormatter.setMaximumIntegerDigits(cfc.maximumIntegerDigits());
			if (cfc.minimumFractionDigits() != null)
				currencyFormatter.setMinimumFractionDigits(cfc.minimumFractionDigits());
			if (cfc.maximumFractionDigits() != null)
				currencyFormatter.setMaximumFractionDigits(cfc.maximumFractionDigits());
			if (cfc.roundingMode() != null)
				currencyFormatter.setRoundingMode(cfc.roundingMode());
			if (cfc.groupingUsed() != null)
				currencyFormatter.setGroupingUsed(cfc.groupingUsed());

			return currencyFormatter;
		}).clone();
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat percentFormatter(@Nonnull Locale locale) {
		requireNonNull(locale);
		return percentFormatter(NumberFormatterConfig.forLocale(locale));
	}

	/**
	 * Careful: {@link NumberFormat} is not threadsafe.
	 */
	@Nonnull
	public NumberFormat percentFormatter(@Nonnull NumberFormatterConfig numberFormatterConfig) {
		requireNonNull(numberFormatterConfig);

		// Cloning a cached instance is much faster than creating a new instance.
		// This ensures callers get a "fast" and safe (non-shared; NumberFormat is not threadsafe) copy.
		return (NumberFormat) PERCENT_FORMATTERS_BY_CONFIG.get(numberFormatterConfig, (nfc) -> {
			NumberFormat numberFormatter = NumberFormat.getPercentInstance(nfc.locale());

			if (nfc.minimumIntegerDigits() != null)
				numberFormatter.setMinimumIntegerDigits(nfc.minimumIntegerDigits());
			if (nfc.maximumIntegerDigits() != null)
				numberFormatter.setMaximumIntegerDigits(nfc.maximumIntegerDigits());
			if (nfc.minimumFractionDigits() != null)
				numberFormatter.setMinimumFractionDigits(nfc.minimumFractionDigits());
			if (nfc.maximumFractionDigits() != null)
				numberFormatter.setMaximumFractionDigits(nfc.maximumFractionDigits());
			if (nfc.roundingMode() != null)
				numberFormatter.setRoundingMode(nfc.roundingMode());
			if (nfc.groupingUsed() != null)
				numberFormatter.setGroupingUsed(nfc.groupingUsed());

			return numberFormatter;
		}).clone();
	}

	/**
	 * Careful: {@link Collator} is not threadsafe.
	 */
	@Nonnull
	public Collator collator(@Nonnull Locale locale) {
		requireNonNull(locale);

		// Cloning a cached instance is much faster than creating a new instance.
		// This ensures callers get a "fast" and safe (non-shared; Collator is not threadsafe) copy.
		return (Collator) COLLATORS_BY_LOCALE.get(locale, (localeKey) -> Collator.getInstance(localeKey)).clone();
	}
}

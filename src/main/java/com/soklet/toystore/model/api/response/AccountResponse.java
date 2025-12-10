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
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.model.db.Role.RoleId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Public-facing representation of an {@link Account}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountResponse {
	@Nonnull
	private final UUID accountId;
	@Nonnull
	private final RoleId roleId;
	@Nonnull
	private final String name;
	@Nullable
	private final String emailAddress;
	@Nonnull
	private final ZoneId timeZone;
	@Nonnull
	private final String timeZoneDescription;
	@Nonnull
	private final Locale locale;
	@Nonnull
	private final String localeDescription;
	@Nonnull
	private final Instant createdAt;
	@Nonnull
	private final String createdAtDescription;

	@ThreadSafe
	public interface AccountResponseFactory {
		@Nonnull
		AccountResponse create(@Nonnull Account account);
	}

	@AssistedInject
	public AccountResponse(@Nonnull Provider<CurrentContext> currentContextProvider,
												 @Assisted @Nonnull Account account) {
		requireNonNull(currentContextProvider);
		requireNonNull(account);

		// Tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getLocale();
		ZoneId currentTimeZone = currentContext.getTimeZone();

		this.accountId = account.accountId();
		this.roleId = account.roleId();
		this.name = account.name();
		this.emailAddress = account.emailAddress();
		this.locale = account.locale();
		this.localeDescription = this.locale.getDisplayName(currentLocale);
		this.timeZone = account.timeZone();
		this.timeZoneDescription = this.timeZone.getDisplayName(TextStyle.FULL, currentLocale);
		this.createdAt = account.createdAt();

		// A real application would cache this formatter
		this.createdAtDescription = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.localizedBy(currentLocale)
				.withZone(currentTimeZone)
				.format(account.createdAt());
	}

	@Nonnull
	public UUID getAccountId() {
		return this.accountId;
	}

	@Nonnull
	public RoleId getRoleId() {
		return this.roleId;
	}

	@Nonnull
	public String getName() {
		return this.name;
	}

	@Nonnull
	public Optional<String> getEmailAddress() {
		return Optional.ofNullable(this.emailAddress);
	}

	@Nonnull
	public ZoneId getTimeZone() {
		return this.timeZone;
	}

	@Nonnull
	public String getTimeZoneDescription() {
		return this.timeZoneDescription;
	}

	@Nonnull
	public Locale getLocale() {
		return this.locale;
	}

	@Nonnull
	public String getLocaleDescription() {
		return this.localeDescription;
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
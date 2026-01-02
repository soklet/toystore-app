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
import com.soklet.toystore.util.Formatter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
	@NonNull
	private final UUID accountId;
	@NonNull
	private final RoleId roleId;
	@NonNull
	private final String name;
	@Nullable
	private final String emailAddress;
	@NonNull
	private final ZoneId timeZone;
	@NonNull
	private final String timeZoneDescription;
	@NonNull
	private final Locale locale;
	@NonNull
	private final String localeDescription;
	@NonNull
	private final Instant createdAt;
	@NonNull
	private final String createdAtDescription;

	@ThreadSafe
	public interface AccountResponseFactory {
		@NonNull
		AccountResponse create(@NonNull Account account);
	}

	@AssistedInject
	public AccountResponse(@NonNull Provider<CurrentContext> currentContextProvider,
												 @NonNull Formatter formatter,
												 @Assisted @NonNull Account account) {
		requireNonNull(currentContextProvider);
		requireNonNull(formatter);
		requireNonNull(account);

		// Tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getLocale();
		ZoneId currentTimeZone = currentContext.getTimeZone();
		DateTimeFormatter dateTimeFormatter = formatter.dateTimeFormatter(
				new Formatter.DateTimeFormatterConfig(currentLocale, currentTimeZone, FormatStyle.MEDIUM, FormatStyle.SHORT)
		);

		this.accountId = account.accountId();
		this.roleId = account.roleId();
		this.name = account.name();
		this.emailAddress = account.emailAddress();
		this.locale = account.locale();
		this.localeDescription = this.locale.getDisplayName(currentLocale);
		this.timeZone = account.timeZone();
		this.timeZoneDescription = this.timeZone.getDisplayName(TextStyle.FULL, currentLocale);
		this.createdAt = account.createdAt();
		this.createdAtDescription = dateTimeFormatter.format(account.createdAt());
	}

	@NonNull
	public UUID getAccountId() {
		return this.accountId;
	}

	@NonNull
	public RoleId getRoleId() {
		return this.roleId;
	}

	@NonNull
	public String getName() {
		return this.name;
	}

	@NonNull
	public Optional<String> getEmailAddress() {
		return Optional.ofNullable(this.emailAddress);
	}

	@NonNull
	public ZoneId getTimeZone() {
		return this.timeZone;
	}

	@NonNull
	public String getTimeZoneDescription() {
		return this.timeZoneDescription;
	}

	@NonNull
	public Locale getLocale() {
		return this.locale;
	}

	@NonNull
	public String getLocaleDescription() {
		return this.localeDescription;
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
/*
 * Copyright 2022-2023 Revetware LLC.
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

package com.soklet.example.service;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.soklet.example.CurrentContext;
import com.soklet.example.exception.ApplicationException;
import com.soklet.example.model.api.request.AccountAuthenticateRequest;
import com.soklet.example.model.auth.AccountJwt;
import com.soklet.example.model.db.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountService {
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;
	@Nonnull
	private final Database database;
	@Nonnull
	private final Strings strings;
	@Nonnull
	private final Logger logger;

	@Inject
	public AccountService(@Nonnull Provider<CurrentContext> currentContextProvider,
												@Nonnull Database database,
												@Nonnull Strings strings) {
		requireNonNull(currentContextProvider);
		requireNonNull(database);
		requireNonNull(strings);

		this.currentContextProvider = currentContextProvider;
		this.database = database;
		this.strings = strings;
		this.logger = LoggerFactory.getLogger(getClass());
	}

	@Nonnull
	public Optional<Account> findAccountById(@Nullable UUID accountId) {
		if (accountId == null)
			return Optional.empty();

		return getDatabase().queryForObject("""
				SELECT *
				FROM account
				WHERE account_id=?
				""", Account.class, accountId);
	}

	@Nonnull
	public AccountJwt authenticateAccount(@Nonnull AccountAuthenticateRequest request) {
		requireNonNull(request);

		String emailAddress = request.emailAddress() == null ? "" : request.emailAddress().trim();
		Map<String, String> fieldErrors = new LinkedHashMap<>();

		if (emailAddress.length() == 0)
			fieldErrors.put("emailAddress", getStrings().get("Email address is required."));

		if (fieldErrors.size() > 0)
			throw ApplicationException.withStatusCode(422)
					.fieldErrors(fieldErrors)
					.build();

		Account account = getDatabase().executeForObject("""
				SELECT *
				FROM account
				WHERE email_address=LOWER(?)
				""", Account.class, emailAddress.toLowerCase(Locale.US)).orElse(null);

		if (account == null)
			throw ApplicationException.withStatusCode(401)
					.error(getStrings().get("Sorry, we could not authenticate you."))
					.build();

		Instant expiration = Instant.now().plus(10, ChronoUnit.MINUTES);

		return new AccountJwt(account.accountId(), expiration);
	}

	@Nonnull
	public Optional<Account> findAccountByJwt(@Nullable AccountJwt accountJwt) {
		if (accountJwt == null)
			return Optional.empty();

		// Validate JWT
		if (Instant.now().isAfter(accountJwt.expiration())) {
			String expirationDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
					.localizedBy(getCurrentContext().getPreferredLocale())
					.withZone(getCurrentContext().getPreferredTimeZone())
					.format(accountJwt.expiration());

			String error = getStrings().get("Your authentication token expired on {{expirationDateTime}} - please re-authenticate.",
					Map.of("expirationDateTime", expirationDateTime));

			throw ApplicationException.withStatusCode(401)
					.error(error)
					.metadata(Map.of(
							"reasonCode", "AUTHENTICATION_TOKEN_EXPIRED",
							"expirationDateTime", expirationDateTime
					))
					.build();
		}

		return findAccountById(accountJwt.accountId());
	}

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	@Nonnull
	protected Database getDatabase() {
		return this.database;
	}

	@Nonnull
	protected Strings getStrings() {
		return this.strings;
	}

	@Nonnull
	protected Logger getLogger() {
		return this.logger;
	}
}
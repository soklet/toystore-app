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

package com.soklet.example.service;

import com.google.inject.Inject;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.soklet.example.exception.ApplicationException;
import com.soklet.example.exception.ApplicationException.ErrorCollector;
import com.soklet.example.model.api.request.AccountAuthenticateRequest;
import com.soklet.example.model.auth.AccountJwt;
import com.soklet.example.model.db.Account;
import com.soklet.example.util.PasswordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Business logic for accounts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountService {
	@Nonnull
	private final PasswordManager passwordManager;
	@Nonnull
	private final Database database;
	@Nonnull
	private final Strings strings;
	@Nonnull
	private final Logger logger;

	@Inject
	public AccountService(@Nonnull PasswordManager passwordManager,
												@Nonnull Database database,
												@Nonnull Strings strings) {
		requireNonNull(passwordManager);
		requireNonNull(database);
		requireNonNull(strings);

		this.passwordManager = passwordManager;
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
		String password = request.password() == null ? "" : request.password().trim();
		ErrorCollector errorCollector = new ErrorCollector();

		if (emailAddress.length() == 0)
			errorCollector.addFieldError("emailAddress", getStrings().get("Email address is required."));

		if (password.length() == 0)
			errorCollector.addFieldError("password", getStrings().get("Password is required."));

		if (errorCollector.hasErrors())
			throw ApplicationException.withStatusCodeAndErrors(422, errorCollector).build();

		Account account = getDatabase().executeForObject("""
				SELECT *
				FROM account
				WHERE email_address=?
				""", Account.class, emailAddress.toLowerCase(Locale.US)).orElse(null);

		// Reject if no account, or account's hashed password does not match
		if (account == null || !getPasswordManager().verifyPassword(password, account.passwordHash()))
			throw ApplicationException.withStatusCode(401)
					.generalError(getStrings().get("Sorry, we could not authenticate you."))
					.build();

		// Generate a JWT
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(60, ChronoUnit.MINUTES);
		return new AccountJwt(account.accountId(), issuedAt, expiresAt);
	}

	@Nonnull
	private PasswordManager getPasswordManager() {
		return this.passwordManager;
	}

	@Nonnull
	private Database getDatabase() {
		return this.database;
	}

	@Nonnull
	private Strings getStrings() {
		return this.strings;
	}

	@Nonnull
	private Logger getLogger() {
		return this.logger;
	}
}
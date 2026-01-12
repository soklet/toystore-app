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

package com.soklet.toystore.service;

import com.google.inject.Inject;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.exception.ApplicationException;
import com.soklet.toystore.exception.ApplicationException.ErrorCollector;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.util.PasswordManager;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.soklet.toystore.util.Normalizer.normalizeEmailAddress;
import static com.soklet.toystore.util.Normalizer.trimAggressivelyToNull;
import static com.soklet.toystore.util.Validator.isValidEmailAddress;
import static java.util.Objects.requireNonNull;

/**
 * Business logic for accounts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountService {
	@NonNull
	private final Configuration configuration;
	@NonNull
	private final PasswordManager passwordManager;
	@NonNull
	private final Database database;
	@NonNull
	private final Strings strings;
	@NonNull
	private final Logger logger;

	@Inject
	public AccountService(@NonNull Configuration configuration,
												@NonNull PasswordManager passwordManager,
												@NonNull Database database,
												@NonNull Strings strings) {
		requireNonNull(configuration);
		requireNonNull(passwordManager);
		requireNonNull(database);
		requireNonNull(strings);

		this.configuration = configuration;
		this.passwordManager = passwordManager;
		this.database = database;
		this.strings = strings;
		this.logger = LoggerFactory.getLogger(getClass());
	}

	@NonNull
	public Optional<Account> findAccountById(@Nullable UUID accountId) {
		if (accountId == null)
			return Optional.empty();

		return getDatabase().query("""
						SELECT *
						FROM account
						WHERE account_id=:accountId
						""")
				.bind("accountId", accountId)
				.fetchObject(Account.class);
	}

	@NonNull
	public AccessToken authenticateAccount(@NonNull AccountAuthenticateRequest request) {
		requireNonNull(request);

		String emailAddress = trimAggressivelyToNull(request.emailAddress());
		String password = trimAggressivelyToNull(request.password());
		ErrorCollector errorCollector = new ErrorCollector();

		if (emailAddress == null)
			errorCollector.addFieldError("emailAddress", getStrings().get("Email address is required."));
		else if (!isValidEmailAddress(emailAddress))
			errorCollector.addFieldError("emailAddress", getStrings().get("Email address is invalid."));

		if (password == null)
			errorCollector.addFieldError("password", getStrings().get("Password is required."));

		if (errorCollector.hasErrors())
			throw ApplicationException.fromStatusCodeAndErrors(422, errorCollector);

		String normalizedEmailAddress = normalizeEmailAddress(emailAddress).orElseThrow();

		Account account = getDatabase().query("""
						SELECT *
						FROM account
						WHERE email_address=:emailAddress
						""")
				.bind("emailAddress", normalizedEmailAddress)
				.fetchObject(Account.class)
				.orElse(null);

		// Reject if no account, or account's hashed password does not match
		if (account == null || !getPasswordManager().verifyPassword(password, account.passwordHash()))
			throw ApplicationException.withStatusCode(401)
					.generalError(getStrings().get("Sorry, we could not authenticate you."))
					.build();

		// Generate a JWT
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(getConfiguration().getAccessTokenExpiration());

		return new AccessToken(
				account.accountId(),
				issuedAt,
				expiresAt,
				Audience.API,
				Set.of(
						Scope.API_READ,
						Scope.API_WRITE
				)
		);
	}

	@NonNull
	private Configuration getConfiguration() {
		return this.configuration;
	}

	@NonNull
	private PasswordManager getPasswordManager() {
		return this.passwordManager;
	}

	@NonNull
	private Database getDatabase() {
		return this.database;
	}

	@NonNull
	private Strings getStrings() {
		return this.strings;
	}

	@NonNull
	private Logger getLogger() {
		return this.logger;
	}
}

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

package com.soklet.toystore.resource;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.soklet.annotation.POST;
import com.soklet.annotation.RequestBody;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.annotation.AuthorizationRequired;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.api.response.AccountResponse;
import com.soklet.toystore.model.api.response.AccountResponse.AccountResponseFactory;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.service.AccountService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Contains Account-related Resource Methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountResource {
	@Nonnull
	private final AccountService accountService;
	@Nonnull
	private final AccountResponseFactory accountResponseFactory;
	@Nonnull
	private final Configuration configuration;
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public AccountResource(@Nonnull AccountService accountService,
												 @Nonnull AccountResponseFactory accountResponseFactory,
												 @Nonnull Configuration configuration,
												 @Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(accountService);
		requireNonNull(accountResponseFactory);
		requireNonNull(configuration);
		requireNonNull(currentContextProvider);

		this.accountService = accountService;
		this.accountResponseFactory = accountResponseFactory;
		this.configuration = configuration;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@POST("/accounts/authenticate")
	public AccountAuthenticateReponseHolder authenticate(@Nonnull @RequestBody AccountAuthenticateRequest request) {
		requireNonNull(request);

		// Authenticate the email/password, and pull the account information from the JWT assertion
		AccessToken accessToken = getAccountService().authenticateAccount(request);
		Account account = getAccountService().findAccountById(accessToken.accountId()).get();

		// Return both account data and a JWT that authenticates it to the client
		return new AccountAuthenticateReponseHolder(accessToken, getAccountResponseFactory().create(account));
	}

	public record AccountAuthenticateReponseHolder(
			@Nonnull AccessToken authenticationToken,
			@Nonnull AccountResponse account
	) {
		public AccountAuthenticateReponseHolder {
			requireNonNull(authenticationToken);
			requireNonNull(account);
		}
	}

	@Nonnull
	@AuthorizationRequired
	@POST("/accounts/sse-access-token")
	public SseAccessTokenResponseHolder acquireSseAccessToken() {
		CurrentContext currentContext = getCurrentContext();
		Account account = currentContext.getAccount().get();
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(getConfiguration().getSseAccessTokenExpiration());

		AccessToken sseAccessToken = new AccessToken(
				account.accountId(),
				issuedAt,
				expiresAt,
				Audience.SSE,
				Set.of(Scope.SSE_HANDSHAKE)
		);

		return new SseAccessTokenResponseHolder(sseAccessToken);
	}

	public record SseAccessTokenResponseHolder(
			@Nonnull AccessToken accessToken
	) {
		public SseAccessTokenResponseHolder {
			requireNonNull(accessToken);
		}
	}

	@Nonnull
	private AccountService getAccountService() {
		return this.accountService;
	}

	@Nonnull
	private AccountResponseFactory getAccountResponseFactory() {
		return this.accountResponseFactory;
	}

	@Nonnull
	private Configuration getConfiguration() {
		return this.configuration;
	}

	@Nonnull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}

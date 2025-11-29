/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example.resource;

import com.google.inject.Inject;
import com.soklet.annotation.POST;
import com.soklet.annotation.RequestBody;
import com.soklet.example.model.api.request.AccountAuthenticateRequest;
import com.soklet.example.model.api.response.AccountResponse;
import com.soklet.example.model.api.response.AccountResponse.AccountResponseFactory;
import com.soklet.example.model.auth.AccountJwt;
import com.soklet.example.model.db.Account;
import com.soklet.example.service.AccountService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountResource {
	@Nonnull
	private final AccountService accountService;
	@Nonnull
	private final AccountResponseFactory accountResponseFactory;

	@Inject
	public AccountResource(@Nonnull AccountService accountService,
												 @Nonnull AccountResponseFactory accountResponseFactory) {
		requireNonNull(accountService);
		requireNonNull(accountResponseFactory);

		this.accountService = accountService;
		this.accountResponseFactory = accountResponseFactory;
	}

	@Nonnull
	@POST("/accounts/authenticate")
	public AccountAuthenticateReponseHolder authenticate(@Nonnull @RequestBody AccountAuthenticateRequest request) {
		requireNonNull(request);

		// Authenticate the email/password, and pull the account information from the JWT assertion
		AccountJwt accountJwt = getAccountService().authenticateAccount(request);
		Account account = getAccountService().findAccountById(accountJwt.accountId()).get();

		// Return both account data and a JWT that authenticates it to the client
		return new AccountAuthenticateReponseHolder(accountJwt, getAccountResponseFactory().create(account));
	}

	public record AccountAuthenticateReponseHolder(
			@Nonnull AccountJwt authenticationToken,
			@Nonnull AccountResponse account
	) {
		public AccountAuthenticateReponseHolder {
			requireNonNull(authenticationToken);
			requireNonNull(account);
		}
	}

	@Nonnull
	protected AccountService getAccountService() {
		return this.accountService;
	}

	@Nonnull
	protected AccountResponseFactory getAccountResponseFactory() {
		return this.accountResponseFactory;
	}
}
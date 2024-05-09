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

package com.soklet.example.resource;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.soklet.annotation.POST;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.Resource;
import com.soklet.example.Configuration;
import com.soklet.example.CurrentContext;
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
@Resource
@ThreadSafe
public class AccountResource {
	@Nonnull
	private final Configuration configuration;
	@Nonnull
	private final AccountService accountService;
	@Nonnull
	private final AccountResponseFactory accountResponseFactory;
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public AccountResource(@Nonnull Configuration configuration,
												 @Nonnull AccountService accountService,
												 @Nonnull AccountResponseFactory accountResponseFactory,
												 @Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(configuration);
		requireNonNull(accountService);
		requireNonNull(accountResponseFactory);
		requireNonNull(currentContextProvider);

		this.configuration = configuration;
		this.accountService = accountService;
		this.accountResponseFactory = accountResponseFactory;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@POST("/accounts/authenticate")
	public AccountAuthenticateReponse authenticate(@Nonnull @RequestBody AccountAuthenticateRequest request) {
		requireNonNull(request);

		AccountJwt accountJwt = getAccountService().authenticateAccount(request);
		Account account = getAccountService().findAccountByJwt(accountJwt).get();

		// Turn JWT into its string representation before returning to clients
		String jwt = accountJwt.toStringRepresentation(getConfiguration().getKeyPair().getPrivate());
		return new AccountAuthenticateReponse(jwt, getAccountResponseFactory().create(account));
	}

	public record AccountAuthenticateReponse(
			@Nonnull String jwt,
			@Nonnull AccountResponse account
	) {
		public AccountAuthenticateReponse {
			requireNonNull(jwt);
			requireNonNull(account);
		}
	}

	@Nonnull
	protected Configuration getConfiguration() {
		return this.configuration;
	}

	@Nonnull
	protected AccountService getAccountService() {
		return this.accountService;
	}

	@Nonnull
	protected AccountResponseFactory getAccountResponseFactory() {
		return this.accountResponseFactory;
	}

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}
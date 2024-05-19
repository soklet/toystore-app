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

import com.google.gson.Gson;
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.Resource;
import com.soklet.core.HttpMethod;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.example.App;
import com.soklet.example.Configuration;
import com.soklet.example.CurrentContext;
import com.soklet.example.model.api.request.AccountAuthenticateRequest;
import com.soklet.example.model.api.request.ToyCreateRequest;
import com.soklet.example.model.api.response.ErrorResponse;
import com.soklet.example.model.auth.AccountJwt;
import com.soklet.example.resource.ToyResource.ToyResponseHolder;
import com.soklet.example.service.AccountService;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Resource
@ThreadSafe
public class ToyResourceTests {
	@Test
	public void testCreateToy() {
		App app = new App(new Configuration());
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfiguration config = app.getInjector().getInstance(SokletConfiguration.class);

		Soklet.runSimulator(config, (simulator -> {
			// Get an auth token so we can provide to API calls
			String authenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "test123");

			// Create a toy by calling the API
			String name = "Example Toy";
			BigDecimal price = BigDecimal.valueOf(24.99);
			Currency currency = Currency.getInstance("GBP");

			String requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			Request request = Request.with(HttpMethod.POST, "/toys")
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Bad status code", 200, marshaledResponse.getStatusCode().intValue());

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder response = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Verify that the toy was created and looks like we expect
			Assert.assertEquals("Name doesn't match", name, response.toy().getName());
			Assert.assertEquals("Price doesn't match", price, response.toy().getPrice());
			Assert.assertEquals("Currency doesn't match", currency.getCurrencyCode(), response.toy().getCurrencyCode());

			// Try to create the same toy again and verify that the backend prevents it
			request = Request.with(HttpMethod.POST, "/toys")
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Bad status code", 422, marshaledResponse.getStatusCode().intValue());

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ErrorResponse errorResponse = gson.fromJson(responseBody, ErrorResponse.class);

			Assert.assertTrue("Error response was missing a 'name' field error message",
					errorResponse.getFieldErrors().keySet().contains("name"));
		}));
	}

	@Nonnull
	protected String acquireAuthenticationToken(@Nonnull App app,
																							@Nonnull String emailAddress,
																							@Nonnull String password) {
		requireNonNull(app);
		requireNonNull(emailAddress);
		requireNonNull(password);

		AccountService accountService = app.getInjector().getInstance(AccountService.class);

		// Hold reference to data inside of the closure.
		// Alternatively, CurrentContext::run could accept a Callable<T> to return values
		AtomicReference<String> holder = new AtomicReference<>();

		CurrentContext.empty().run(() -> {
			// Ask the backend for an authentication token
			AccountAuthenticateRequest accountAuthenticateRequest = new AccountAuthenticateRequest(emailAddress, password);
			AccountJwt accountJwt = accountService.authenticateAccount(accountAuthenticateRequest);
			String authenticationToken = accountJwt.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			// "Warp" the token outside the closure so it can be returned
			holder.set(authenticationToken);
		});

		return holder.get();
	}
}
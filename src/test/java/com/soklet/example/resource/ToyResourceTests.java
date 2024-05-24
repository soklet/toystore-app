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
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
import com.soklet.example.resource.ToyResource.PurchaseResponseHolder;
import com.soklet.example.resource.ToyResource.ToyResponseHolder;
import com.soklet.example.service.AccountService;
import com.soklet.example.util.CreditCardProcessor;
import com.soklet.example.util.CreditCardProcessor.CreditCardPaymentFailureReason;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
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

	@Test
	public void testPurchaseToyWithDeclinedCreditCard() {
		// Run the entire app, but use a special credit card processor that declines in certain scenarios.
		// Our app is using Guice for Dependency Injection, which enables these kinds of "surgical" overrides.
		// See https://github.com/google/guice/wiki/GettingStarted for details.
		App app = new App(new Configuration(), new AbstractModule() {
			@Nonnull
			@Provides
			@Singleton
			public CreditCardProcessor provideCreditCardProcessor() {
				// Our custom processor for testing decline scenarios
				return new CreditCardProcessor() {
					@Nonnull
					@Override
					public String makePayment(@Nonnull String creditCardNumber,
																		@Nonnull BigDecimal amount,
																		@Nonnull Currency currency) throws CreditCardPaymentException {
						// Anything over USD$100 exceeds this card's limit
						if (amount.compareTo(BigDecimal.valueOf(100)) > 0 && currency.getCurrencyCode().equals("USD"))
							throw new CreditCardPaymentException(CreditCardPaymentFailureReason.DECLINED);

						return format("fake-%s", UUID.randomUUID());
					}
				};
			}

			@Override
			protected void configure() {
				// Guice module configuration; nothing to do
			}
		});

		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfiguration config = app.getInjector().getInstance(SokletConfiguration.class);

		Soklet.runSimulator(config, (simulator -> {
			// Get an auth token so we can provide to API calls
			String authenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "test123");

			// Create an expensive toy by calling the API
			String name = "Expensive Toy";
			BigDecimal price = BigDecimal.valueOf(150.00);
			Currency currency = Currency.getInstance("USD");

			String requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			Request request = Request.with(HttpMethod.POST, "/toys")
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Expensive toy creation failed", 200, marshaledResponse.getStatusCode().intValue());

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder expensiveToyResponse = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Keep a handle to the expensive toy so we can try to purchase it
			UUID expensiveToyId = expensiveToyResponse.toy().getToyId();

			// Now, try to purchase the expensive toy and verify that the backend indicates a CC decline
			request = Request.with(HttpMethod.POST, format("/toys/%s/purchase", expensiveToyId))
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(gson.toJson(Map.of(
							"creditCardNumber", "4111111111111111",
							"creditCardExpiration", "2030-01"
					)).getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Expensive toy purchase did not fail as expected", 422, marshaledResponse.getStatusCode().intValue());

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ErrorResponse errorResponse = gson.fromJson(responseBody, ErrorResponse.class);

			// Ensure the metadata returned in the API response says that the card was declined
			Assert.assertTrue("Expensive toy error response was missing 'declined' metadata",
					Objects.equals(CreditCardPaymentFailureReason.DECLINED.name(), errorResponse.getMetadata().get("failureReason")));

			// Now, we create a cheap toy and verify that we don't get declined.
			// First, create a toy by calling the API
			name = "Cheap Toy";
			price = BigDecimal.valueOf(1.99);
			currency = Currency.getInstance("USD");

			requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			request = Request.with(HttpMethod.POST, "/toys")
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Cheap toy creation failed", 200, marshaledResponse.getStatusCode().intValue());

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder cheapToyResponse = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Keep a handle to the cheap toy so we can try to purchase it
			UUID cheapToyId = cheapToyResponse.toy().getToyId();

			// Now, try to purchase the cheap toy and verify that we don't get declined
			request = Request.with(HttpMethod.POST, format("/toys/%s/purchase", cheapToyId))
					.headers(Map.of("X-Authentication-Token", Set.of(authenticationToken)))
					.body(gson.toJson(Map.of(
							"creditCardNumber", "4111111111111111",
							"creditCardExpiration", "2030-01"
					)).getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Cheap toy purchase did not succeed", 200, marshaledResponse.getStatusCode().intValue());

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			PurchaseResponseHolder purchaseResponseHolder = gson.fromJson(responseBody, PurchaseResponseHolder.class);

			Assert.assertEquals("Cheap toy purchase amount mismatch", price, purchaseResponseHolder.purchase().getPrice());
			Assert.assertEquals("Cheap toy purchase currency mismatch", currency.getCurrencyCode(), purchaseResponseHolder.purchase().getCurrencyCode());
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
		AtomicReference<String> holder = new AtomicReference<>();

		CurrentContext.with(Locale.US, ZoneId.of("America/New_York")).run(() -> {
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
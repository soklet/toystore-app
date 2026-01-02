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

package com.soklet.toystore.resource;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.ServerSentEvent;
import com.soklet.ServerSentEventRequestResult;
import com.soklet.ServerSentEventRequestResult.HandshakeAccepted;
import com.soklet.ServerSentEventRequestResult.HandshakeRejected;
import com.soklet.ServerSentEventRequestResult.RequestFailed;
import com.soklet.Simulator;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.response.ErrorResponse;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.resource.AccountResource.SseAccessTokenResponseHolder;
import com.soklet.toystore.service.AccountService;
import com.soklet.toystore.util.CreditCardProcessor;
import com.soklet.toystore.util.CreditCardProcessor.CreditCardPaymentFailureReason;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
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
@ThreadSafe
public class ToyResourceTests {
	@Test
	public void testCreateToy() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, (simulator -> {
			// Get an auth token so we can provide to API calls
			String authenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "administrator-password");

			// Create a toy by calling the API
			String name = "Example Toy";
			BigDecimal price = BigDecimal.valueOf(24.99);
			Currency currency = Currency.getInstance("GBP");

			String requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			Request request = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Bad status code");

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder response = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Verify that the toy was created and looks like we expect
			Assertions.assertEquals(name, response.toy().getName(), "Name doesn't match");
			Assertions.assertEquals(price, response.toy().getPrice(), "Price doesn't match");
			Assertions.assertEquals(currency.getCurrencyCode(), response.toy().getCurrencyCode(), "Currency doesn't match");

			// Try to create the same toy again and verify that the backend prevents it
			request = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(422, marshaledResponse.getStatusCode().intValue(), "Bad status code");

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ErrorResponse errorResponse = gson.fromJson(responseBody, ErrorResponse.class);

			Assertions.assertTrue(errorResponse.getFieldErrors().keySet().contains("name"),
					"Error response was missing a 'name' field error message");
		}));
	}

	@Test
	public void testPurchaseToyWithDeclinedCreditCard() {
		// Run the entire app, but use a special credit card processor that declines in certain scenarios.
		// Our app is using Guice for Dependency Injection, which enables these kinds of "surgical" overrides.
		// See https://github.com/google/guice/wiki/GettingStarted for details.
		App app = new App(new Configuration("local"), new AbstractModule() {
			@NonNull
			@Provides
			@Singleton
			public CreditCardProcessor provideCreditCardProcessor() {
				// Our custom processor for testing decline scenarios
				return new CreditCardProcessor() {
					@NonNull
					@Override
					public String makePayment(@NonNull String creditCardNumber,
																		@NonNull BigDecimal amount,
																		@NonNull Currency currency) throws CreditCardPaymentException {
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
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, (simulator -> {
			// Get an auth token so we can provide to API calls
			String authenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "administrator-password");

			// Create an expensive toy by calling the API
			String name = "Expensive Toy";
			BigDecimal price = BigDecimal.valueOf(150.00);
			Currency currency = Currency.getInstance("USD");

			String requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			Request request = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Expensive toy creation failed");

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder expensiveToyResponse = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Keep a handle to the expensive toy so we can try to purchase it
			UUID expensiveToyId = expensiveToyResponse.toy().getToyId();

			// Now, try to purchase the expensive toy and verify that the backend indicates a CC decline
			request = Request.withPath(HttpMethod.POST, format("/toys/%s/purchase", expensiveToyId))
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(gson.toJson(Map.of(
							"creditCardNumber", "4111111111111111",
							"creditCardExpiration", "2030-01"
					)).getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(422, marshaledResponse.getStatusCode().intValue(), "Expensive toy purchase did not fail as expected");

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ErrorResponse errorResponse = gson.fromJson(responseBody, ErrorResponse.class);

			// Ensure the metadata returned in the API response says that the card was declined
			Assertions.assertTrue(Objects.equals(CreditCardPaymentFailureReason.DECLINED.name(), errorResponse.getMetadata().get("failureReason")),
					"Expensive toy error response was missing 'declined' metadata");

			// Now, we create a cheap toy and verify that we don't get declined.
			// First, create a toy by calling the API
			name = "Cheap Toy";
			price = BigDecimal.valueOf(1.99);
			currency = Currency.getInstance("USD");

			requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

			request = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Cheap toy creation failed");

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponseHolder cheapToyResponse = gson.fromJson(responseBody, ToyResponseHolder.class);

			// Keep a handle to the cheap toy so we can try to purchase it
			UUID cheapToyId = cheapToyResponse.toy().getToyId();

			// Now, try to purchase the cheap toy and verify that we don't get declined
			request = Request.withPath(HttpMethod.POST, format("/toys/%s/purchase", cheapToyId))
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(gson.toJson(Map.of(
							"creditCardNumber", "4111111111111111",
							"creditCardExpiration", "2030-01"
					)).getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Cheap toy purchase did not succeed");

			responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			PurchaseResponseHolder purchaseResponseHolder = gson.fromJson(responseBody, PurchaseResponseHolder.class);

			Assertions.assertEquals(price, purchaseResponseHolder.purchase().getPrice(), "Cheap toy purchase amount mismatch");
			Assertions.assertEquals(currency.getCurrencyCode(), purchaseResponseHolder.purchase().getCurrencyCode(), "Cheap toy purchase currency mismatch");
		}));
	}

	@NonNull
	private String acquireAuthenticationToken(@NonNull App app,
																						@NonNull String emailAddress,
																						@NonNull String password) {
		requireNonNull(app);
		requireNonNull(emailAddress);
		requireNonNull(password);

		AccountService accountService = app.getInjector().getInstance(AccountService.class);

		// Hold reference to data inside of the closure.
		AtomicReference<String> holder = new AtomicReference<>();

		CurrentContext.with(Locale.US, ZoneId.of("America/New_York")).build().run(() -> {
			// Ask the backend for an authentication token
			AccountAuthenticateRequest accountAuthenticateRequest = new AccountAuthenticateRequest(emailAddress, password);
			AccessToken accessToken = accountService.authenticateAccount(accountAuthenticateRequest);
			String authenticationToken = accessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			// "Warp" the token outside the closure so it can be returned
			holder.set(authenticationToken);
		});

		return holder.get();
	}

	@Test
	public void testLocalizationFromRequestHeaders() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, (simulator -> {
			String authenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "administrator-password");

			String requestBodyJson = gson.toJson(new ToyCreateRequest("Localized Toy", BigDecimal.valueOf(12.34), Currency.getInstance("USD")));

			Request createRequest = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			simulator.performRequest(createRequest);

			Request usRequest = Request.withPath(HttpMethod.GET, "/toys")
					.headers(Map.of(
							"Accept-Language", Set.of("en-US"),
							"Time-Zone", Set.of("America/New_York")
					))
					.build();

			MarshaledResponse usResponse = simulator.performRequest(usRequest).getMarshaledResponse();
			Assertions.assertEquals(200, usResponse.getStatusCode().intValue(), "Bad status code");

			String usBody = new String(usResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponse usToy = extractFirstToy(gson, usBody);
			String usPriceDescription = usToy.getPriceDescription();
			String usCreatedAtDescription = usToy.getCreatedAtDescription();

			Request deRequest = Request.withPath(HttpMethod.GET, "/toys")
					.headers(Map.of(
							"Accept-Language", Set.of("de-DE"),
							"Time-Zone", Set.of("Europe/Berlin")
					))
					.build();

			MarshaledResponse deResponse = simulator.performRequest(deRequest).getMarshaledResponse();
			Assertions.assertEquals(200, deResponse.getStatusCode().intValue(), "Bad status code");

			String deBody = new String(deResponse.getBody().get(), StandardCharsets.UTF_8);
			ToyResponse deToy = extractFirstToy(gson, deBody);
			String dePriceDescription = deToy.getPriceDescription();
			String deCreatedAtDescription = deToy.getCreatedAtDescription();

			Assertions.assertTrue(usPriceDescription.contains("."), "Expected US price to use '.' decimal separator");
			Assertions.assertTrue(dePriceDescription.contains(","), "Expected DE price to use ',' decimal separator");
			Assertions.assertNotEquals(usCreatedAtDescription, deCreatedAtDescription, "Expected time zone to affect createdAtDescription");
		}));
	}

	@Test
	public void testSseBroadcastLocalizationPerAccount() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		List<ServerSentEvent> adminEvents = new ArrayList<>();
		List<ServerSentEvent> employeeEvents = new ArrayList<>();

		Soklet.runSimulator(config, (simulator -> {
			String adminAuthenticationToken = acquireAuthenticationToken(app, "admin@soklet.com", "administrator-password");
			String employeeAuthenticationToken = acquireAuthenticationToken(app, "employee@soklet.com", "employee-password");

			PrivateKey privateKey = app.getConfiguration().getKeyPair().getPrivate();
			AccessToken adminSseAccessToken = acquireSseAccessToken(simulator, gson, adminAuthenticationToken);
			AccessToken employeeSseAccessToken = acquireSseAccessToken(simulator, gson, employeeAuthenticationToken);

			HandshakeAccepted adminHandshake = performSseHandshake(simulator, adminSseAccessToken.toStringRepresentation(privateKey),
					Map.of(
							"Accept-Language", Set.of("de-DE"),
							"Time-Zone", Set.of("Europe/Berlin")
					));

			HandshakeAccepted employeeHandshake = performSseHandshake(simulator, employeeSseAccessToken.toStringRepresentation(privateKey),
					Map.of(
							"Accept-Language", Set.of("en-US"),
							"Time-Zone", Set.of("America/New_York")
					));

			adminHandshake.registerEventConsumer(adminEvents::add);
			employeeHandshake.registerEventConsumer(employeeEvents::add);

			String requestBodyJson = gson.toJson(new ToyCreateRequest("Sse Toy", BigDecimal.valueOf(12.34), Currency.getInstance("USD")));

			Request createRequest = Request.withPath(HttpMethod.POST, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + adminAuthenticationToken)))
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			simulator.performRequest(createRequest);
		}));

		Assertions.assertEquals(1, adminEvents.size(), "Wrong number of admin SSE events");
		Assertions.assertEquals(1, employeeEvents.size(), "Wrong number of employee SSE events");
		Assertions.assertEquals("toy-created", adminEvents.get(0).getEvent().orElse(null), "Unexpected admin SSE event type");
		Assertions.assertEquals("toy-created", employeeEvents.get(0).getEvent().orElse(null), "Unexpected employee SSE event type");

		String adminPriceDescription = extractToyFromServerSentEvent(gson, adminEvents.get(0)).getPriceDescription();
		String employeePriceDescription = extractToyFromServerSentEvent(gson, employeeEvents.get(0)).getPriceDescription();

		Assertions.assertTrue(adminPriceDescription.contains("."), "Expected admin price to use '.' decimal separator");
		Assertions.assertTrue(employeePriceDescription.contains(","), "Expected employee price to use ',' decimal separator");
		Assertions.assertNotEquals(adminPriceDescription, employeePriceDescription, "Expected locale-specific price formatting");
	}

	@NonNull
	private AccessToken acquireSseAccessToken(@NonNull Simulator simulator,
																						@NonNull Gson gson,
																						@NonNull String authenticationToken) {
		requireNonNull(simulator);
		requireNonNull(gson);
		requireNonNull(authenticationToken);

		Request request = Request.withPath(HttpMethod.POST, "/accounts/sse-access-token")
				.headers(Map.of("Authorization", Set.of("Bearer " + authenticationToken)))
				.build();

		MarshaledResponse marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

		Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Bad status code");

		String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
		SseAccessTokenResponseHolder response = gson.fromJson(responseBody, SseAccessTokenResponseHolder.class);

		Assertions.assertNotNull(response, "Missing SSE access token response");

		return response.accessToken();
	}

	@NonNull
	private HandshakeAccepted performSseHandshake(@NonNull Simulator simulator,
																								@NonNull String sseAccessToken,
																								@NonNull Map<String, Set<String>> headers) {
		requireNonNull(simulator);
		requireNonNull(sseAccessToken);
		requireNonNull(headers);

		Request request = Request.withPath(HttpMethod.GET, "/toys/event-source")
				.queryParameters(Map.of("sse-access-token", Set.of(sseAccessToken)))
				.headers(headers)
				.build();

		ServerSentEventRequestResult requestResult = simulator.performServerSentEventRequest(request);

		if (requestResult instanceof HandshakeAccepted handshakeAccepted)
			return handshakeAccepted;
		if (requestResult instanceof HandshakeRejected handshakeRejected)
			Assertions.fail("SSE handshake rejected: " + handshakeRejected);
		if (requestResult instanceof RequestFailed requestFailed)
			Assertions.fail("SSE request failed: " + requestFailed);

		throw new IllegalStateException(format("Unexpected SSE result: %s", requestResult.getClass()));
	}

	@NonNull
	private ToyResponse extractFirstToy(@NonNull Gson gson,
																			@NonNull String responseBody) {
		requireNonNull(gson);
		requireNonNull(responseBody);

		ToysResponseHolder response = gson.fromJson(responseBody, ToysResponseHolder.class);

		Assertions.assertNotNull(response, "Missing toys response");
		Assertions.assertTrue(response.toys().size() > 0, "No toys in response");

		return response.toys().get(0);
	}

	@NonNull
	private ToyResponse extractToyFromServerSentEvent(@NonNull Gson gson,
																										@NonNull ServerSentEvent event) {
		requireNonNull(gson);
		requireNonNull(event);

		String data = event.getData().orElse(null);

		if (data == null)
			throw new AssertionError("Missing SSE data");

		ToyServerSentEventPayload payload = gson.fromJson(data, ToyServerSentEventPayload.class);

		Assertions.assertNotNull(payload, "Missing toy SSE payload");

		return payload.toy();
	}

	private record ToyServerSentEventPayload(
			@NonNull ToyResponse toy
	) {
		private ToyServerSentEventPayload {
			requireNonNull(toy);
		}
	}
}

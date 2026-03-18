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

package com.soklet.toystore.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.McpRequestResult;
import com.soklet.Request;
import com.soklet.Simulator;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.resource.AccountResource.McpAccessTokenResponseHolder;
import com.soklet.toystore.service.AccountService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyStoreMcpEndpointTests {
	@Test
	public void testListToysViaMcpUsesAccountLocalization() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, simulator -> {
			AccessToken adminApiAccessToken = acquireApiAccessToken(app, "admin@soklet.com", "administrator-password");
			String adminApiAccessTokenAsString = adminApiAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			createToy(simulator, gson, adminApiAccessTokenAsString, "Catalog Ball", BigDecimal.valueOf(12.34), Currency.getInstance("USD"));

			AccessToken employeeApiAccessToken = acquireApiAccessToken(app, "employee@soklet.com", "employee-password");
			String employeeApiAccessTokenAsString = employeeApiAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			AccessToken mcpAccessToken = acquireMcpAccessToken(simulator, gson, app, employeeApiAccessTokenAsString);
			Assertions.assertEquals(Audience.MCP, mcpAccessToken.audience(), "Expected MCP audience");
			Assertions.assertTrue(mcpAccessToken.scopes().contains(Scope.MCP_READ), "Expected MCP read scope");

			String mcpAccessTokenAsString = mcpAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());
			Map<String, Set<String>> sessionHeaders = initializeMcpSession(simulator, mcpAccessTokenAsString);

			McpRequestResult.ResponseCompleted toolCall = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					mcpPost("""
							{
							  "jsonrpc":"2.0",
							  "id":"req-20",
							  "method":"tools/call",
							  "params":{
							    "name":"list_toys",
							    "arguments":{}
							  }
							}
							""", sessionHeaders));

			JsonObject toolBody = jsonBody(toolCall);
			JsonObject toolResult = toolBody.get("result").getAsJsonObject();
			Assertions.assertFalse(toolResult.get("isError").getAsBoolean(), "Tool unexpectedly failed");

			JsonObject structuredContent = toolResult.get("structuredContent").getAsJsonObject();
			JsonArray toys = structuredContent.get("toys").getAsJsonArray();
			JsonObject firstToy = toys.get(0).getAsJsonObject();

			Request request = Request.withPath(HttpMethod.GET, "/toys")
					.headers(Map.of("Authorization", Set.of("Bearer " + employeeApiAccessTokenAsString)))
					.build();

			MarshaledResponse marshaledResponse = simulator.performHttpRequest(request).getMarshaledResponse();
			ToysResponseHolder response = gson.fromJson(new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8), ToysResponseHolder.class);
			ToyResponse httpToy = response.toys().get(0);

			Assertions.assertEquals(httpToy.getName(), stringValue(firstToy, "name"), "Toy name mismatch");
			Assertions.assertEquals(httpToy.getPriceDescription(), stringValue(firstToy, "priceDescription"), "Localized price mismatch");
			Assertions.assertEquals(httpToy.getCreatedAtDescription(), stringValue(firstToy, "createdAtDescription"), "Localized timestamp mismatch");
			Assertions.assertEquals(httpToy.getCurrencyDescription(), stringValue(firstToy, "currencyDescription"), "Localized currency mismatch");
		});
	}

	@Test
	public void testReadToyResourceAndResourceListViaMcp() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, simulator -> {
			AccessToken adminApiAccessToken = acquireApiAccessToken(app, "admin@soklet.com", "administrator-password");
			String adminApiAccessTokenAsString = adminApiAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			ToyResponseHolder createdToy = createToy(simulator, gson, adminApiAccessTokenAsString, "Catalog Robot", BigDecimal.valueOf(88.99), Currency.getInstance("EUR"));

			AccessToken employeeApiAccessToken = acquireApiAccessToken(app, "employee@soklet.com", "employee-password");
			String employeeApiAccessTokenAsString = employeeApiAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());
			String mcpAccessTokenAsString = acquireMcpAccessToken(simulator, gson, app, employeeApiAccessTokenAsString)
					.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			Map<String, Set<String>> sessionHeaders = initializeMcpSession(simulator, mcpAccessTokenAsString);

			McpRequestResult.ResponseCompleted resourcesList = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					mcpPost("""
							{
							  "jsonrpc":"2.0",
							  "id":"req-30",
							  "method":"resources/list",
							  "params":{}
							}
							""", sessionHeaders));

			JsonObject resourcesListBody = jsonBody(resourcesList);
			JsonArray listedResources = resourcesListBody.get("result").getAsJsonObject().get("resources").getAsJsonArray();
			JsonObject listedResource = listedResources.get(0).getAsJsonObject();

			Assertions.assertEquals("toystore://toys/%s".formatted(createdToy.toy().getToyId()), stringValue(listedResource, "uri"), "Toy resource URI mismatch");
			Assertions.assertEquals(createdToy.toy().getName(), stringValue(listedResource, "title"), "Toy resource title mismatch");

			McpRequestResult.ResponseCompleted resourceRead = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					mcpPost("""
							{
							  "jsonrpc":"2.0",
							  "id":"req-31",
							  "method":"resources/read",
							  "params":{
							    "uri":"toystore://toys/%s"
							  }
							}
							""".formatted(createdToy.toy().getToyId()), sessionHeaders));

			JsonObject resourceBody = jsonBody(resourceRead);
			JsonArray contents = resourceBody.get("result").getAsJsonObject().get("contents").getAsJsonArray();
			JsonObject firstContent = contents.get(0).getAsJsonObject();
			ToyResponseHolder resourceResponse = gson.fromJson(stringValue(firstContent, "text"), ToyResponseHolder.class);

			Assertions.assertEquals(createdToy.toy().getToyId(), resourceResponse.toy().getToyId(), "Resource toy ID mismatch");
			Assertions.assertEquals(createdToy.toy().getName(), resourceResponse.toy().getName(), "Resource toy name mismatch");
		});
	}

	@Test
	public void testInitializeRejectsApiAccessTokens() {
		App app = new App(new Configuration("local"));
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, simulator -> {
			AccessToken apiAccessToken = acquireApiAccessToken(app, "admin@soklet.com", "administrator-password");
			String apiAccessTokenAsString = apiAccessToken.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					mcpPost(initializeJson("req-10"), Map.of(
							"Authorization", Set.of("Bearer " + apiAccessTokenAsString)
					)));

			JsonObject initializeBody = jsonBody(initializeResult);
			JsonObject error = initializeBody.get("error").getAsJsonObject();

			Assertions.assertEquals("Sorry, we could not authenticate you.", stringValue(error, "message"),
					"Expected MCP initialization auth failure");
		});
	}

	@NonNull
	private AccessToken acquireApiAccessToken(@NonNull App app,
																						@NonNull String emailAddress,
																						@NonNull String password) {
		requireNonNull(app);
		requireNonNull(emailAddress);
		requireNonNull(password);

		AccountService accountService = app.getInjector().getInstance(AccountService.class);
		AtomicReference<AccessToken> holder = new AtomicReference<>();

		CurrentContext.with(Locale.US, ZoneId.of("America/New_York")).build().run(() -> {
			holder.set(accountService.authenticateAccount(new AccountAuthenticateRequest(emailAddress, password)));
		});

		return holder.get();
	}

	@NonNull
	private AccessToken acquireMcpAccessToken(@NonNull Simulator simulator,
																					 @NonNull Gson gson,
																					 @NonNull App app,
																					 @NonNull String apiAccessTokenAsString) {
		requireNonNull(simulator);
		requireNonNull(gson);
		requireNonNull(app);
		requireNonNull(apiAccessTokenAsString);

		Request request = Request.withPath(HttpMethod.POST, "/accounts/mcp-access-token")
				.headers(Map.of("Authorization", Set.of("Bearer " + apiAccessTokenAsString)))
				.build();

		MarshaledResponse marshaledResponse = simulator.performHttpRequest(request).getMarshaledResponse();
		Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "MCP access token issuance failed");

		String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
		McpAccessTokenResponseHolder response = gson.fromJson(responseBody, McpAccessTokenResponseHolder.class);
		return response.accessToken();
	}

	@NonNull
	private ToyResponseHolder createToy(@NonNull Simulator simulator,
																			@NonNull Gson gson,
																			@NonNull String accessTokenAsString,
																			@NonNull String name,
																			@NonNull BigDecimal price,
																			@NonNull Currency currency) {
		requireNonNull(simulator);
		requireNonNull(gson);
		requireNonNull(accessTokenAsString);
		requireNonNull(name);
		requireNonNull(price);
		requireNonNull(currency);

		String requestBodyJson = gson.toJson(new ToyCreateRequest(name, price, currency));

		Request request = Request.withPath(HttpMethod.POST, "/toys")
				.headers(Map.of("Authorization", Set.of("Bearer " + accessTokenAsString)))
				.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
				.build();

		MarshaledResponse marshaledResponse = simulator.performHttpRequest(request).getMarshaledResponse();
		Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Toy creation failed");

		String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
		return gson.fromJson(responseBody, ToyResponseHolder.class);
	}

	@NonNull
	private Map<String, Set<String>> initializeMcpSession(@NonNull Simulator simulator,
																									 @NonNull String mcpAccessTokenAsString) {
		requireNonNull(simulator);
		requireNonNull(mcpAccessTokenAsString);

		McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
				mcpPost(initializeJson("req-1"), Map.of(
						"Authorization", Set.of("Bearer " + mcpAccessTokenAsString)
				)));

		String sessionId = headerValue(initializeResult, "MCP-Session-Id");
		Map<String, Set<String>> sessionHeaders = Map.of(
				"MCP-Session-Id", Set.of(sessionId),
				"MCP-Protocol-Version", Set.of("2025-11-25")
		);

		simulator.performMcpRequest(mcpPost("""
				{
				  "jsonrpc":"2.0",
				  "method":"notifications/initialized",
				  "params":{}
				}
				""", sessionHeaders));

		return sessionHeaders;
	}

	@NonNull
	private Request mcpPost(@NonNull String body,
													@NonNull Map<String, Set<String>> headers) {
		requireNonNull(body);
		requireNonNull(headers);

		Map<String, Set<String>> allHeaders = new java.util.LinkedHashMap<>(headers);
		allHeaders.put("Content-Type", Set.of("application/json"));

		return Request.withPath(HttpMethod.POST, "/mcp")
				.headers(allHeaders)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	private String initializeJson(@NonNull String requestId) {
		requireNonNull(requestId);

		return """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"initialize",
				  "params":{
				    "protocolVersion":"2025-11-25",
				    "capabilities":{},
				    "clientInfo":{"name":"test-client","version":"1.0.0"}
				  }
				}
				""".formatted(requestId);
	}

	@NonNull
	private String headerValue(McpRequestResult.ResponseCompleted responseCompleted,
														 @NonNull String headerName) {
		requireNonNull(responseCompleted);
		requireNonNull(headerName);

		return responseCompleted.getHttpRequestResult().getMarshaledResponse().getHeaders()
				.get(headerName)
				.stream()
				.findFirst()
				.orElseThrow();
	}

	@NonNull
	private JsonObject jsonBody(McpRequestResult.ResponseCompleted responseCompleted) {
		requireNonNull(responseCompleted);

		String body = new String(responseCompleted.getHttpRequestResult().getMarshaledResponse().getBody().orElseThrow(),
				StandardCharsets.UTF_8);

		return JsonParser.parseString(body).getAsJsonObject();
	}

	@NonNull
	private String stringValue(@NonNull JsonObject object,
														 @NonNull String fieldName) {
		requireNonNull(object);
		requireNonNull(fieldName);

		return object.get(fieldName).getAsString();
	}
}

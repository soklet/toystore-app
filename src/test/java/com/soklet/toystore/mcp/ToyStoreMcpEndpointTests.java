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
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationResponse;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import com.soklet.Simulator;
import com.soklet.SokletSimulator;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.AccessTokenResult;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.resource.AccountResource.McpAccessTokenResponseHolder;
import com.soklet.toystore.service.AccountService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.toystore.TestMarshaledResponses.responseBodyAsString;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyStoreMcpEndpointTests {
	@NonNull
	private static final Duration MCP_WAIT = Duration.ofSeconds(5);
	@NonNull
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	public void testListToysViaMcpUsesAccountLocalization() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			createToy(simulator, gson, adminToken, "Catalog Ball",
					BigDecimal.valueOf(12.34), Currency.getInstance("USD"));

			String employeeApiToken = apiToken(app, "employee@soklet.com",
					"employee-password");
			String employeeMcpToken = mcpToken(
					simulator, gson, app, employeeApiToken);
			McpSimulationResponse toolResponse = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "tools/call", "list_toys", "de-DE", """
						,"name":"list_toys","arguments":{}
						""");
			JsonObject result = jsonBody(toolResponse)
					.getAsJsonObject("result");
			JsonObject structured = result.getAsJsonObject("structuredContent");
			JsonObject firstToy = structured.getAsJsonArray("toys")
					.get(0).getAsJsonObject();

			Request request = Request.withPath(HttpMethod.GET, "/toys")
					.headers(Map.of("Authorization",
							Set.of("Bearer " + employeeApiToken)))
					.build();
			MarshaledResponse httpResponse = simulator.performHttpRequest(request)
					.getMarshaledResponse();
			ToysResponseHolder response = gson.fromJson(
					responseBodyAsString(httpResponse), ToysResponseHolder.class);
			ToyResponse httpToy = response.toys().get(0);

			Assertions.assertEquals(httpToy.getName(),
					stringValue(firstToy, "name"));
			Assertions.assertEquals(httpToy.getPriceDescription(),
					stringValue(firstToy, "priceDescription"));
			Assertions.assertEquals(httpToy.getCreatedAtDescription(),
					stringValue(firstToy, "createdAtDescription"));
			Assertions.assertEquals(httpToy.getCurrencyDescription(),
					stringValue(firstToy, "currencyDescription"));
			Assertions.assertEquals("1 Spielzeug(e) gefunden.",
					stringValue(structured, "summary"));
			Assertions.assertEquals("1 Spielzeug(e) gefunden.",
					result.getAsJsonArray("content").get(0).getAsJsonObject()
							.get("text").getAsString());
		});
	}

	@Test
	public void testReadToyResourceAndResourceListViaMcp() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			ToyResponseHolder created = createToy(simulator, gson, adminToken,
					"Catalog Robot", BigDecimal.valueOf(88.99),
					Currency.getInstance("EUR"));
			String employeeMcpToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));

			JsonObject resourcesResult = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "resources/list", null, "de-DE", ""))
					.getAsJsonObject("result");
			JsonObject listedResource = resourcesResult
					.getAsJsonArray("resources").get(0).getAsJsonObject();
			String uri = "toystore://toys/%s".formatted(
					created.toy().getToyId());
			Assertions.assertEquals(uri, stringValue(listedResource, "uri"));
			Assertions.assertEquals(created.toy().getName(),
					stringValue(listedResource, "title"));

			JsonObject resourceResult = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "resources/read", uri, "de-DE",
					",\"uri\":\"%s\"".formatted(uri)))
					.getAsJsonObject("result");
			JsonObject firstContent = resourceResult.getAsJsonArray("contents")
					.get(0).getAsJsonObject();
			ToyResponseHolder resource = gson.fromJson(
					stringValue(firstContent, "text"), ToyResponseHolder.class);

			Assertions.assertEquals(created.toy().getToyId(),
					resource.toy().getToyId());
			Assertions.assertEquals(created.toy().getName(),
					resource.toy().getName());
		});
	}

	@Test
	public void testAdmissionRejectsApiAccessTokensWithoutSessionInitialization() {
		App app = new App(new Configuration("local"));

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			McpSimulationResponse response = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), apiToken,
					"tools/list", null, "en-US", "");
			JsonObject error = jsonBody(response).getAsJsonObject("error");

			Assertions.assertEquals(401, response.getStatusCode());
			Assertions.assertEquals("Sorry, we could not authenticate you.",
					stringValue(error, "message"));
			assertHeader(response, "WWW-Authenticate", "Bearer");
		});
	}

	@Test
	public void testAdmissionReevaluatesCredentialsForEveryRequest() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "employee@soklet.com",
					"employee-password");
			String validMcpToken = mcpToken(simulator, gson, app, apiToken);
			String wrongAudienceToken = tokenWithClaims(app, validMcpToken,
					Audience.API, Set.of(Scope.MCP_READ));
			String insufficientScopeToken = tokenWithClaims(app, validMcpToken,
					Audience.MCP, Set.of(Scope.API_READ));
			String expiredToken = expiredToken(app, validMcpToken);

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, "not-a-jwt",
					"Malformed credentials were carried over from the prior request.");
			assertAuthenticationHeaderRejected(simulator, app,
					"Bearer" + validMcpToken,
					"A Bearer credential without the required separator was accepted.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, null,
					"Missing credentials were carried over from the prior request.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, expiredToken,
					"Expired credentials were carried over from the prior request.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, wrongAudienceToken,
					"A wrong-audience credential inherited the prior identity.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertInsufficientScopeRejected(simulator, app,
					insufficientScopeToken,
					"An insufficient-scope credential inherited the prior authorization.");
		});
	}

	@Test
	public void testFrameworkCatalogLocalizationForGermanAndPortuguese() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String employeeToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			McpSimulationResponse german = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), employeeToken,
					"tools/list", null, "de-DE", "");
			JsonObject germanTool = tool(jsonBody(german), "list_toys");
			Assertions.assertEquals("Spielzeuge auflisten",
					stringValue(germanTool, "title"));
			Assertions.assertEquals(
					"Listet Spielzeuge im Katalog auf. Optional kann nach einem Namenspräfix gefiltert werden.",
					stringValue(germanTool, "description"));
			assertHeader(german, "Content-Language", "de-DE");
			assertVaryAcceptLanguage(german);

			String customerToken = mcpToken(simulator, gson, app,
					apiToken(app, "customer@soklet.com", "customer-password"));
			McpSimulationResponse portuguese = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					customerToken, "tools/list", null, "pt-BR", "");
			JsonObject portugueseTool = tool(jsonBody(portuguese), "get_toy");
			Assertions.assertEquals("Obter brinquedo",
					stringValue(portugueseTool, "title"));
			Assertions.assertEquals(
					"Obtém um brinquedo pelo identificador.",
					stringValue(portugueseTool, "description"));
			assertHeader(portuguese, "Content-Language", "pt-BR");
			assertVaryAcceptLanguage(portuguese);
		});
	}

	@Test
	public void testMissingToyReturnsLocalizedApplicationToolError() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String employeeToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			String missingToyId = UUID.randomUUID().toString();
			JsonObject result = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), employeeToken,
					"tools/call", "get_toy", "de-DE",
					",\"name\":\"get_toy\",\"arguments\":{\"toyId\":\"%s\"}"
							.formatted(missingToyId)))
					.getAsJsonObject("result");

			Assertions.assertTrue(result.get("isError").getAsBoolean());
			Assertions.assertEquals("Spielzeug nicht gefunden.",
					result.getAsJsonArray("content").get(0).getAsJsonObject()
							.get("text").getAsString());
		});
	}

	@NonNull
	private String apiToken(@NonNull App app, @NonNull String emailAddress,
			@NonNull String password) {
		AccountService accountService =
				app.getInjector().getInstance(AccountService.class);
		AtomicReference<AccessToken> holder = new AtomicReference<>();
		CurrentContext.with(Locale.US, ZoneId.of("America/New_York"))
				.build()
				.run(() -> holder.set(accountService.authenticateAccount(
						new AccountAuthenticateRequest(emailAddress, password))));
		return holder.get().toStringRepresentation(
				app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String mcpToken(@NonNull Simulator simulator, @NonNull Gson gson,
			@NonNull App app, @NonNull String apiAccessToken) {
		Request request = Request.withPath(
					HttpMethod.POST, "/accounts/mcp-access-token")
				.headers(Map.of("Authorization",
						Set.of("Bearer " + apiAccessToken)))
				.build();
		MarshaledResponse response = simulator.performHttpRequest(request)
				.getMarshaledResponse();
		Assertions.assertEquals(200, response.getStatusCode());

		McpAccessTokenResponseHolder holder = gson.fromJson(
				responseBodyAsString(response), McpAccessTokenResponseHolder.class);
		Assertions.assertEquals(Audience.MCP, holder.accessToken().audience());
		Assertions.assertTrue(holder.accessToken().scopes()
				.contains(Scope.MCP_READ));
		return holder.accessToken().toStringRepresentation(
				app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String tokenWithClaims(@NonNull App app,
			@NonNull String sourceToken, @NonNull Audience audience,
			@NonNull Set<@NonNull Scope> scopes) {
		AccessToken source = verifiedToken(app, sourceToken);
		return new AccessToken(source.accountId(), source.issuedAt(),
				source.expiresAt(), audience, scopes)
				.toStringRepresentation(
						app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String expiredToken(@NonNull App app,
			@NonNull String sourceToken) {
		AccessToken source = verifiedToken(app, sourceToken);
		return new AccessToken(source.accountId(), Instant.EPOCH,
				Instant.EPOCH.plusSeconds(1), Audience.MCP,
				Set.of(Scope.MCP_READ))
				.toStringRepresentation(
						app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private AccessToken verifiedToken(@NonNull App app,
			@NonNull String sourceToken) {
		AccessTokenResult result = AccessToken.fromStringRepresentation(
				sourceToken, app.getConfiguration().getKeyPair().getPublic());
		if (result instanceof AccessTokenResult.Succeeded succeeded)
			return succeeded.accessToken();
		throw new AssertionError("Source access token could not be parsed: "
				+ result);
	}

	private void assertToolsListAccepted(@NonNull Simulator simulator,
			@NonNull App app, @NonNull String accessToken) {
		McpSimulationResponse response = mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", "");
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertTrue(jsonBody(response).has("result"));
	}

	private void assertAuthenticationRejected(@NonNull Simulator simulator,
			@NonNull App app, @Nullable String accessToken,
			@NonNull String failureMessage) {
		assertAuthenticationResponseRejected(mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", ""), failureMessage);
	}

	private void assertAuthenticationHeaderRejected(
			@NonNull Simulator simulator, @NonNull App app,
			@NonNull String authorizationHeader,
			@NonNull String failureMessage) {
		assertAuthenticationResponseRejected(mcpRequestWithAuthorization(
				simulator, app.getConfiguration().getMcpServerPort(),
				authorizationHeader, "tools/list", null, "en-US", ""),
				failureMessage);
	}

	private void assertAuthenticationResponseRejected(
			@NonNull McpSimulationResponse response,
			@NonNull String failureMessage) {
		JsonObject error = jsonBody(response).getAsJsonObject("error");

		Assertions.assertEquals(401, response.getStatusCode(), failureMessage);
		Assertions.assertEquals(-31901, error.get("code").getAsInt(),
				failureMessage);
		Assertions.assertEquals("Sorry, we could not authenticate you.",
				stringValue(error, "message"), failureMessage);
		assertHeader(response, "WWW-Authenticate", "Bearer");
	}

	private void assertInsufficientScopeRejected(@NonNull Simulator simulator,
			@NonNull App app, @NonNull String accessToken,
			@NonNull String failureMessage) {
		McpSimulationResponse response = mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", "");
		JsonObject error = jsonBody(response).getAsJsonObject("error");

		Assertions.assertEquals(403, response.getStatusCode(), failureMessage);
		Assertions.assertEquals(-31903, error.get("code").getAsInt(),
				failureMessage);
		Assertions.assertEquals(
				"Sorry, you are not authorized to perform this action.",
				stringValue(error, "message"), failureMessage);
		assertHeader(response, "WWW-Authenticate",
				"Bearer error=\"insufficient_scope\", scope=\"mcp:read\"");
	}

	@NonNull
	private ToyResponseHolder createToy(@NonNull Simulator simulator,
			@NonNull Gson gson, @NonNull String accessToken, @NonNull String name,
			@NonNull BigDecimal price, @NonNull Currency currency) {
		String body = gson.toJson(new ToyCreateRequest(name, price, currency));
		Request request = Request.withPath(HttpMethod.POST, "/toys")
				.headers(Map.of("Authorization",
						Set.of("Bearer " + accessToken)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
		MarshaledResponse response = simulator.performHttpRequest(request)
				.getMarshaledResponse();
		Assertions.assertEquals(200, response.getStatusCode());
		return gson.fromJson(responseBodyAsString(response),
				ToyResponseHolder.class);
	}

	@NonNull
	private McpSimulationResponse mcpRequest(@NonNull Simulator simulator,
			@NonNull Integer mcpPort, @Nullable String accessToken,
			@NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix) {
		return mcpRequestWithAuthorization(simulator, mcpPort,
				accessToken == null ? null : "Bearer " + accessToken, method,
				operationName, acceptLanguage, paramsSuffix);
	}

	@NonNull
	private McpSimulationResponse mcpRequestWithAuthorization(
			@NonNull Simulator simulator, @NonNull Integer mcpPort,
			@Nullable String authorizationHeader, @NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix) {
		String body = """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"%s",
				  "params":{
				    "_meta":{
				      "io.modelcontextprotocol/protocolVersion":"%s",
				      "io.modelcontextprotocol/clientCapabilities":{}
				    }%s
				  }
				}
				""".formatted(UUID.randomUUID(), method, PROTOCOL_VERSION,
				paramsSuffix);
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of("127.0.0.1:" + mcpPort));
		if (authorizationHeader != null)
			headers.put("Authorization", Set.of(authorizationHeader));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept",
				Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (acceptLanguage != null)
			headers.put("Accept-Language", Set.of(acceptLanguage));

		Request request = Request.withPath(HttpMethod.POST, "/mcp")
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(MCP_WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out awaiting MCP response."));
			var completion = simulation.awaitCompletion(MCP_WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting MCP completion."));
			Assertions.assertEquals(McpSimulationBodyType.JSON,
					response.getBodyType(), () -> "Unexpected MCP response: status=%s, headers=%s, completion=%s, throwables=%s"
							.formatted(response.getStatusCode(), response.getHeaders(),
									completion.getReason(), completion.getThrowables()));
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					completion.getReason());
			return response;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private JsonObject jsonBody(@NonNull McpSimulationResponse response) {
		byte[] body = response.getBody().orElseThrow(() ->
				new AssertionError("MCP JSON body was not captured."));
		return JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
				.getAsJsonObject();
	}

	@NonNull
	private JsonObject tool(@NonNull JsonObject response,
			@NonNull String toolName) {
		JsonArray tools = response.getAsJsonObject("result")
				.getAsJsonArray("tools");
		for (int index = 0; index < tools.size(); ++index) {
			JsonObject tool = tools.get(index).getAsJsonObject();
			if (toolName.equals(stringValue(tool, "name")))
				return tool;
		}
		throw new AssertionError("Tool not found: " + toolName);
	}

	private void assertHeader(@NonNull McpSimulationResponse response,
			@NonNull String name, @NonNull String expectedValue) {
		Set<String> values = response.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"Missing response header: " + name));
		Assertions.assertTrue(values.stream()
				.anyMatch(value -> value.equalsIgnoreCase(expectedValue)),
				() -> "Expected %s=%s but was %s".formatted(
						name, expectedValue, values));
	}

	private void assertVaryAcceptLanguage(
			@NonNull McpSimulationResponse response) {
		Set<String> values = response.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase("Vary"))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing Vary header."));
		Assertions.assertTrue(values.stream()
				.flatMap(value -> java.util.Arrays.stream(value.split(",")))
				.map(String::trim)
				.anyMatch(value -> value.equalsIgnoreCase("Accept-Language")));
	}

	@NonNull
	private String stringValue(@NonNull JsonObject object,
			@NonNull String fieldName) {
		return requireNonNull(object).get(requireNonNull(fieldName)).getAsString();
	}
}

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
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import com.lokalized.Strings;
import com.soklet.McpArray;
import com.soklet.McpEndpoint;
import com.soklet.McpBoolean;
import com.soklet.McpInitializationContext;
import com.soklet.McpJsonRpcError;
import com.soklet.McpListResourcesResult;
import com.soklet.McpListedResource;
import com.soklet.McpNull;
import com.soklet.McpNumber;
import com.soklet.McpObject;
import com.soklet.McpRequestContext;
import com.soklet.McpResourceContents;
import com.soklet.McpSessionContext;
import com.soklet.McpString;
import com.soklet.McpTextContent;
import com.soklet.McpToolCallContext;
import com.soklet.McpToolResult;
import com.soklet.McpValue;
import com.soklet.Request;
import com.soklet.annotation.McpArgument;
import com.soklet.annotation.McpListResources;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpUriParameter;
import com.soklet.toystore.exception.AuthenticationException;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.service.AccountService;
import com.soklet.toystore.service.ToyService;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Read-only MCP endpoint for the Toy Store catalog.
 *
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
@McpServerEndpoint(
		path = "/mcp",
		name = "toystore",
		version = "1.0.0",
		title = "Toy Store MCP",
		description = "Read-only Toy Store catalog tools and resources.",
		instructions = "Authenticate with a Toy Store MCP bearer token and use the catalog to inspect available toys."
)
public class ToyStoreMcpEndpoint implements McpEndpoint {
	@NonNull
	private final AccountService accountService;
	@NonNull
	private final ToyService toyService;
	@NonNull
	private final ToyResponseFactory toyResponseFactory;
	@NonNull
	private final Strings strings;
	@NonNull
	private final Gson gson;

	@Inject
	public ToyStoreMcpEndpoint(@NonNull AccountService accountService,
														 @NonNull ToyService toyService,
														 @NonNull ToyResponseFactory toyResponseFactory,
														 @NonNull Strings strings,
														 @NonNull Gson gson) {
		requireNonNull(accountService);
		requireNonNull(toyService);
		requireNonNull(toyResponseFactory);
		requireNonNull(strings);
		requireNonNull(gson);

		this.accountService = accountService;
		this.toyService = toyService;
		this.toyResponseFactory = toyResponseFactory;
		this.strings = strings;
		this.gson = gson;
	}

	@NonNull
	@Override
	public McpSessionContext initialize(@NonNull McpInitializationContext context,
																			@NonNull McpSessionContext session) {
		requireNonNull(context);
		requireNonNull(session);

		Account account = getAccountService().findAccountByAccessToken(
				resolveAccessTokenFromAuthorization(context.getRequest()),
				Audience.MCP,
				Set.of(Scope.MCP_READ)
		).orElseThrow(AuthenticationException::new);

		return session.with("accountId", account.accountId());
	}

	@NonNull
	@Override
	public McpToolResult handleToolError(@NonNull Throwable throwable,
																			 @NonNull McpToolCallContext context) {
		requireNonNull(throwable);
		requireNonNull(context);

		if (throwable instanceof NotFoundException)
			return McpToolResult.fromErrorMessage(getStrings().get("Toy not found."));

		if (throwable instanceof AuthenticationException)
			return McpToolResult.fromErrorMessage(getStrings().get("Sorry, we could not authenticate you."));

		return McpEndpoint.super.handleToolError(throwable, context);
	}

	@NonNull
	@Override
	public McpJsonRpcError handleError(@NonNull Throwable throwable,
																		 @NonNull McpRequestContext context) {
		requireNonNull(throwable);
		requireNonNull(context);

		if (throwable instanceof AuthenticationException)
			return McpJsonRpcError.fromCodeAndMessage(-32001, getStrings().get("Sorry, we could not authenticate you."));

		if (throwable instanceof NotFoundException)
			return McpJsonRpcError.fromCodeAndMessage(-32004, getStrings().get("Toy not found."));

		return McpEndpoint.super.handleError(throwable, context);
	}

	@NonNull
	@McpTool(name = "list_toys", description = "Lists toys in the catalog. Optionally filter by a toy-name prefix.")
	public McpToolResult listToys(@McpArgument(value = "query", optional = true) Optional<String> query) {
		requireNonNull(query);

		List<ToyResponse> toys = (query.isPresent()
				? getToyService().searchToys(query.get())
				: getToyService().findToys())
				.stream()
				.map(getToyResponseFactory()::create)
				.toList();

		String summary = query.filter(value -> !value.isBlank())
				.map(value -> "Found %d toy(s) matching \"%s\".".formatted(toys.size(), value))
				.orElse("Found %d toy(s).".formatted(toys.size()));

		return McpToolResult.builder()
				.content(McpTextContent.fromText(summary))
				.structuredContent(toMcpObject(new ToysResponseHolder(toys)))
				.build();
	}

	@NonNull
	@McpTool(name = "get_toy", description = "Gets a toy by its ID.")
	public McpToolResult getToy(@NonNull @McpArgument("toyId") UUID toyId) {
		requireNonNull(toyId);

		ToyResponse toy = getToyResponseFactory().create(findToyOrThrow(toyId));

		return McpToolResult.builder()
				.content(McpTextContent.fromText("Loaded toy \"%s\".".formatted(toy.getName())))
				.structuredContent(toMcpObject(new ToyResponseHolder(toy)))
				.build();
	}

	@NonNull
	@McpListResources
	public McpListResourcesResult listToyResources() {
		List<McpListedResource> resources = getToyService().findToys().stream()
				.map(toy -> {
					ToyResponse toyResponse = getToyResponseFactory().create(toy);

					return McpListedResource.fromComponents(toyUri(toy.toyId()), "toy", "application/json")
							.withTitle(toyResponse.getName())
							.withDescription("%s | %s".formatted(toyResponse.getPriceDescription(), toyResponse.getCreatedAtDescription()));
				})
				.toList();

		return McpListResourcesResult.fromResources(resources);
	}

	@NonNull
	@McpResource(
			uri = "toystore://toys/{toyId}",
			name = "toy",
			mimeType = "application/json",
			description = "Localized Toy Store catalog entry."
	)
	public McpResourceContents toy(@NonNull @McpUriParameter("toyId") UUID toyId) {
		requireNonNull(toyId);

		ToyResponseHolder response = new ToyResponseHolder(getToyResponseFactory().create(findToyOrThrow(toyId)));

		return McpResourceContents.fromText(
				toyUri(toyId),
				getGson().toJson(response),
				"application/json"
		);
	}

	@NonNull
	private Toy findToyOrThrow(@NonNull UUID toyId) {
		requireNonNull(toyId);

		return getToyService().findToyById(toyId)
				.orElseThrow(() -> new NotFoundException(getStrings().get("Toy not found.")));
	}

	@NonNull
	private String toyUri(@NonNull UUID toyId) {
		requireNonNull(toyId);
		return "toystore://toys/%s".formatted(toyId);
	}

	@NonNull
	private McpObject toMcpObject(@NonNull Object value) {
		requireNonNull(value);

		McpValue mcpValue = toMcpValue(getGson().toJsonTree(value));

		if (!(mcpValue instanceof McpObject mcpObject))
			throw new IllegalArgumentException("Expected object structured content.");

		return mcpObject;
	}

	@NonNull
	private McpValue toMcpValue(JsonElement jsonElement) {
		requireNonNull(jsonElement);

		if (jsonElement.isJsonNull())
			return McpNull.INSTANCE;

		if (jsonElement.isJsonObject()) {
			Map<String, McpValue> values = new LinkedHashMap<>();

			for (Map.Entry<String, JsonElement> entry : jsonElement.getAsJsonObject().entrySet())
				values.put(entry.getKey(), toMcpValue(entry.getValue()));

			return new McpObject(values);
		}

		if (jsonElement.isJsonArray())
		{
			List<McpValue> values = new java.util.ArrayList<>();

			for (JsonElement element : jsonElement.getAsJsonArray())
				values.add(toMcpValue(element));

			return new McpArray(values);
		}

		JsonPrimitive primitive = jsonElement.getAsJsonPrimitive();

		if (primitive.isBoolean())
			return new McpBoolean(primitive.getAsBoolean());

		if (primitive.isNumber())
			return new McpNumber(primitive.getAsBigDecimal());

		if (primitive.isString())
			return new McpString(primitive.getAsString());

		throw new IllegalArgumentException("Unsupported JSON value: %s".formatted(jsonElement));
	}

	private String resolveAccessTokenFromAuthorization(@NonNull Request request) {
		requireNonNull(request);

		String authorizationHeader = request.getHeader("Authorization").orElse(null);

		if (authorizationHeader == null)
			return null;

		String trimmed = authorizationHeader.trim();

		if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer", 0, 6))
			return null;

		String token = trimmed.substring(6).trim();

		return token.isEmpty() ? null : token;
	}

	@NonNull
	private AccountService getAccountService() {
		return this.accountService;
	}

	@NonNull
	private ToyService getToyService() {
		return this.toyService;
	}

	@NonNull
	private ToyResponseFactory getToyResponseFactory() {
		return this.toyResponseFactory;
	}

	@NonNull
	private Strings getStrings() {
		return this.strings;
	}

	@NonNull
	private Gson getGson() {
		return this.gson;
	}
}

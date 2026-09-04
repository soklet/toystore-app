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
import com.google.inject.Inject;
import com.lokalized.Strings;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpResourceDescriptor;
import com.soklet.McpResourceListContext;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourcePage;
import com.soklet.McpTextResourceContents;
import com.soklet.annotation.McpResourceList;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpResourceUriParameter;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.service.ToyService;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public final class ToyStoreMcpEndpoint {
	@NonNull
	private final ToyService toyService;
	@NonNull
	private final ToyResponseFactory toyResponseFactory;
	@NonNull
	private final Strings strings;
	@NonNull
	private final Gson gson;

	@Inject
	public ToyStoreMcpEndpoint(@NonNull ToyService toyService,
												 @NonNull ToyResponseFactory toyResponseFactory,
												 @NonNull Strings strings,
												 @NonNull Gson gson) {
		this.toyService = requireNonNull(toyService);
		this.toyResponseFactory = requireNonNull(toyResponseFactory);
		this.strings = requireNonNull(strings);
		this.gson = requireNonNull(gson);
	}

	@NonNull
	@McpTool(
			name = "list_toys",
			title = "List toys",
			description = "Lists toys in the catalog. Optionally filter by a toy-name prefix.",
			structuredContentMirroredAsText = false
	)
	public ToyListResult listToys(
			@McpToolArgument(
					name = "query",
					title = "Toy name prefix",
					description = "Optional toy-name prefix to match."
			) @NonNull Optional<String> query) {
		requireNonNull(query);

		List<ToyCatalogEntry> toys = (query.isPresent()
				? getToyService().searchToys(query.orElseThrow())
				: getToyService().findToys())
				.stream()
				.map(getToyResponseFactory()::create)
				.map(this::toCatalogEntry)
				.toList();
		String summary = query.filter(value -> !value.isBlank())
				.map(value -> getStrings().get(
						"Found {{toyCount}} toy(s) matching \"{{query}}\".",
						Map.of("toyCount", toys.size(), "query", value)))
				.orElseGet(() -> getStrings().get(
						"Found {{toyCount}} toy(s).",
						Map.of("toyCount", toys.size())));

		return new ToyListResult(summary, toys);
	}

	@NonNull
	@McpTool(
			name = "get_toy",
			title = "Get toy",
			description = "Gets a toy by its ID.",
			structuredContentMirroredAsText = false
	)
	public ToyLookupResult getToy(
			@McpToolArgument(
					name = "toyId",
					title = "Toy ID",
					description = "The UUID of the toy to load."
			) @NonNull String toyId) {
		requireNonNull(toyId);

		ToyResponse toy = getToyResponseFactory().create(
				findToyOrThrow(parseToyId(toyId)));
		String summary = getStrings().get(
				"Loaded toy \"{{toyName}}\".",
				Map.of("toyName", toy.getName()));

		return new ToyLookupResult(summary, toCatalogEntry(toy));
	}

	@NonNull
	@McpResourceList
	public McpResourcePage listToyResources(
			@NonNull McpResourceListContext context) {
		requireNonNull(context);

		List<McpResourceDescriptor> resources = getToyService().findToys().stream()
				.map(toy -> {
					ToyResponse response = getToyResponseFactory().create(toy);
					return McpResourceDescriptor.withUriAndName(
							toyUri(toy.toyId()), "toy")
							.title(response.getName())
							.description("%s | %s".formatted(
									response.getPriceDescription(),
									response.getCreatedAtDescription()))
							.mimeType("application/json")
							.build();
				})
				.toList();

		return McpResourcePage.builder().addResources(resources).build();
	}

	@NonNull
	@McpResource(
			uri = "toystore://toys/{toyId}",
			name = "toy",
			title = "Toy catalog entry",
			mimeType = "application/json",
			description = "Localized Toy Store catalog entry."
	)
	public McpResourceOutput toy(
			@McpResourceUriParameter(name = "toyId") @NonNull String toyId) {
		requireNonNull(toyId);
		UUID parsedToyId = parseToyId(toyId);
		Toy toy;

		try {
			toy = findToyOrThrow(parsedToyId);
		} catch (NotFoundException exception) {
			throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
					-31904, getStrings().get("Toy not found.")));
		}

		ToyResponseHolder response = new ToyResponseHolder(
				getToyResponseFactory().create(toy));
		return McpResourceOutput.fromContent(
				McpTextResourceContents.withUriAndText(
						toyUri(parsedToyId), getGson().toJson(response))
						.mimeType("application/json")
						.build());
	}

	@NonNull
	private Toy findToyOrThrow(@NonNull UUID toyId) {
		return getToyService().findToyById(requireNonNull(toyId))
				.orElseThrow(NotFoundException::new);
	}

	@NonNull
	private UUID parseToyId(@NonNull String toyId) {
		try {
			return UUID.fromString(requireNonNull(toyId));
		} catch (IllegalArgumentException exception) {
			throw new McpJsonRpcException(McpJsonRpcError.fromInvalidParameters(
					getStrings().get("The toyId argument must be a UUID.")));
		}
	}

	@NonNull
	private URI toyUri(@NonNull UUID toyId) {
		return URI.create("toystore://toys/%s".formatted(requireNonNull(toyId)));
	}

	@NonNull
	private ToyCatalogEntry toCatalogEntry(@NonNull ToyResponse toy) {
		requireNonNull(toy);
		return new ToyCatalogEntry(
				toy.getToyId().toString(),
				toy.getName(),
				toy.getPrice(),
				toy.getPriceDescription(),
				toy.getCurrencyCode(),
				toy.getCurrencySymbol(),
				toy.getCurrencyDescription(),
				toy.getCreatedAt().toString(),
				toy.getCreatedAtDescription());
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

	/** Typed MCP result for catalog searches. */
	public record ToyListResult(
			@NonNull String summary,
			@NonNull List<@NonNull ToyCatalogEntry> toys) {
		public ToyListResult {
			requireNonNull(summary);
			toys = List.copyOf(requireNonNull(toys));
		}
	}

	/** Typed MCP result for one catalog lookup. */
	public record ToyLookupResult(
			@NonNull String summary,
			@NonNull ToyCatalogEntry toy) {
		public ToyLookupResult {
			requireNonNull(summary);
			requireNonNull(toy);
		}
	}

	/** MCP-safe record representation of the existing HTTP toy response. */
	public record ToyCatalogEntry(
			@NonNull String toyId,
			@NonNull String name,
			@NonNull BigDecimal price,
			@NonNull String priceDescription,
			@NonNull String currencyCode,
			@NonNull String currencySymbol,
			@NonNull String currencyDescription,
			@NonNull String createdAt,
			@NonNull String createdAtDescription) {
		public ToyCatalogEntry {
			requireNonNull(toyId);
			requireNonNull(name);
			requireNonNull(price);
			requireNonNull(priceDescription);
			requireNonNull(currencyCode);
			requireNonNull(currencySymbol);
			requireNonNull(currencyDescription);
			requireNonNull(createdAt);
			requireNonNull(createdAtDescription);
		}
	}
}

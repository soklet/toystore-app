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
import com.google.inject.Provider;
import com.lokalized.Strings;
import com.soklet.McpAppResourceMetadata;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpRequestContext;
import com.soklet.McpResourceDescriptor;
import com.soklet.McpResourceListContext;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourcePage;
import com.soklet.McpTextResourceContents;
import com.soklet.annotation.McpAppTool;
import com.soklet.annotation.McpResourceList;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpResourceUriParameter;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.service.ToyService;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
	private static final String CATALOG_APP_URI = "ui://toystore/catalog-v1";
	private static final String CATALOG_APP_MIME_TYPE = "text/html;profile=mcp-app";
	@NonNull
	private static final String CATALOG_APP_HTML = catalogAppHtml();
	@NonNull
	private static final McpAppResourceMetadata CATALOG_APP_METADATA =
			McpAppResourceMetadata.builder()
					.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder().build())
					.permissions(Set.of()).prefersBorder(true).build();
	@NonNull
	private final ToyService toyService;
	@NonNull
	private final ToyResponseFactory toyResponseFactory;
	@NonNull
	private final Strings strings;
	@NonNull
	private final Gson gson;
	@NonNull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public ToyStoreMcpEndpoint(@NonNull ToyService toyService,
												 @NonNull ToyResponseFactory toyResponseFactory,
												 @NonNull Strings strings,
												 @NonNull Gson gson,
												 @NonNull Provider<CurrentContext> currentContextProvider) {
		this.toyService = requireNonNull(toyService);
		this.toyResponseFactory = requireNonNull(toyResponseFactory);
		this.strings = requireNonNull(strings);
		this.gson = requireNonNull(gson);
		this.currentContextProvider = requireNonNull(currentContextProvider);
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

		// Use the same account-scoped string catalog as the summary, not the
		// request's independently negotiated MCP descriptor language.
		String locale = getStrings().bestMatchFor(getCurrentContext().getLocale()).toLanguageTag();
		return new ToyListResult(summary, toys, locale);
	}

	@NonNull
	@McpTool(
			name = "show_toy_catalog",
			title = "Show toy catalog",
			description = "Shows the read-only toy catalog with an optional toy-name prefix filter. Apps-capable clients can display an interactive catalog view.",
			structuredContentMirroredAsText = false
	)
	@McpAppTool(resourceUri = CATALOG_APP_URI)
	public ToyListResult showToyCatalog(
			@McpToolArgument(
					name = "query",
					title = "Toy name prefix",
					description = "Optional toy-name prefix to match."
			) @NonNull Optional<String> query) {
		ToyListResult result = listToys(requireNonNull(query));
		// Keep the ordinary text result useful when a host cannot render Apps.
		String entries = result.toys().stream()
				.map(toy -> "%s — %s (%s)".formatted(
						toy.name(), toy.priceDescription(), toy.currencyCode()))
				.collect(Collectors.joining("\n"));
		return new ToyListResult(entries.isEmpty() ? result.summary()
				: result.summary() + "\n" + entries, result.toys(), result.locale());
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
			@NonNull McpRequestContext requestContext,
			@NonNull McpResourceListContext context) {
		requireNonNull(requestContext);
		requireNonNull(context);

		List<McpResourceDescriptor> resources = new ArrayList<>(getToyService().findToys().stream()
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
				.toList());
		// Registration descriptors are not authorization- or capability-filtered.
		// Admission has already required an MCP-audience token with mcp:read.
		if (requestContext.getClientCapabilities().supportsAppMimeType(CATALOG_APP_MIME_TYPE))
			context.getRegisteredResourceDescriptors().stream()
					.filter(resource -> URI.create(CATALOG_APP_URI).equals(resource.getUri()))
					.forEach(resources::add);

		return McpResourcePage.builder().resourceDescriptors(resources).build();
	}

	@NonNull
	@McpResource(
			uri = CATALOG_APP_URI,
			name = "toy_catalog_view",
			title = "Toy catalog view",
			mimeType = CATALOG_APP_MIME_TYPE,
			description = "Static read-only catalog interface. Catalog data arrives through authenticated tool results."
	)
	public McpResourceOutput catalogApp() {
		return McpResourceOutput.fromContent(McpTextResourceContents
				.withUriAndText(URI.create(CATALOG_APP_URI), CATALOG_APP_HTML)
				.mimeType(CATALOG_APP_MIME_TYPE)
				.appResourceMetadata(CATALOG_APP_METADATA).build());
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
	private static String catalogAppHtml() {
		try (InputStream input = ToyStoreMcpEndpoint.class.getResourceAsStream("/mcp/apps/catalog.html")) {
			return new String(requireNonNull(input, "Missing packaged catalog App").readAllBytes(),
					StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read the packaged catalog App.", exception);
		}
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

	@NonNull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	/** Typed MCP result for catalog searches. */
	public record ToyListResult(
			@NonNull String summary,
			@NonNull List<@NonNull ToyCatalogEntry> toys,
			@NonNull String locale) {
		public ToyListResult {
			requireNonNull(summary);
			toys = List.copyOf(requireNonNull(toys));
			requireNonNull(locale);
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

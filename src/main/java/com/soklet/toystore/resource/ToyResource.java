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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.soklet.MarshaledResponse;
import com.soklet.StreamingResponseBody;
import com.soklet.SseHandshakeResult;
import com.soklet.annotation.DELETE;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.SseEventSource;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.annotation.AuthorizationRequired;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.request.ToyPurchaseRequest;
import com.soklet.toystore.model.api.request.ToyUpdateRequest;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseFactory;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.model.db.Purchase;
import com.soklet.toystore.model.db.Role.RoleId;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.service.ToyService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Contains Toy-related Resource Methods.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyResource {
	@NonNull
	private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

	@NonNull
	private final ToyService toyService;
	@NonNull
	private final ToyResponseFactory toyResponseFactory;
	@NonNull
	private final PurchaseResponseFactory purchaseResponseFactory;
	@NonNull
	private final Provider<CurrentContext> currentContextProvider;
	@NonNull
	private final Gson gson;

	@Inject
	public ToyResource(@NonNull ToyService toyService,
										 @NonNull ToyResponseFactory toyResponseFactory,
										 @NonNull PurchaseResponseFactory purchaseResponseFactory,
										 @NonNull Provider<CurrentContext> currentContextProvider,
										 @NonNull Gson gson) {
		requireNonNull(toyService);
		requireNonNull(toyResponseFactory);
		requireNonNull(purchaseResponseFactory);
		requireNonNull(currentContextProvider);
		requireNonNull(gson);

		this.toyService = toyService;
		this.toyResponseFactory = toyResponseFactory;
		this.purchaseResponseFactory = purchaseResponseFactory;
		this.currentContextProvider = currentContextProvider;
		this.gson = gson;
	}

	@NonNull
	@GET("/toys")
	public ToysResponseHolder findToys(@Nullable @QueryParameter(optional = true) String query) {
		List<Toy> toys = query == null ? getToyService().findToys() : getToyService().searchToys(query);

		return new ToysResponseHolder(toys.stream()
				.map(toy -> getToyResponseFactory().create(toy))
				.collect(Collectors.toList()));
	}

	@NonNull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@GET("/toys/export.ndjson")
	public MarshaledResponse exportToys(@Nullable @QueryParameter(optional = true) String query) {
		List<ToyResponse> toys = (query == null ? getToyService().findToys() : getToyService().searchToys(query)).stream()
				.map(toy -> getToyResponseFactory().create(toy))
				.collect(Collectors.toList());

		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of(
						"Content-Type", Set.of("application/x-ndjson; charset=UTF-8"),
						"Cache-Control", Set.of("no-transform")
				))
				.stream(StreamingResponseBody.fromWriter((output, context) -> {
					for (ToyResponse toy : toys) {
						context.throwIfCanceled();
						output.write(compactJson(toy).getBytes(StandardCharsets.UTF_8));
						output.write(new byte[]{'\n'});
						output.flush();
					}
				}))
				.build();
	}

	@NonNull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@POST("/toys")
	public ToyResponseHolder createToy(@NonNull @RequestBody ToyCreateRequest request) {
		requireNonNull(request);

		UUID toyId = getToyService().createToy(request);
		Toy toy = getToyService().findToyById(toyId).get();

		return new ToyResponseHolder(getToyResponseFactory().create(toy));
	}

	@NonNull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@PUT("/toys/{toyId}")
	public ToyResponseHolder updateToy(@NonNull @PathParameter UUID toyId,
																		 @NonNull @RequestBody ToyUpdateRequest request) {
		requireNonNull(toyId);
		requireNonNull(request);

		Toy toy = getToyService().findToyById(toyId).orElse(null);

		if (toy == null)
			throw new NotFoundException();

		// Apply the path parameter to the record
		request = request.withToyId(toyId);

		getToyService().updateToy(request);
		Toy updatedToy = getToyService().findToyById(toyId).get();

		return new ToyResponseHolder(getToyResponseFactory().create(updatedToy));
	}

	@AuthorizationRequired(RoleId.ADMINISTRATOR)
	@DELETE("/toys/{toyId}")
	public void deleteToy(@NonNull @PathParameter UUID toyId) {
		requireNonNull(toyId);

		Toy toy = getToyService().findToyById(toyId).orElse(null);

		if (toy == null)
			throw new NotFoundException();

		getToyService().deleteToy(toyId);
	}

	@NonNull
	@AuthorizationRequired
	@POST("/toys/{toyId}/purchase")
	public PurchaseResponseHolder purchaseToy(@NonNull @PathParameter UUID toyId,
																						@NonNull @RequestBody ToyPurchaseRequest request) {
		requireNonNull(toyId);
		requireNonNull(request);

		Toy toy = getToyService().findToyById(toyId).orElse(null);

		if (toy == null)
			throw new NotFoundException();

		// Apply path parameter and current account to the record
		Account account = getCurrentContext().getAccount().get();
		request = request.withToyId(toyId).withAccountId(account.accountId());

		UUID purchaseId = getToyService().purchaseToy(request);
		Purchase purchase = getToyService().findPurchaseById(purchaseId).get();

		return new PurchaseResponseHolder(getPurchaseResponseFactory().create(purchase));
	}

	@NonNull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@SseEventSource("/toys/event-source")
	public SseHandshakeResult toysEventSource() {
		// Accept the handshake and store off the current context so we can appropriately localize our SSE broadcasts
		return SseHandshakeResult.acceptWithClientContext(getCurrentContext());
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
	private PurchaseResponseFactory getPurchaseResponseFactory() {
		return this.purchaseResponseFactory;
	}

	@NonNull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	@NonNull
	private Gson getGson() {
		return this.gson;
	}

	@NonNull
	private String compactJson(@NonNull Object value) {
		requireNonNull(value);

		return COMPACT_GSON.toJson(JsonParser.parseString(getGson().toJson(value)));
	}
}

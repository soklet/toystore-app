/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.soklet.HandshakeResult;
import com.soklet.annotation.DELETE;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.annotation.AuthorizationRequired;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.request.ToyPurchaseRequest;
import com.soklet.toystore.model.api.request.ToyUpdateRequest;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseFactory;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.model.db.Purchase;
import com.soklet.toystore.model.db.Role.RoleId;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.service.ToyService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
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
	@Nonnull
	private final ToyService toyService;
	@Nonnull
	private final ToyResponseFactory toyResponseFactory;
	@Nonnull
	private final PurchaseResponseFactory purchaseResponseFactory;
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public ToyResource(@Nonnull ToyService toyService,
										 @Nonnull ToyResponseFactory toyResponseFactory,
										 @Nonnull PurchaseResponseFactory purchaseResponseFactory,
										 @Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(toyService);
		requireNonNull(toyResponseFactory);
		requireNonNull(purchaseResponseFactory);
		requireNonNull(currentContextProvider);

		this.toyService = toyService;
		this.toyResponseFactory = toyResponseFactory;
		this.purchaseResponseFactory = purchaseResponseFactory;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@GET("/toys")
	public ToysResponseHolder findToys(@Nullable @QueryParameter(optional = true) String query) {
		List<Toy> toys = query == null ? getToyService().findToys() : getToyService().searchToys(query);

		return new ToysResponseHolder(toys.stream()
				.map(toy -> getToyResponseFactory().create(toy))
				.collect(Collectors.toList()));
	}

	@Nonnull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@POST("/toys")
	public ToyResponseHolder createToy(@Nonnull @RequestBody ToyCreateRequest request) {
		requireNonNull(request);

		UUID toyId = getToyService().createToy(request);
		Toy toy = getToyService().findToyById(toyId).get();

		return new ToyResponseHolder(getToyResponseFactory().create(toy));
	}

	@Nonnull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@PUT("/toys/{toyId}")
	public ToyResponseHolder updateToy(@Nonnull @PathParameter UUID toyId,
																		 @Nonnull @RequestBody ToyUpdateRequest request) {
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
	public void deleteToy(@Nonnull @PathParameter UUID toyId) {
		requireNonNull(toyId);

		Toy toy = getToyService().findToyById(toyId).orElse(null);

		if (toy == null)
			throw new NotFoundException();

		getToyService().deleteToy(toyId);
	}

	@Nonnull
	@AuthorizationRequired
	@POST("/toys/{toyId}/purchase")
	public PurchaseResponseHolder purchaseToy(@Nonnull @PathParameter UUID toyId,
																						@Nonnull @RequestBody ToyPurchaseRequest request) {
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

	@Nonnull
	@AuthorizationRequired(RoleId.ADMINISTRATOR)
	@ServerSentEventSource("/toys/event-source")
	public HandshakeResult toysEventSource() {
		// Accept the handshake and store off the current context so we can appropriately localize our SSE broadcasts
		return HandshakeResult.acceptWithDefaults()
				.clientContext(getCurrentContext())
				.build();
	}

	@Nonnull
	private ToyService getToyService() {
		return this.toyService;
	}

	@Nonnull
	private ToyResponseFactory getToyResponseFactory() {
		return this.toyResponseFactory;
	}

	@Nonnull
	private PurchaseResponseFactory getPurchaseResponseFactory() {
		return this.purchaseResponseFactory;
	}

	@Nonnull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}
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

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.soklet.annotation.DELETE;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.Resource;
import com.soklet.example.CurrentContext;
import com.soklet.example.annotation.AuthorizationRequired;
import com.soklet.example.exception.NotFoundException;
import com.soklet.example.model.api.request.ToyCreateRequest;
import com.soklet.example.model.api.request.ToyUpdateRequest;
import com.soklet.example.model.api.response.ToyResponse;
import com.soklet.example.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.example.model.db.Role.RoleId;
import com.soklet.example.model.db.Toy;
import com.soklet.example.service.ToyService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Resource
@ThreadSafe
public class ToyResource {
	@Nonnull
	private final ToyService toyService;
	@Nonnull
	private final ToyResponseFactory toyResponseFactory;
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public ToyResource(@Nonnull ToyService toyService,
										 @Nonnull ToyResponseFactory toyResponseFactory,
										 @Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(toyService);
		requireNonNull(toyResponseFactory);
		requireNonNull(currentContextProvider);

		this.toyService = toyService;
		this.toyResponseFactory = toyResponseFactory;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@AuthorizationRequired
	@GET("/toys")
	public ToysResponse findToys() {
		List<ToyResponse> toys = getToyService().findToys().stream()
				.map(toy -> getToyResponseFactory().create(toy))
				.collect(Collectors.toList());

		return new ToysResponse(toys);
	}

	public record ToysResponse(
			@Nonnull List<ToyResponse> toys
	) {
		public ToysResponse {
			requireNonNull(toys);
		}
	}

	@Nonnull
	@AuthorizationRequired({RoleId.EMPLOYEE, RoleId.ADMINISTRATOR})
	@POST("/toys")
	public ToyResponse createToy(@Nonnull @RequestBody ToyCreateRequest request) {
		requireNonNull(request);

		UUID toyId = getToyService().createToy(request);
		Toy toy = getToyService().findToyById(toyId).get();

		return getToyResponseFactory().create(toy);
	}

	@Nonnull
	@AuthorizationRequired
	@PUT("/toys/{toyId}")
	public ToyResponse updateToy(@Nonnull @PathParameter UUID toyId,
															 @Nonnull @RequestBody ToyUpdateRequest request) {
		requireNonNull(toyId);
		requireNonNull(request);

		Toy toyToUpdate = getToyService().findToyById(toyId).orElse(null);

		if (toyToUpdate == null)
			throw new NotFoundException();

		// Apply the path parameter to the record
		request = request.withToyId(toyId);

		getToyService().updateToy(request);
		Toy toy = getToyService().findToyById(toyId).get();

		return getToyResponseFactory().create(toy);
	}

	@AuthorizationRequired(RoleId.ADMINISTRATOR)
	@DELETE("/toys/{toyId}")
	public void deleteToy(@Nonnull @PathParameter UUID toyId) {
		requireNonNull(toyId);

		Toy toyToDelete = getToyService().findToyById(toyId).orElse(null);

		if (toyToDelete == null)
			throw new NotFoundException();

		getToyService().deleteToy(toyId);
	}

	@Nonnull
	protected ToyService getToyService() {
		return this.toyService;
	}

	@Nonnull
	protected ToyResponseFactory getToyResponseFactory() {
		return this.toyResponseFactory;
	}

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}
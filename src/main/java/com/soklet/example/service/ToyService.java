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

package com.soklet.example.service;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.pyranid.DatabaseException;
import com.pyranid.Transaction;
import com.soklet.example.CurrentContext;
import com.soklet.example.exception.ApplicationException;
import com.soklet.example.model.api.request.ToyCreateRequest;
import com.soklet.example.model.api.request.ToyUpdateRequest;
import com.soklet.example.model.db.Toy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.sql.Savepoint;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyService {
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;
	@Nonnull
	private final Database database;
	@Nonnull
	private final Strings strings;
	@Nonnull
	private final Logger logger;

	@Inject
	public ToyService(@Nonnull Provider<CurrentContext> currentContextProvider,
										@Nonnull Database database,
										@Nonnull Strings strings) {
		requireNonNull(currentContextProvider);
		requireNonNull(database);
		requireNonNull(strings);

		this.currentContextProvider = currentContextProvider;
		this.database = database;
		this.strings = strings;
		this.logger = LoggerFactory.getLogger(getClass());
	}

	@Nonnull
	public List<Toy> findToys() {
		return getDatabase().queryForList("""
				  SELECT *
				  FROM toy
				  ORDER BY name
				""", Toy.class);
	}

	@Nonnull
	public Optional<Toy> findToyById(@Nullable UUID toyId) {
		if (toyId == null)
			return Optional.empty();

		return getDatabase().queryForObject("""
				SELECT *
				FROM toy
				WHERE toy_id=?
				""", Toy.class, toyId);
	}

	@Nonnull
	public UUID createToy(@Nonnull ToyCreateRequest request) {
		requireNonNull(request);

		UUID toyId = UUID.randomUUID();
		String name = request.name() == null ? "" : request.name().trim();
		BigDecimal price = request.price();
		Currency currency = request.currency();
		Map<String, String> fieldErrors = new LinkedHashMap<>();

		if (name.length() == 0)
			fieldErrors.put("name", getStrings().get("Name is required."));

		if (price == null)
			fieldErrors.put("price", getStrings().get("Price is required."));
		else if (price.compareTo(BigDecimal.ZERO) < 0)
			fieldErrors.put("price", getStrings().get("Price cannot be negative."));

		if (currency == null)
			fieldErrors.put("currency", getStrings().get("Currency is required."));

		if (fieldErrors.size() > 0)
			throw ApplicationException.withStatusCode(422)
					.fieldErrors(fieldErrors)
					.build();

		// Make a savepoint in case there is a unique constraint violation (duplicate name)
		Transaction transaction = getDatabase().currentTransaction().get();
		Savepoint savepoint = transaction.createSavepoint();

		try {
			getDatabase().execute("""
					INSERT INTO toy (
						toy_id,
						name,
						price,
						currency
					) VALUES (?,?,?,?)
					""", toyId, request.name(), request.price(), request.currency());
		} catch (DatabaseException e) {
			// If this is a unique constraint violation on the 'name' field, handle it specially:
			// roll the transaction back to a known-good state and expose some details to the caller
			if (e.getMessage().contains("TOY_NAME_UNIQUE_IDX")) {
				getDatabase().currentTransaction().get().rollback(savepoint);
				throw ApplicationException.withStatusCode(422)
						.fieldErrors(Map.of("name", getStrings().get("Name '{{name}}' is already in use.", Map.of("name", name))))
						.build();
			} else {
				// Some other problem; just bubble out
				throw e;
			}
		}

		return toyId;
	}

	@Nonnull
	public Boolean updateToy(@Nonnull ToyUpdateRequest request) {
		requireNonNull(request);

		// TODO: validation

		return getDatabase().execute("""
				UPDATE toy
				SET name=?, price=?, currency=?
				WHERE toy_id=?
				""", request.name(), request.price(), request.currency(), request.toyId()) > 0;
	}

	@Nonnull
	public Boolean deleteToy(@Nonnull UUID toyId) {
		requireNonNull(toyId);
		return getDatabase().execute("DELETE FROM toy WHERE toy_id=?", toyId) > 0;
	}

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	@Nonnull
	protected Database getDatabase() {
		return this.database;
	}

	@Nonnull
	protected Strings getStrings() {
		return this.strings;
	}

	@Nonnull
	protected Logger getLogger() {
		return this.logger;
	}
}
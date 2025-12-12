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

package com.soklet.toystore.service;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.pyranid.DatabaseException;
import com.pyranid.TransactionResult;
import com.soklet.ResourcePath;
import com.soklet.ServerSentEvent;
import com.soklet.ServerSentEventBroadcaster;
import com.soklet.ServerSentEventServer;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.exception.ApplicationException;
import com.soklet.toystore.exception.ApplicationException.ErrorCollector;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.request.ToyPurchaseRequest;
import com.soklet.toystore.model.api.request.ToyUpdateRequest;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.db.Purchase;
import com.soklet.toystore.model.db.Toy;
import com.soklet.toystore.util.CreditCardProcessor;
import com.soklet.toystore.util.CreditCardProcessor.CreditCardPaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Business logic for toys.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyService {
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;
	@Nonnull
	private final ServerSentEventServer serverSentEventServer;
	@Nonnull
	private final CreditCardProcessor creditCardProcessor;
	@Nonnull
	private final ToyResponseFactory toyResponseFactory;
	@Nonnull
	private final PurchaseResponseFactory purchaseResponseFactory;
	@Nonnull
	private final Gson gson;
	@Nonnull
	private final Database database;
	@Nonnull
	private final Strings strings;
	@Nonnull
	private final Logger logger;

	@Inject
	public ToyService(@Nonnull Provider<CurrentContext> currentContextProvider,
										@Nonnull ServerSentEventServer serverSentEventServer,
										@Nonnull CreditCardProcessor creditCardProcessor,
										@Nonnull ToyResponseFactory toyResponseFactory,
										@Nonnull PurchaseResponseFactory purchaseResponseFactory,
										@Nonnull Gson gson,
										@Nonnull Database database,
										@Nonnull Strings strings) {
		requireNonNull(currentContextProvider);
		requireNonNull(serverSentEventServer);
		requireNonNull(creditCardProcessor);
		requireNonNull(toyResponseFactory);
		requireNonNull(purchaseResponseFactory);
		requireNonNull(gson);
		requireNonNull(database);
		requireNonNull(strings);

		this.currentContextProvider = currentContextProvider;
		this.serverSentEventServer = serverSentEventServer;
		this.creditCardProcessor = creditCardProcessor;
		this.toyResponseFactory = toyResponseFactory;
		this.purchaseResponseFactory = purchaseResponseFactory;
		this.gson = gson;
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
	public List<Toy> searchToys(@Nullable String query) {
		query = query == null ? "" : query.trim();

		if (query.length() == 0)
			return findToys();

		// Naïve "LIKE" search.
		// Avoids SQL injection by using parameterized query with "?"
		return getDatabase().queryForList("""
				  SELECT *
				  FROM toy
				  WHERE LOWER(name) LIKE CONCAT(LOWER(?), '%')
				  ORDER BY name
				""", Toy.class, query);
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
		ErrorCollector errorCollector = new ErrorCollector();

		if (name.length() == 0)
			errorCollector.addFieldError("name", getStrings().get("Name is required."));

		if (price == null)
			errorCollector.addFieldError("price", getStrings().get("Price is required."));
		else if (price.compareTo(BigDecimal.ZERO) < 0)
			errorCollector.addFieldError("price", getStrings().get("Price cannot be negative."));

		if (currency == null)
			errorCollector.addFieldError("currency", getStrings().get("Currency is required."));

		if (errorCollector.hasErrors())
			throw ApplicationException.withStatusCodeAndErrors(422, errorCollector).build();

		if (getLogger().isInfoEnabled())
			getLogger().info("Creating toy '{}', which costs {}", name, formatPriceForDisplay(price, currency));

		try {
			getDatabase().execute("""
					INSERT INTO toy (
						toy_id,
						name,
						price,
						currency
					) VALUES (?,?,?,?)
					""", toyId, name, price, currency);

			Toy toyToBroadcast = findToyById(toyId).get();

			broadcastServerSentEvent((@Nonnull BroadcastKey broadcastKey) -> {
				CurrentContext clientCurrentContext = CurrentContext.with(broadcastKey.locale(), broadcastKey.timeZone()).build();

				return clientCurrentContext.run(() ->
						ServerSentEvent.withEvent("toy-created")
								.data(getGson().toJson(Map.of("toy", getToyResponseFactory().create(toyToBroadcast))))
								.build()
				);
			});
		} catch (DatabaseException e) {
			// If this is a unique constraint violation on the 'name' field, handle it specially
			// by exposing a helpful message to the caller
			if (e.getMessage().contains("TOY_NAME_UNIQUE_IDX")) {
				throw ApplicationException.withStatusCode(422)
						.fieldErrors(Map.of(
								"name", List.of(getStrings().get("There is already a toy named '{{name}}'.", Map.of("name", name)))
						))
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

		UUID toyId = request.toyId();
		String name = request.name() == null ? "" : request.name().trim();
		BigDecimal price = request.price();
		Currency currency = request.currency();

		// Not shown: validation similar to createToy(ToyCreateRequest) above

		boolean updated = getDatabase().execute("""
				UPDATE toy
				SET name=?, price=?, currency=?
				WHERE toy_id=?
				""", name, price, currency, toyId) > 0;

		if (updated) {
			Toy toyToBroadcast = findToyById(toyId).get();

			broadcastServerSentEvent((@Nonnull BroadcastKey broadcastKey) -> {
				CurrentContext clientCurrentContext = CurrentContext.with(broadcastKey.locale(), broadcastKey.timeZone()).build();

				return clientCurrentContext.run(() ->
						ServerSentEvent.withEvent("toy-updated")
								.data(getGson().toJson(Map.of("toy", getToyResponseFactory().create(toyToBroadcast))))
								.build()
				);
			});
		}

		return updated;
	}

	@Nonnull
	public Boolean deleteToy(@Nonnull UUID toyId) {
		requireNonNull(toyId);

		Toy toy = findToyById(toyId).orElse(null);

		if (toy == null)
			return false;

		boolean deleted = getDatabase().execute("DELETE FROM toy WHERE toy_id=?", toyId) > 0;

		if (deleted) {
			broadcastServerSentEvent((@Nonnull BroadcastKey broadcastKey) -> {
				CurrentContext clientCurrentContext = CurrentContext.with(broadcastKey.locale(), broadcastKey.timeZone()).build();

				return clientCurrentContext.run(() ->
						ServerSentEvent.withEvent("toy-deleted")
								.data(getGson().toJson(Map.of("toy", getToyResponseFactory().create(toy))))
								.build()
				);
			});
		}

		return deleted;
	}

	@Nonnull
	public UUID purchaseToy(@Nonnull ToyPurchaseRequest request) {
		requireNonNull(request);

		UUID accountId = request.accountId();
		String creditCardNumber = request.creditCardNumber();
		YearMonth creditCardExpiration = request.creditCardExpiration();
		Toy toy = findToyById(request.toyId()).orElse(null);
		String creditCardTransactionId;
		ErrorCollector errorCollector = new ErrorCollector();

		if (accountId == null)
			errorCollector.addFieldError("accountId", getStrings().get("Account ID is required."));

		if (creditCardNumber == null)
			errorCollector.addFieldError("creditCardNumber", getStrings().get("Credit card number is required."));

		if (creditCardExpiration == null)
			errorCollector.addFieldError("creditCardExpiration", getStrings().get("Credit card expiration is required."));
		else if (creditCardExpiration.isBefore(YearMonth.now(getCurrentContext().getTimeZone())))
			errorCollector.addFieldError("creditCardExpiration", getStrings().get("Credit card is expired."));

		if (errorCollector.hasErrors())
			throw ApplicationException.withStatusCodeAndErrors(422, errorCollector).build();

		// Charge the card, then record the transaction.
		// NOTE: A real system would have more thorough recordkeeping in order to detect scenarios
		// where a card is charged but the recording of the transaction fails

		try {
			creditCardTransactionId = getCreditCardProcessor().makePayment(creditCardNumber, toy.price(), toy.currency());
		} catch (CreditCardPaymentException e) {
			throw ApplicationException.withStatusCodeAndGeneralError(422,
							getStrings().get("We were unable to charge {{amount}} to your credit card.",
									Map.of("amount", formatPriceForDisplay(toy.price(), toy.currency()))
							)
					)
					.metadata(Map.of("failureReason", e.getFailureReason()))
					.build();
		}

		UUID purchaseId = UUID.randomUUID();

		getDatabase().execute("""
				INSERT INTO purchase (
					purchase_id,
					account_id,
					toy_id,
					price,
					currency,
					credit_card_txn_id
				) VALUES (?,?,?,?,?,?)
				""", purchaseId, accountId, toy.toyId(), toy.price(), toy.currency(), creditCardTransactionId);

		Purchase purchaseToBroadcast = findPurchaseById(purchaseId).get();

		broadcastServerSentEvent((@Nonnull BroadcastKey broadcastKey) -> {
			CurrentContext clientCurrentContext = CurrentContext.with(broadcastKey.locale(), broadcastKey.timeZone()).build();

			return clientCurrentContext.run(() ->
					ServerSentEvent.withEvent("toy-purchased")
							.data(getGson().toJson(Map.of(
									"toy", getToyResponseFactory().create(toy),
									"purchase", getPurchaseResponseFactory().create(purchaseToBroadcast)
							)))
							.build()
			);
		});

		return purchaseId;
	}

	@Nonnull
	public Optional<Purchase> findPurchaseById(@Nullable UUID purchaseId) {
		if (purchaseId == null)
			return Optional.empty();

		return getDatabase().queryForObject("""
				SELECT *
				FROM purchase
				WHERE purchase_id=?
				""", Purchase.class, purchaseId);
	}

	private void broadcastServerSentEvent(@Nonnull Function<BroadcastKey, ServerSentEvent> serverSentEventProvider) {
		requireNonNull(serverSentEventProvider);

		// Once this transaction successfully commits, fire off a Server-Sent Event to inform listeners.
		// Note: distributed systems would put the Server-Sent Event on an event bus so each node can consume and broadcast to its own SSE connections
		getDatabase().currentTransaction().get().addPostTransactionOperation((TransactionResult transactionResult) -> {
			if (transactionResult == TransactionResult.COMMITTED) {
				ResourcePath resourcePath = ResourcePath.withPath("/toys/event-source");
				ServerSentEventBroadcaster serverSentEventBroadcaster = getServerSentEventServer().acquireBroadcaster(resourcePath).get();

				// Instead of broadcasting the same message to everyone via #broadcastEvent(ServerSentEvent), we create separate broadcasts
				// based on client-specific context.  For example, we should broadcast Brazilian Portuguese to clients who had pt-BR locale
				// at SSE handshake time.

				// First, define how we convert an SSE connection's client context object (if available) into a BroadcastKey
				Function<Object, BroadcastKey> broadcastKeySelector = (@Nullable Object clientContext) -> {
					CurrentContext currentContext = (CurrentContext) clientContext;

					if (currentContext == null)
						return new BroadcastKey(Configuration.getDefaultLocale(), Configuration.getDefaultTimeZone());

					return new BroadcastKey(currentContext.getLocale(), currentContext.getTimeZone());
				};

				// Then, define how we convert that BroadcastKey into a Server-Sent Event
				Function<BroadcastKey, ServerSentEvent> serverSentEventGenerator = (@Nonnull BroadcastKey broadcastKey) -> {
					ServerSentEvent serverSentEvent = serverSentEventProvider.apply(broadcastKey);
					getLogger().debug("Performing SSE Broadcast on {} with {}...", resourcePath.getPath(), serverSentEvent);
					return serverSentEvent;
				};

				// With those two methods, we can now efficiently broadcast the event to all locale/timezone combinations
				serverSentEventBroadcaster.broadcastEvent(broadcastKeySelector, serverSentEventGenerator);
			}
		});
	}

	private record BroadcastKey(
			@Nonnull Locale locale,
			@Nonnull ZoneId timeZone
	) {
		public BroadcastKey {
			requireNonNull(locale);
			requireNonNull(timeZone);
		}
	}

	@Nonnull
	private String formatPriceForDisplay(@Nonnull BigDecimal price,
																			 @Nonnull Currency currency) {
		requireNonNull(price);
		requireNonNull(currency);

		NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(getCurrentContext().getLocale());
		currencyFormatter.setCurrency(currency);
		return currencyFormatter.format(price);
	}

	@Nonnull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	@Nonnull
	private ServerSentEventServer getServerSentEventServer() {
		return this.serverSentEventServer;
	}

	@Nonnull
	private CreditCardProcessor getCreditCardProcessor() {
		return this.creditCardProcessor;
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
	private Gson getGson() {
		return this.gson;
	}

	@Nonnull
	private Database getDatabase() {
		return this.database;
	}

	@Nonnull
	private Strings getStrings() {
		return this.strings;
	}

	@Nonnull
	private Logger getLogger() {
		return this.logger;
	}
}
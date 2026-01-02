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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import static com.soklet.toystore.util.Normalizer.trimAggressivelyToNull;
import static com.soklet.toystore.util.Validator.isValidCreditCardNumber;
import static java.util.Objects.requireNonNull;

/**
 * Business logic for toys.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyService {
	@NonNull
	private final Provider<CurrentContext> currentContextProvider;
	@NonNull
	private final ServerSentEventServer serverSentEventServer;
	@NonNull
	private final CreditCardProcessor creditCardProcessor;
	@NonNull
	private final ToyResponseFactory toyResponseFactory;
	@NonNull
	private final PurchaseResponseFactory purchaseResponseFactory;
	@NonNull
	private final Gson gson;
	@NonNull
	private final Database database;
	@NonNull
	private final Strings strings;
	@NonNull
	private final Logger logger;

	@Inject
	public ToyService(@NonNull Provider<CurrentContext> currentContextProvider,
										@NonNull ServerSentEventServer serverSentEventServer,
										@NonNull CreditCardProcessor creditCardProcessor,
										@NonNull ToyResponseFactory toyResponseFactory,
										@NonNull PurchaseResponseFactory purchaseResponseFactory,
										@NonNull Gson gson,
										@NonNull Database database,
										@NonNull Strings strings) {
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

	@NonNull
	public List<@NonNull Toy> findToys() {
		return getDatabase().query("""
						  SELECT *
						  FROM toy
						  ORDER BY name
						""")
				.fetchList(Toy.class);
	}

	@NonNull
	public List<@NonNull Toy> searchToys(@Nullable String query) {
		query = trimAggressivelyToNull(query);

		if (query == null)
			return findToys();

		// Naïve "LIKE" search (a real system would use Postgres' advanced search features or a separate search engine).
		// Avoids SQL injection by using parameterized query with "?"
		return getDatabase().query("""
						  SELECT *
						  FROM toy
						  WHERE LOWER(name) LIKE CONCAT(LOWER(:query), '%')
						  ORDER BY name
						""")
				.bind("query", query)
				.fetchList(Toy.class);
	}

	@NonNull
	public Optional<Toy> findToyById(@Nullable UUID toyId) {
		if (toyId == null)
			return Optional.empty();

		return getDatabase().query("""
						SELECT *
						FROM toy
						WHERE toy_id=:toyId
						""")
				.bind("toyId", toyId)
				.fetchObject(Toy.class);
	}

	@NonNull
	public UUID createToy(@NonNull ToyCreateRequest request) {
		requireNonNull(request);

		UUID toyId = UUID.randomUUID();
		String name = trimAggressivelyToNull(request.name());
		BigDecimal price = request.price();
		Currency currency = request.currency();
		ErrorCollector errorCollector = new ErrorCollector();

		if (name == null)
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
			getDatabase().query("""
							INSERT INTO toy (
								toy_id,
								name,
								price,
								currency
							) VALUES (:toyId, :name, :price, :currency)
							""")
					.bind("toyId", toyId)
					.bind("name", name)
					.bind("price", price)
					.bind("currency", currency)
					.execute();

			Toy toyToBroadcast = findToyById(toyId).get();

			broadcastServerSentEvent((@NonNull BroadcastKey broadcastKey) -> {
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

	@NonNull
	public Boolean updateToy(@NonNull ToyUpdateRequest request) {
		requireNonNull(request);

		UUID toyId = request.toyId();
		String name = trimAggressivelyToNull(request.name());
		BigDecimal price = request.price();
		Currency currency = request.currency();

		// Not shown: validation similar to createToy(ToyCreateRequest) above

		boolean updated = getDatabase().query("""
						UPDATE toy
						SET name=:name, price=:price, currency=:currency
						WHERE toy_id=:toyId
						""")
				.bind("name", name)
				.bind("price", price)
				.bind("currency", currency)
				.bind("toyId", toyId)
				.execute() > 0;

		if (updated) {
			Toy toyToBroadcast = findToyById(toyId).get();

			broadcastServerSentEvent((@NonNull BroadcastKey broadcastKey) -> {
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

	@NonNull
	public Boolean deleteToy(@NonNull UUID toyId) {
		requireNonNull(toyId);

		Toy toy = findToyById(toyId).orElse(null);

		if (toy == null)
			return false;

		boolean deleted = getDatabase().query("DELETE FROM toy WHERE toy_id=:toyId")
				.bind("toyId", toyId)
				.execute() > 0;

		if (deleted) {
			broadcastServerSentEvent((@NonNull BroadcastKey broadcastKey) -> {
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

	@NonNull
	public UUID purchaseToy(@NonNull ToyPurchaseRequest request) {
		requireNonNull(request);

		UUID accountId = request.accountId();
		String creditCardNumber = trimAggressivelyToNull(request.creditCardNumber());
		YearMonth creditCardExpiration = request.creditCardExpiration();
		Toy toy = findToyById(request.toyId()).orElse(null);
		String creditCardTransactionId;
		ErrorCollector errorCollector = new ErrorCollector();

		if (accountId == null)
			errorCollector.addFieldError("accountId", getStrings().get("Account ID is required."));

		if (creditCardNumber == null)
			errorCollector.addFieldError("creditCardNumber", getStrings().get("Credit card number is required."));
		else if (!isValidCreditCardNumber(creditCardNumber))
			errorCollector.addFieldError("creditCardNumber", getStrings().get("Credit card number is invalid."));

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

		getDatabase().query("""
						INSERT INTO purchase (
							purchase_id,
							account_id,
							toy_id,
							price,
							currency,
							credit_card_txn_id
						) VALUES (:purchaseId, :accountId, :toyId, :price, :currency, :creditCardTransactionId)
						""")
				.bind("purchaseId", purchaseId)
				.bind("accountId", accountId)
				.bind("toyId", toy.toyId())
				.bind("price", toy.price())
				.bind("currency", toy.currency())
				.bind("creditCardTransactionId", creditCardTransactionId)
				.execute();

		Purchase purchaseToBroadcast = findPurchaseById(purchaseId).get();

		broadcastServerSentEvent((@NonNull BroadcastKey broadcastKey) -> {
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

	@NonNull
	public Optional<Purchase> findPurchaseById(@Nullable UUID purchaseId) {
		if (purchaseId == null)
			return Optional.empty();

		return getDatabase().query("""
						SELECT *
						FROM purchase
						WHERE purchase_id=:purchaseId
						""")
				.bind("purchaseId", purchaseId)
				.fetchObject(Purchase.class);
	}

	private void broadcastServerSentEvent(@NonNull Function<BroadcastKey, ServerSentEvent> serverSentEventProvider) {
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
				Function<BroadcastKey, ServerSentEvent> serverSentEventGenerator = (@NonNull BroadcastKey broadcastKey) -> {
					ServerSentEvent serverSentEvent = serverSentEventProvider.apply(broadcastKey);
					getLogger().debug("Performing SSE Broadcast on {} with {}...", resourcePath.getPath(), serverSentEvent);
					return serverSentEvent;
				};

				// With those two methods, we can now efficiently broadcast the event to all locale/timezone combinations
				serverSentEventBroadcaster.broadcastEvent(broadcastKeySelector, serverSentEventGenerator);
			}
		});
	}

	// Key used for unique SSE broadcasts.
	// If there are 1000 SSE connections and 999 are `en-US` in `America/New_York`, then there are only 2 keys
	// (meaning we only need to compute 2 payloads, not 1000).
	private record BroadcastKey(
			@NonNull Locale locale,
			@NonNull ZoneId timeZone
	) {
		public BroadcastKey {
			requireNonNull(locale);
			requireNonNull(timeZone);
		}
	}

	@NonNull
	private String formatPriceForDisplay(@NonNull BigDecimal price,
																			 @NonNull Currency currency) {
		requireNonNull(price);
		requireNonNull(currency);

		NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(getCurrentContext().getLocale());
		currencyFormatter.setCurrency(currency);
		return currencyFormatter.format(price);
	}

	@NonNull
	private CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}

	@NonNull
	private ServerSentEventServer getServerSentEventServer() {
		return this.serverSentEventServer;
	}

	@NonNull
	private CreditCardProcessor getCreditCardProcessor() {
		return this.creditCardProcessor;
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
	private Gson getGson() {
		return this.gson;
	}

	@NonNull
	private Database getDatabase() {
		return this.database;
	}

	@NonNull
	private Strings getStrings() {
		return this.strings;
	}

	@NonNull
	private Logger getLogger() {
		return this.logger;
	}
}

/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.lokalized.LocaleMatcher;
import com.lokalized.LocalizedStringLoader;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.pyranid.InstanceProvider;
import com.pyranid.StatementContext;
import com.pyranid.StatementLog;
import com.pyranid.StatementLogger;
import com.soklet.SokletConfiguration;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestBodyMarshaler;
import com.soklet.core.ResourceMethod;
import com.soklet.core.Response;
import com.soklet.core.Server;
import com.soklet.core.impl.DefaultResponseMarshaler;
import com.soklet.core.impl.DefaultServer;
import com.soklet.core.impl.WhitelistedOriginsCorsAuthorizer;
import com.soklet.example.annotation.AuthorizationRequired;
import com.soklet.example.exception.ApplicationException;
import com.soklet.example.exception.AuthenticationException;
import com.soklet.example.exception.AuthorizationException;
import com.soklet.example.exception.NotFoundException;
import com.soklet.example.model.api.response.AccountResponse.AccountResponseFactory;
import com.soklet.example.model.api.response.ErrorResponse;
import com.soklet.example.model.api.response.PurchaseResponse.PurchaseResponseFactory;
import com.soklet.example.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.example.model.auth.AccountJwt;
import com.soklet.example.model.auth.AccountJwt.AccountJwtResult;
import com.soklet.example.model.auth.AccountJwt.AccountJwtResult.Expired;
import com.soklet.example.model.auth.AccountJwt.AccountJwtResult.SignatureMismatch;
import com.soklet.example.model.auth.AccountJwt.AccountJwtResult.Succeeded;
import com.soklet.example.model.db.Account;
import com.soklet.example.model.db.Role.RoleId;
import com.soklet.example.service.AccountService;
import com.soklet.example.util.CreditCardProcessor;
import com.soklet.example.util.DefaultCreditCardProcessor;
import com.soklet.example.util.PasswordManager;
import com.soklet.exception.BadRequestException;
import com.soklet.exception.IllegalQueryParameterException;
import org.hsqldb.jdbc.JDBCDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AppModule extends AbstractModule {
	@Nonnull
	private final Configuration configuration;

	public AppModule(@Nonnull Configuration configuration) {
		requireNonNull(configuration);
		this.configuration = configuration;
	}

	@Nonnull
	@Provides
	@Singleton
	public Configuration provideConfiguration() {
		return this.configuration;
	}

	@Nonnull
	@Provides
	@Singleton
	public SokletConfiguration provideSokletConfiguration(@Nonnull Injector injector,
																												@Nonnull Configuration configuration,
																												@Nonnull Database database,
																												@Nonnull AccountService accountService,
																												@Nonnull Strings strings,
																												@Nonnull Gson gson) {
		requireNonNull(injector);
		requireNonNull(configuration);
		requireNonNull(database);
		requireNonNull(accountService);
		requireNonNull(strings);
		requireNonNull(gson);

		return SokletConfiguration.withServer(DefaultServer.withPort(configuration.getPort()).host("0.0.0.0").build())
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Nonnull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.example.LifecycleInterceptor");

					@Override
					public void didStartRequestHandling(@Nonnull Request request,
																							@Nullable ResourceMethod resourceMethod) {
						logger.debug("Received {} {}", request.getHttpMethod(), request.getUri());
					}

					@Override
					public void didFinishRequestHandling(@Nonnull Request request,
																							 @Nullable ResourceMethod resourceMethod,
																							 @Nonnull MarshaledResponse marshaledResponse,
																							 @Nonnull Duration processingDuration,
																							 @Nonnull List<Throwable> throwables) {
						logger.debug(format("Finished processing %s %s (HTTP %d) in %.2fms", request.getHttpMethod(),
								request.getUri(), marshaledResponse.getStatusCode(), processingDuration.toNanos() / 1000000.0));
					}

					@Override
					public void didStartServer(@Nonnull Server server) {
						logger.debug("Server started in {} environment on port {}", configuration.getEnvironment(), configuration.getPort());
					}

					@Override
					public void didStopServer(@Nonnull Server server) {
						logger.debug("Server stopped.");
					}

					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
						requireNonNull(logEvent);
						logger.warn(logEvent.getMessage(), logEvent.getThrowable().orElse(null));
					}

					@Override
					public void wrapRequest(@Nonnull Request request,
																	@Nullable ResourceMethod resourceMethod,
																	@Nonnull Consumer<Request> requestProcessor) {
						requireNonNull(request);
						requireNonNull(requestProcessor);

						// Ensure a "current context" scope exists for all request-handling code
						CurrentContext.withRequest(request).build().run(() -> {
							requestProcessor.accept(request);
						});
					}

					@Override
					public void interceptRequest(@Nonnull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @Nonnull Function<Request, MarshaledResponse> responseGenerator,
																			 @Nonnull Consumer<MarshaledResponse> responseWriter) {
						requireNonNull(request);
						requireNonNull(responseGenerator);
						requireNonNull(responseWriter);

						Account account = null;

						// Try to pull authentication token from request headers...
						String authenticationTokenAsString = request.getHeader("X-Authentication-Token").orElse(null);

						// ...and if it exists, see if we can pull an account from it.
						if (authenticationTokenAsString != null) {
							AccountJwtResult accountJwtResult = AccountJwt.fromStringRepresentation(authenticationTokenAsString, configuration.getKeyPair().getPrivate());

							switch (accountJwtResult) {
								case Succeeded(@Nonnull AccountJwt accountJwt) -> {
									account = accountService.findAccountById(accountJwt.accountId()).orElse(null);
								}
								case Expired(@Nonnull AccountJwt accountJwt, @Nonnull Instant expiredAt) -> {
									logger.debug("JWT for account ID {} expired at {}", accountJwt.accountId(), expiredAt);
								}
								case SignatureMismatch() -> {
									logger.warn("JWT signature is invalid: {}", authenticationTokenAsString);
								}
								default -> {
									logger.warn("JWT is invalid: {}", authenticationTokenAsString);
								}
							}
						}

						if (resourceMethod != null) {
							// See if the resource method has an @AuthorizationRequired annotation...
							AuthorizationRequired authorizationRequired = resourceMethod.getMethod().getAnnotation(AuthorizationRequired.class);

							if (authorizationRequired != null) {
								// Ensure an account was found for the authentication token
								if (account == null)
									throw new AuthenticationException();

								Set<RoleId> requiredRoleIds = authorizationRequired.value() == null
										? Set.of() : Arrays.stream(authorizationRequired.value()).collect(Collectors.toSet());

								if (requiredRoleIds.size() > 0 && !requiredRoleIds.contains(account.roleId()))
									throw new AuthorizationException();
							}
						}

						// Create a new current context scope with the authenticated account (if present)
						CurrentContext currentContext = CurrentContext.withRequest(request)
								.account(account)
								.build();

						currentContext.run(() -> {
							// Wrap the resource method execution (not including the writing of bytes over the wire) in a database transaction.
							// If an exception occurs during this process, the transaction will roll back.
							// This is the behavior you normally want.
							MarshaledResponse marshaledResponse = database.transaction(() ->
									Optional.of(responseGenerator.apply(request))
							).get();

							responseWriter.accept(marshaledResponse);
						});
					}
				})
				.requestBodyMarshaler(new RequestBodyMarshaler() {
					@Nonnull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.example.RequestBodyMarshaler");

					@Nullable
					@Override
					public Optional<Object> marshalRequestBody(@Nonnull Request request,
																										 @Nonnull ResourceMethod resourceMethod,
																										 @Nonnull Parameter parameter,
																										 @Nonnull Type requestBodyType) {
						requireNonNull(request);
						requireNonNull(requestBodyType);

						String requestBodyAsString = request.getBodyAsString().orElse(null);

						if (requestBodyAsString == null)
							return Optional.empty();

						logger.debug("Request body:\n{}", requestBodyAsString);

						// Use Gson to turn the request body JSON into a Java type
						return Optional.of(gson.fromJson(requestBodyAsString, requestBodyType));
					}
				})
				.responseMarshaler(new DefaultResponseMarshaler() {
					@Nonnull
					@Override
					public MarshaledResponse forHappyPath(@Nonnull Request request,
																								@Nonnull Response response,
																								@Nonnull ResourceMethod resourceMethod) {
						// Use Gson to turn response objects into JSON to go over the wire
						Object bodyObject = response.getBody().orElse(null);
						byte[] body = bodyObject == null ? null : gson.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

						// Ensure content type header is set
						Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return MarshaledResponse.withStatusCode(response.getStatusCode())
								.headers(headers)
								.cookies(response.getCookies())
								.body(body)
								.build();
					}

					@Nonnull
					@Override
					public MarshaledResponse forNotFound(@Nonnull Request request) {
						// Use Gson to turn the error response into JSON
						ErrorResponse errorResponse = ErrorResponse.withSummary(strings.get("The resource you requested was not found.")).build();
						byte[] body = gson.toJson(errorResponse).getBytes(StandardCharsets.UTF_8);

						Map<String, Set<String>> headers = new HashMap<>();
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return MarshaledResponse.withStatusCode(404)
								.headers(headers)
								.body(body)
								.build();
					}

					@Nonnull
					@Override
					public MarshaledResponse forThrowable(@Nonnull Request request,
																								@Nonnull Throwable throwable,
																								@Nullable ResourceMethod resourceMethod) {
						// Collect error information for display to client
						int statusCode;
						List<String> generalErrors = new ArrayList<>();
						Map<String, String> fieldErrors = new LinkedHashMap<>();
						Map<String, Object> metadata = new LinkedHashMap<>();

						switch (throwable) {
							case IllegalQueryParameterException ex -> {
								statusCode = 400;
								generalErrors.add(strings.get("Illegal value '{{parameterValue}}' specified for query parameter '{{parameterName}}'",
										Map.of(
												"parameterValue", ex.getQueryParameterValue().orElse(strings.get("[not provided]")),
												"parameterName", ex.getQueryParameterName()
										)
								));
							}
							case BadRequestException ignored -> {
								statusCode = 400;
								generalErrors.add(strings.get("Your request was improperly formatted."));
							}
							case AuthenticationException ignored -> {
								statusCode = 401;
								generalErrors.add(strings.get("You must be authenticated to perform this action."));
							}
							case AuthorizationException ignored -> {
								statusCode = 403;
								generalErrors.add(strings.get("You are not authorized to perform this action."));
							}
							case NotFoundException ignored -> {
								statusCode = 404;
								generalErrors.add(strings.get("The resource you requested was not found."));
							}
							case ApplicationException applicationException -> {
								statusCode = applicationException.getStatusCode();
								generalErrors.addAll(applicationException.getGeneralErrors());
								fieldErrors.putAll(applicationException.getFieldErrors());
								metadata.putAll(applicationException.getMetadata());
							}
							default -> {
								statusCode = 500;
								generalErrors.add(strings.get("An unexpected error occurred."));
							}
						}

						// Combine all the error messages into one field for easy access by clients
						String summary = format("%s %s",
								generalErrors.stream().collect(Collectors.joining(" ")),
								fieldErrors.values().stream().collect(Collectors.joining(" "))
						).trim();

						// Ensure there is always a summary
						if (summary.length() == 0)
							summary = strings.get("An unexpected error occurred.");

						// Collect all the error information into an object for transport over the wire
						ErrorResponse errorResponse = ErrorResponse.withSummary(summary)
								.generalErrors(generalErrors)
								.fieldErrors(fieldErrors)
								.metadata(metadata)
								.build();

						// Use Gson to turn the error response into JSON
						byte[] body = gson.toJson(errorResponse).getBytes(StandardCharsets.UTF_8);

						Map<String, Set<String>> headers = new HashMap<>();
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return MarshaledResponse.withStatusCode(statusCode)
								.headers(headers)
								.body(body)
								.build();
					}
				})
				.corsAuthorizer(new WhitelistedOriginsCorsAuthorizer(configuration.getCorsWhitelistedOrigins()))
				// Use Google Guice when Soklet needs to vend instances
				.instanceProvider(injector::getInstance)
				.build();
	}

	@Nonnull
	@Provides
	public CurrentContext provideCurrentContext() {
		return CurrentContext.get();
	}

	@Nonnull
	@Provides
	@Singleton
	public Database provideDatabase(@Nonnull Injector injector) {
		requireNonNull(injector);

		// Example in-memory datasource for HSQLDB
		JDBCDataSource dataSource = new JDBCDataSource();
		dataSource.setUrl("jdbc:hsqldb:mem:example");
		dataSource.setUser("sa");
		dataSource.setPassword("");

		// Use Pyranid to simplify JDBC operations
		return Database.withDataSource(dataSource)
				// Use Google Guice when Pyranid needs to vend instances
				.instanceProvider(new InstanceProvider() {
					@Override
					@Nonnull
					public <T> T provide(@Nonnull StatementContext<T> statementContext,
															 @Nonnull Class<T> instanceType) {
						return injector.getInstance(instanceType);
					}
				})
				.statementLogger(new StatementLogger() {
					@Nonnull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.example.StatementLogger");

					@Override
					public void log(@Nonnull StatementLog statementLog) {
						if (logger.isTraceEnabled())
							logger.trace("SQL took {}ms:\n{}\nParameters: {}", format("%.2f", statementLog.getTotalDuration().toNanos() / 1000000.0),
									statementLog.getStatementContext().getStatement().getSql().stripIndent().trim(),
									statementLog.getStatementContext().getParameters());
					}
				})
				.build();
	}

	@Nonnull
	@Provides
	@Singleton
	public Strings provideStrings(@Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(currentContextProvider);

		return Strings.withFallbackLocale(Locale.forLanguageTag("en-US"))
				.localizedStringSupplier(() -> LocalizedStringLoader.loadFromFilesystem(Paths.get("strings")))
				.localeSupplier((LocaleMatcher localeMatcher) -> {
					// Using the current context's preferred locale as a hint, pick the best-matching strings file
					Locale locale = currentContextProvider.get().getLocale();
					return localeMatcher.bestMatchFor(locale);
				})
				.build();
	}

	@Nonnull
	@Provides
	@Singleton
	public PasswordManager providePasswordManager() {
		// Because this is a sample system, we choose fast but less secure values.
		// Real systems should increase per comments below.
		return PasswordManager.withHashAlgorithm("PBKDF2WithHmacSHA512")
				.rngAlgorithm("SHA1PRNG")
				.iterations(5_000) // OWASP 2023 recommends 210_000 instead
				.saltLength(16) // Larger value is recommended, e.g. 64
				.keyLength(128 * 8) // Larger value is recommended, e.g. 128 * 16
				.build();
	}

	@Nonnull
	@Provides
	@Singleton
	public CreditCardProcessor provideCreditCardProcessor() {
		return new DefaultCreditCardProcessor();
	}

	@Nonnull
	@Provides
	@Singleton
	public Gson provideGson(@Nonnull Configuration configuration) {
		requireNonNull(configuration);

		GsonBuilder gsonBuilder = new GsonBuilder()
				.setPrettyPrinting()
				.disableHtmlEscaping()
				// Support `Locale` type for handling locales
				.registerTypeAdapter(Locale.class, new TypeAdapter<Locale>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull Locale locale) throws IOException {
						jsonWriter.value(locale.toLanguageTag());
					}

					@Override
					public Locale read(@Nonnull JsonReader jsonReader) throws IOException {
						return Locale.forLanguageTag(jsonReader.nextString());
					}
				})
				// Support `ZoneId` type for handling timezones
				.registerTypeAdapter(ZoneId.class, new TypeAdapter<ZoneId>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull ZoneId zoneId) throws IOException {
						jsonWriter.value(zoneId.getId());
					}

					@Override
					@Nullable
					public ZoneId read(@Nonnull JsonReader jsonReader) throws IOException {
						return ZoneId.of(jsonReader.nextString());
					}
				})
				// Use ISO formatting for Instants
				.registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull Instant instant) throws IOException {
						jsonWriter.value(instant.toString());
					}

					@Override
					@Nullable
					public Instant read(@Nonnull JsonReader jsonReader) throws IOException {
						return Instant.parse(jsonReader.nextString());
					}
				})
				// Use ISO formatting for YearMonths
				.registerTypeAdapter(YearMonth.class, new TypeAdapter<YearMonth>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull YearMonth yearMonth) throws IOException {
						jsonWriter.value(yearMonth.toString());
					}

					@Override
					@Nullable
					public YearMonth read(@Nonnull JsonReader jsonReader) throws IOException {
						return YearMonth.parse(jsonReader.nextString());
					}
				})
				// Convert our custom AccountJwt to and from a JSON string
				.registerTypeAdapter(AccountJwt.class, new TypeAdapter<AccountJwt>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull AccountJwt accountJwt) throws IOException {
						jsonWriter.value(accountJwt.toStringRepresentation(configuration.getKeyPair().getPrivate()));
					}

					@Override
					@Nullable
					public AccountJwt read(@Nonnull JsonReader jsonReader) throws IOException {
						AccountJwtResult accountJwtResult = AccountJwt.fromStringRepresentation(jsonReader.nextString(), configuration.getKeyPair().getPrivate());

						switch (accountJwtResult) {
							case Succeeded(@Nonnull AccountJwt accountJwt) -> {
								return accountJwt;
							}
							case Expired(@Nonnull AccountJwt accountJwt, @Nonnull Instant expiredAt) -> {
								return accountJwt;
							}
							default -> {
								return null;
							}
						}
					}
				});

		return gsonBuilder.create();
	}

	@Override
	protected void configure() {
		// Tells Guice to set up assisted injection
		// See https://github.com/google/guice/wiki/AssistedInject
		install(new FactoryModuleBuilder().build(AccountResponseFactory.class));
		install(new FactoryModuleBuilder().build(ToyResponseFactory.class));
		install(new FactoryModuleBuilder().build(PurchaseResponseFactory.class));
	}
}
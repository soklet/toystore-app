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

package com.soklet.toystore;

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
import com.soklet.CorsAuthorizer;
import com.soklet.HttpMethod;
import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.RequestBodyMarshaler;
import com.soklet.RequestInterceptor;
import com.soklet.ResourceMethod;
import com.soklet.Response;
import com.soklet.ResponseMarshaler;
import com.soklet.Server;
import com.soklet.ServerSentEventConnection;
import com.soklet.ServerSentEventConnection.TerminationReason;
import com.soklet.ServerSentEventServer;
import com.soklet.ServerType;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.exception.BadRequestException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.toystore.annotation.AuthorizationRequired;
import com.soklet.toystore.annotation.SuppressRequestLogging;
import com.soklet.toystore.exception.ApplicationException;
import com.soklet.toystore.exception.AuthenticationException;
import com.soklet.toystore.exception.AuthorizationException;
import com.soklet.toystore.exception.NotFoundException;
import com.soklet.toystore.mock.MockCreditCardProcessor;
import com.soklet.toystore.mock.MockErrorReporter;
import com.soklet.toystore.mock.MockSecretsManager;
import com.soklet.toystore.model.api.response.AccountResponse.AccountResponseFactory;
import com.soklet.toystore.model.api.response.ErrorResponse;
import com.soklet.toystore.model.api.response.PurchaseResponse.PurchaseResponseFactory;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseFactory;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.AccessTokenResult;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.model.db.Account;
import com.soklet.toystore.model.db.Role.RoleId;
import com.soklet.toystore.service.AccountService;
import com.soklet.toystore.util.CreditCardProcessor;
import com.soklet.toystore.util.ErrorReporter;
import com.soklet.toystore.util.PasswordManager;
import com.soklet.toystore.util.SecretsManager;
import com.soklet.toystore.util.SensitiveValueRedactor;
import org.hsqldb.jdbc.JDBCDataSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
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
	@NonNull
	private final Configuration configuration;

	public AppModule(@NonNull Configuration configuration) {
		requireNonNull(configuration);
		this.configuration = configuration;
	}

	@NonNull
	@Provides
	@Singleton
	public Configuration provideConfiguration() {
		return this.configuration;
	}

	@NonNull
	@Provides
	@Singleton
	public SokletConfig provideSokletConfig(@NonNull Injector injector,
																					@NonNull Configuration configuration,
																					@NonNull Database database,
																					@NonNull AccountService accountService,
																					@NonNull SensitiveValueRedactor sensitiveValueRedactor,
																					@NonNull Strings strings,
																					@NonNull Gson gson) {
		requireNonNull(injector);
		requireNonNull(configuration);
		requireNonNull(database);
		requireNonNull(accountService);
		requireNonNull(sensitiveValueRedactor);
		requireNonNull(strings);
		requireNonNull(gson);

		return SokletConfig.withServer(Server.withPort(configuration.getPort()).build())
				.serverSentEventServer(ServerSentEventServer.withPort(configuration.getServerSentEventPort()).build())
				.lifecycleObserver(new LifecycleObserver() {
					@NonNull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.toystore.LifecycleObserver");

					@Override
					public void didStartRequestHandling(@NonNull ServerType serverType,
																							@NonNull Request request,
																							@Nullable ResourceMethod resourceMethod) {
						if (shouldPerformRequestLogging(request, resourceMethod))
							logger.debug("Received {} {}", request.getHttpMethod(), request.getRawPathAndQuery());
					}

					@Override
					public void didFinishRequestHandling(@NonNull ServerType serverType,
																							 @NonNull Request request,
																							 @Nullable ResourceMethod resourceMethod,
																							 @NonNull MarshaledResponse marshaledResponse,
																							 @NonNull Duration processingDuration,
																							 @NonNull List<Throwable> throwables) {
						if (shouldPerformRequestLogging(request, resourceMethod))
							logger.debug(format("Finished processing %s %s (HTTP %d) in %.2fms", request.getHttpMethod(),
									request.getRawPathAndQuery(), marshaledResponse.getStatusCode(), processingDuration.toNanos() / 1000000.0));
					}

					@NonNull
					private Boolean shouldPerformRequestLogging(@NonNull Request request,
																											@Nullable ResourceMethod resourceMethod) {
						requireNonNull(request);

						// Special OPTIONS * requests are generally health checks and should not be logged
						if (request.getHttpMethod() == HttpMethod.OPTIONS && request.getPath().equals("*"))
							return false;

						// 404? Log it...
						if (resourceMethod == null)
							return true;

						// ...and log everything else, unless the Resource Method is decorated with our custom annotation
						return !resourceMethod.getMethod().isAnnotationPresent(SuppressRequestLogging.class);
					}

					@Override
					public void willStartSoklet(@NonNull Soklet soklet) {
						logger.debug("Toystore app starting in {} environment...", configuration.getEnvironment());
					}

					@Override
					public void willStopSoklet(@NonNull Soklet soklet) {
						logger.debug("Toystore app stopping...");
					}

					@Override
					public void didStopSoklet(@NonNull Soklet soklet) {
						logger.debug("Toystore app stopped.");
					}

					@Override
					public void didStartServer(@NonNull Server server) {
						logger.debug("Server started on port {}", configuration.getPort());
					}

					@Override
					public void didStartServerSentEventServer(@NonNull ServerSentEventServer serverSentEventServer) {
						logger.debug("Server-Sent Event server started on port {}", configuration.getServerSentEventPort());
					}

					@Override
					public void didEstablishServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection) {
						CurrentContext currentContext = (CurrentContext) serverSentEventConnection.getClientContext().get();
						logger.debug("Server-Sent Event Connection ID {} established for {}. Context: {}",
								serverSentEventConnection.getRequest().getId(), serverSentEventConnection.getRequest().getPath(), currentContext);
					}

					@Override
					public void didTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																														@NonNull Duration connectionDuration,
																														@NonNull TerminationReason terminationReason,
																														@Nullable Throwable throwable) {
						CurrentContext currentContext = (CurrentContext) serverSentEventConnection.getClientContext().get();
						logger.debug("Server-Sent Event Connection ID {} terminated for {} (reason: {}). Context: {}",
								serverSentEventConnection.getRequest().getId(), serverSentEventConnection.getRequest().getPath(), terminationReason.name(),
								currentContext);
					}

					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						requireNonNull(logEvent);
						logger.warn(logEvent.getMessage(), logEvent.getThrowable().orElse(null));
					}
				})
				.requestInterceptor(new RequestInterceptor() {
					@NonNull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.toystore.RequestInterceptor");

					@Override
					public void wrapRequest(@NonNull Request request,
																	@NonNull Consumer<Request> requestProcessor) {
						requireNonNull(request);
						requireNonNull(requestProcessor);

						// As soon as a request arrives wrap it in a "current context" scope.
						// Pick the best-matching locale and timezone based on information we have from the request (i.e. headers).
						// We will apply a new "current context" downstream with updated info if we authenticate an account for this request
						Localization localization = resolveLocalization(request, null);

						CurrentContext.withRequest(request)
								.locale(localization.locale())
								.timeZone(localization.timeZone())
								.build().run(() -> {
									requestProcessor.accept(request);
								});
					}

					@Override
					public void interceptRequest(@NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull Function<Request, MarshaledResponse> responseGenerator,
																			 @NonNull Consumer<MarshaledResponse> responseWriter) {
						requireNonNull(request);
						requireNonNull(responseGenerator);
						requireNonNull(responseWriter);

						// We'll pull an account to tie to our "current context" if the request has a valid access token
						Account account;

						if (resourceMethod != null && resourceMethod.isServerSentEventSource()) {
							// Is this an SSE resource method? If so, auth comes from a query parameter (SSE spec does not permit headers)...
							String sseAccessTokenAsString = request.getQueryParameter("sse-access-token").orElse(null);

							account = resolveAccountFromAccessToken(
									sseAccessTokenAsString,
									Audience.SSE,
									Set.of(Scope.SSE_HANDSHAKE)
							).orElse(null);
						} else {
							// Try to pull authentication token from request headers...
							String accessTokenAsString = resolveAccessTokenFromAuthorization(request);

							// ...and if it exists, see if we can pull an account from it.
							Set<Scope> requiredScopes = resolveRequiredApiScopes(request);

							account = resolveAccountFromAccessToken(
									accessTokenAsString,
									Audience.API,
									requiredScopes
							).orElse(null);
						}

						if (resourceMethod != null) {
							// Next, see if the resource method has an @AuthorizationRequired annotation, and respect it if so (401 or 403 as appropriate)
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

						// Finalize localization: if we have an account, use its settings to override request data
						Localization localization = resolveLocalization(request, account);

						CurrentContext currentContext = CurrentContext.withRequest(request, resourceMethod)
								.locale(localization.locale())
								.timeZone(localization.timeZone())
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

					@NonNull
					private Optional<Account> resolveAccountFromAccessToken(@Nullable String accessTokenAsString,
																																	@NonNull Audience expectedAudience,
																																	@NonNull Set<Scope> requiredScopes) {
						requireNonNull(expectedAudience);
						requireNonNull(requiredScopes);

						if (accessTokenAsString == null)
							return Optional.empty();

						AccessTokenResult accessTokenResult = AccessToken.fromStringRepresentation(accessTokenAsString, configuration.getKeyPair().getPublic());

						switch (accessTokenResult) {
							case AccessTokenResult.Succeeded(@NonNull AccessToken accessToken) -> {
								if (!accessToken.audience().equals(expectedAudience)) {
									logger.warn("{} Access Token audience is invalid: {}", expectedAudience.name(), accessToken.audience());
									return Optional.empty();
								}

								if (!accessToken.scopes().containsAll(requiredScopes)) {
									logger.warn("{} Access Token missing required scopes: {}", expectedAudience.name(), requiredScopes);
									return Optional.empty();
								}

								return accountService.findAccountById(accessToken.accountId());
							}

							case AccessTokenResult.Expired(@NonNull AccessToken accessToken, @NonNull Instant expiredAt) ->
									logger.debug("{} Access Token for account ID {} expired at {}", expectedAudience.name(), accessToken.accountId(), expiredAt);

							case AccessTokenResult.SignatureMismatch() ->
									logger.warn("{} Access Token signature is invalid: {}", expectedAudience.name(), accessTokenAsString);

							default -> logger.warn("{} Access Token is invalid: {}", expectedAudience.name(), accessTokenAsString);
						}

						return Optional.empty();
					}

					@Nullable
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
					private Set<Scope> resolveRequiredApiScopes(@NonNull Request request) {
						requireNonNull(request);

						return switch (request.getHttpMethod()) {
							case GET, HEAD, OPTIONS -> Set.of(Scope.API_READ);
							case POST, PUT, PATCH, DELETE -> Set.of(Scope.API_WRITE);
						};
					}

					@NonNull
					private Localization resolveLocalization(@NonNull Request request,
																									 @Nullable Account account) {
						requireNonNull(request);
						return new Localization(resolveLocale(request, account), resolveTimeZone(request, account));
					}

					@NonNull
					private Locale resolveLocale(@NonNull Request request,
																			 @Nullable Account account) {
						requireNonNull(request);

						// If there's an account, use its locale.
						// If there's a request, use its best-matching locale.
						// Otherwise, fall back to system default.
						return Optional.ofNullable(account)
								.map(Account::locale)
								.or(() -> request.getLocales().stream().findFirst())
								.orElse(Configuration.getDefaultLocale());
					}

					@NonNull
					private ZoneId resolveTimeZone(@NonNull Request request,
																				 @Nullable Account account) {
						requireNonNull(request);

						// If there's an account, use its time zone.
						// If there's a request, use its time zone.
						// Otherwise, fall back to system default.
						return Optional.ofNullable(account)
								.map(Account::timeZone)
								.or(() -> resolveTimeZoneHeader(request))
								.orElse(Configuration.getDefaultTimeZone());
					}

					@NonNull
					private Optional<ZoneId> resolveTimeZoneHeader(@NonNull Request request) {
						requireNonNull(request);

						// Pull from RFC 7808 Time-Zone header
						String timeZoneHeader = request.getHeader("Time-Zone").orElse(null);

						if (timeZoneHeader != null) {
							try {
								return Optional.of(ZoneId.of(timeZoneHeader));
							} catch (Exception ignored) {
								// Illegal timezone specified
							}
						}

						return Optional.empty();
					}
				})
				.requestBodyMarshaler(new RequestBodyMarshaler() {
					@NonNull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.toystore.RequestBodyMarshaler");

					@Nullable
					@Override
					public Optional<Object> marshalRequestBody(@NonNull Request request,
																										 @NonNull ResourceMethod resourceMethod,
																										 @NonNull Parameter parameter,
																										 @NonNull Type requestBodyType) {
						requireNonNull(request);
						requireNonNull(requestBodyType);

						String requestBodyAsString = request.getBodyAsString().orElse(null);

						if (requestBodyAsString == null)
							return Optional.empty();

						// Log out the request body, taking care to redact any fields marked with the @SensitiveValue annotation
						if (logger.isDebugEnabled())
							logger.debug("Request body:\n{}", sensitiveValueRedactor.performRedactions(requestBodyAsString, parameter.getType()));

						// Use Gson to turn the request body JSON into a Java type
						return Optional.of(gson.fromJson(requestBodyAsString, requestBodyType));
					}
				})
				.responseMarshaler(ResponseMarshaler.withDefaults()
						.resourceMethodHandler((@NonNull Request request,
																		@NonNull Response response,
																		@NonNull ResourceMethod resourceMethod) -> {
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
						})
						.notFoundHandler((@NonNull Request request) -> {
							// Use Gson to turn the error response into JSON
							ErrorResponse errorResponse = ErrorResponse.withSummary(strings.get("The resource you requested was not found.")).build();
							byte[] body = gson.toJson(errorResponse).getBytes(StandardCharsets.UTF_8);

							Map<String, Set<String>> headers = new HashMap<>();
							headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

							return MarshaledResponse.withStatusCode(404)
									.headers(headers)
									.body(body)
									.build();
						})
						.throwableHandler((@NonNull Request request,
															 @NonNull Throwable throwable,
															 @Nullable ResourceMethod resourceMethod) -> {
							// Collect error information for display to client
							int statusCode;
							List<String> generalErrors = new ArrayList<>();
							Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
							Map<String, Object> metadata = new LinkedHashMap<>();

							// Unwrap CompletionExceptions
							if (throwable instanceof CompletionException) {
								Throwable cause = throwable.getCause();
								if (cause != null)
									throwable = cause;
							}

							switch (throwable) {
								case IllegalQueryParameterException ex -> {
									statusCode = 400;
									generalErrors.add(strings.get("Illegal value '{{parameterValue}}' specified for query parameter '{{parameterName}}'.",
											Map.of(
													"parameterValue", ex.getQueryParameterValue().orElse(strings.get("(not provided)")),
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
							Set<String> fieldErrorsSummary = new LinkedHashSet<>();

							for (List<String> fieldErrorValues : fieldErrors.values())
								fieldErrorsSummary.addAll(fieldErrorValues);

							String summary = format("%s %s",
									generalErrors.stream().collect(Collectors.joining(" ")),
									fieldErrorsSummary.stream().collect(Collectors.joining(" "))
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
						}).build()
				)
				// Permit CORS for only the specified origins
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(configuration.getCorsWhitelistedOrigins()))
				// Use Google Guice when Soklet needs to vend instances
				.instanceProvider(injector::getInstance)
				.build();
	}

	// Explicitly provide this so it can be injected directly, e.g. to ToyService for broadcasting Server-Sent Events
	@NonNull
	@Provides
	@Singleton
	public ServerSentEventServer provideServerSentEventServer(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		return sokletConfig.getServerSentEventServer().get();
	}

	// What context is bound to the current execution scope?
	@NonNull
	@Provides
	public CurrentContext provideCurrentContext() {
		return CurrentContext.get();
	}

	// Provides a way to talk to a relational database
	@NonNull
	@Provides
	@Singleton
	public Database provideDatabase(@NonNull Injector injector) {
		requireNonNull(injector);

		// Example in-memory datasource for HSQLDB.
		// Each App instance gets its own isolated database to support parallel test execution in the same JVM instance
		JDBCDataSource dataSource = new JDBCDataSource();
		dataSource.setUrl(format("jdbc:hsqldb:mem:%s", UUID.randomUUID()));
		dataSource.setUser("sa");
		dataSource.setPassword("");

		// Use Pyranid to simplify JDBC operations
		return Database.withDataSource(dataSource)
				// Use Google Guice when Pyranid needs to vend instances
				.instanceProvider(new InstanceProvider() {
					@Override
					@NonNull
					public <T> T provide(@NonNull StatementContext<T> statementContext,
															 @NonNull Class<T> instanceType) {
						return injector.getInstance(instanceType);
					}
				})
				.statementLogger(new StatementLogger() {
					@NonNull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.toystore.StatementLogger");

					@Override
					public void log(@NonNull StatementLog statementLog) {
						if (logger.isTraceEnabled())
							logger.trace("SQL took {}ms:\n{}\nParameters: {}", format("%.2f", statementLog.getTotalDuration().toNanos() / 1000000.0),
									statementLog.getStatementContext().getStatement().getSql().stripIndent().trim(),
									statementLog.getStatementContext().getParameters());
					}
				})
				.build();
	}

	// Provides context-aware localization
	@NonNull
	@Provides
	@Singleton
	public Strings provideStrings(@NonNull Provider<CurrentContext> currentContextProvider) {
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

	@NonNull
	@Provides
	@Singleton
	public PasswordManager providePasswordManager() {
		return PasswordManager.withHashAlgorithm("PBKDF2WithHmacSHA512")
				.iterations(210_000)
				.saltLength(64)
				.keyLength(512)
				.build();
	}

	@NonNull
	@Provides
	@Singleton
	public SecretsManager provideSecretsManager(@NonNull Configuration configuration) {
		requireNonNull(configuration);

		SecretsManager secretsManager = null;

		switch (configuration.getSecretsManagerType()) {
			case MOCK -> secretsManager = new MockSecretsManager();
			case REAL ->
					throw new IllegalStateException(format("Need to create a real %s implementation", SecretsManager.class.getSimpleName()));
		}

		return secretsManager;
	}

	@NonNull
	@Provides
	@Singleton
	public CreditCardProcessor provideCreditCardProcessor(@NonNull Configuration configuration) {
		requireNonNull(configuration);

		CreditCardProcessor creditCardProcessor = null;

		switch (configuration.getCreditCardProcessorType()) {
			case MOCK -> creditCardProcessor = new MockCreditCardProcessor();
			case REAL ->
					throw new IllegalStateException(format("Need to create a real %s implementation", CreditCardProcessor.class.getSimpleName()));
		}

		return creditCardProcessor;
	}

	@NonNull
	@Provides
	@Singleton
	public ErrorReporter provideErrorReporter(@NonNull Configuration configuration) {
		requireNonNull(configuration);

		ErrorReporter errorReporter = null;

		switch (configuration.getErrorReporterType()) {
			case MOCK -> errorReporter = new MockErrorReporter();
			case REAL ->
					throw new IllegalStateException(format("Need to create a real %s implementation", ErrorReporter.class.getSimpleName()));
		}

		return errorReporter;
	}

	// Supports "complex" types to/from JSON: Locale, ZoneId, Instant, YearMonth
	@NonNull
	@Provides
	@Singleton
	public Gson provideGson(@NonNull Configuration configuration) {
		requireNonNull(configuration);

		GsonBuilder gsonBuilder = new GsonBuilder()
				.setPrettyPrinting()
				.disableHtmlEscaping()
				// Support `Locale` type for handling locales
				.registerTypeAdapter(Locale.class, new TypeAdapter<Locale>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull Locale locale) throws IOException {
						jsonWriter.value(locale.toLanguageTag());
					}

					@Override
					@NonNull
					public Locale read(@NonNull JsonReader jsonReader) throws IOException {
						return Locale.forLanguageTag(jsonReader.nextString());
					}
				})
				// Support `ZoneId` type for handling timezones
				.registerTypeHierarchyAdapter(ZoneId.class, new TypeAdapter<ZoneId>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull ZoneId zoneId) throws IOException {
						jsonWriter.value(zoneId.getId());
					}

					@Override
					@Nullable
					public ZoneId read(@NonNull JsonReader jsonReader) throws IOException {
						return ZoneId.of(jsonReader.nextString());
					}
				})
				// Support `Currency` type for handling ISO currency codes
				.registerTypeAdapter(Currency.class, new TypeAdapter<Currency>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull Currency currency) throws IOException {
						jsonWriter.value(currency.getCurrencyCode());
					}

					@Override
					@Nullable
					public Currency read(@NonNull JsonReader jsonReader) throws IOException {
						String code = jsonReader.nextString();
						try {
							return Currency.getInstance(code);
						} catch (IllegalArgumentException ignored) {
							return null;
						}
					}
				})
				// Use ISO formatting for Instants
				.registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull Instant instant) throws IOException {
						jsonWriter.value(instant.toString());
					}

					@Override
					@Nullable
					public Instant read(@NonNull JsonReader jsonReader) throws IOException {
						return Instant.parse(jsonReader.nextString());
					}
				})
				// Use ISO formatting for YearMonths
				.registerTypeAdapter(YearMonth.class, new TypeAdapter<YearMonth>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull YearMonth yearMonth) throws IOException {
						jsonWriter.value(yearMonth.toString());
					}

					@Override
					@Nullable
					public YearMonth read(@NonNull JsonReader jsonReader) throws IOException {
						return YearMonth.parse(jsonReader.nextString());
					}
				})
				// Convert our custom AccessToken to and from a JSON string
				.registerTypeAdapter(AccessToken.class, new TypeAdapter<AccessToken>() {
					@Override
					public void write(@NonNull JsonWriter jsonWriter,
														@NonNull AccessToken accountJwt) throws IOException {
						jsonWriter.value(accountJwt.toStringRepresentation(configuration.getKeyPair().getPrivate()));
					}

					@Override
					@Nullable
					public AccessToken read(@NonNull JsonReader jsonReader) throws IOException {
						AccessTokenResult result = AccessToken.fromStringRepresentation(jsonReader.nextString(), configuration.getKeyPair().getPublic());

						switch (result) {
							case AccessTokenResult.Succeeded(@NonNull AccessToken accountJwt) -> {
								return accountJwt;
							}
							case AccessTokenResult.Expired(@NonNull AccessToken accountJwt, @NonNull Instant expiredAt) -> {
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
		// Tells Guice to set up assisted injection for factories
		// See https://github.com/google/guice/wiki/AssistedInject
		install(new FactoryModuleBuilder().build(AccountResponseFactory.class));
		install(new FactoryModuleBuilder().build(ToyResponseFactory.class));
		install(new FactoryModuleBuilder().build(PurchaseResponseFactory.class));
	}

	// Tuple to hold localization information
	private record Localization(@NonNull Locale locale,
															@NonNull ZoneId timeZone) {
		public Localization {
			requireNonNull(locale);
			requireNonNull(timeZone);
		}
	}
}

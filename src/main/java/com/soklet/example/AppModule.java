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
import com.lokalized.DefaultStrings;
import com.lokalized.LocalizedStringLoader;
import com.lokalized.Strings;
import com.pyranid.Database;
import com.pyranid.DefaultInstanceProvider;
import com.pyranid.DefaultStatementLogger;
import com.pyranid.StatementContext;
import com.pyranid.StatementLog;
import com.soklet.SokletConfiguration;
import com.soklet.core.LifecycleInterceptor;
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
import com.soklet.example.exception.AuthenticationException;
import com.soklet.example.exception.AuthorizationException;
import com.soklet.example.exception.NotFoundException;
import com.soklet.example.exception.UserFacingException;
import com.soklet.example.model.api.response.EmployeeApiResponse.EmployeeApiResponseFactory;
import com.soklet.example.model.auth.AuthenticationToken;
import com.soklet.example.model.db.Employee;
import com.soklet.example.model.db.Role.RoleId;
import com.soklet.example.service.EmployeeService;
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
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AppModule extends AbstractModule {
	@Nonnull
	@Provides
	@Singleton
	public SokletConfiguration provideSokletConfiguration(@Nonnull Injector injector,
																												@Nonnull Configuration configuration,
																												@Nonnull Database database,
																												@Nonnull EmployeeService employeeService,
																												@Nonnull Strings strings,
																												@Nonnull Gson gson) {
		requireNonNull(injector);
		requireNonNull(configuration);
		requireNonNull(database);
		requireNonNull(employeeService);
		requireNonNull(strings);
		requireNonNull(gson);

		return new SokletConfiguration.Builder(new DefaultServer.Builder(configuration.getPort()).host("0.0.0.0").build())
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
						logger.debug("Finished processing {} {} (HTTP {}) in {}ms", request.getHttpMethod(),
								request.getUri(), marshaledResponse.getStatusCode(), processingDuration.toNanos() / 1000000.0);
					}

					@Override
					public void didStartServer(@Nonnull Server server) {
						logger.debug("Server started on port {}", configuration.getPort());
					}

					@Override
					public void didStopServer(@Nonnull Server server) {
						logger.debug("Server stopped.");
					}

					@Override
					public void wrapRequest(@Nonnull Request request,
																	@Nullable ResourceMethod resourceMethod,
																	@Nonnull Consumer<Request> requestProcessor) {
						requireNonNull(request);
						requireNonNull(requestProcessor);

						// Ensure a "current context" scope exists for all request-handling code
						CurrentContext.forRequest(request).build().run(() -> {
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

						// Look at request headers to see if there's a token that identifies an authenticated employee.
						// In a real system, this might be a JWT
						String authenticationTokenAsString = request.getHeader("X-Authentication-Token").orElse(null);
						Employee employee = null;

						// If the token exists, look up the employee
						if (authenticationTokenAsString != null)
							employee = employeeService.findEmployeeByAuthenticationToken(
									AuthenticationToken.decodeFromString(authenticationTokenAsString)).orElse(null);

						if (resourceMethod != null) {
							// See if the resource method has an @AuthorizationRequired annotation...
							AuthorizationRequired authorizationRequired = resourceMethod.getMethod().getAnnotation(AuthorizationRequired.class);

							if (authorizationRequired != null) {
								if (employee == null)
									throw new AuthenticationException();

								Set<RoleId> requiredRoleIds = authorizationRequired.value() == null
										? Set.of() : Arrays.stream(authorizationRequired.value()).collect(Collectors.toSet());

								if (requiredRoleIds.size() > 0 && !requiredRoleIds.contains(employee.roleId()))
									throw new AuthorizationException();
							}
						}

						// Create a new current context scope to take the authenticated employee into account (if present)
						CurrentContext currentContext = CurrentContext.forRequest(request)
								.employee(employee)
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
					@Nullable
					@Override
					public Object marshalRequestBody(@Nonnull Request request,
																					 @Nonnull ResourceMethod resourceMethod,
																					 @Nonnull Parameter parameter,
																					 @Nonnull Type requestBodyType) {
						requireNonNull(request);
						requireNonNull(requestBodyType);

						String requestBodyAsString = request.getBodyAsString().orElse(null);

						if (requestBodyAsString == null)
							return null;

						// Use Gson to turn the request body JSON into a Java type
						return gson.fromJson(requestBodyAsString, requestBodyType);
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

						Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return new MarshaledResponse.Builder(response.getStatusCode())
								.headers(headers)
								.cookies(response.getCookies())
								.body(body)
								.build();
					}

					@Nonnull
					@Override
					public MarshaledResponse forNotFound(@Nonnull Request request) {
						// Use Gson to turn response objects into JSON to go over the wire
						byte[] body = gson.toJson(Map.of("message", strings.get("The resource you requested was not found."))).getBytes(StandardCharsets.UTF_8);

						Map<String, Set<String>> headers = new HashMap<>();
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return new MarshaledResponse.Builder(404)
								.headers(headers)
								.body(body)
								.build();
					}

					@Nonnull
					@Override
					public MarshaledResponse forException(@Nonnull Request request,
																								@Nonnull Throwable throwable,
																								@Nullable ResourceMethod resourceMethod) {
						String message;
						int statusCode;

						switch (throwable) {
							case UserFacingException userFacingException -> {
								message = userFacingException.getMessage();
								statusCode = userFacingException.getStatusCode().orElse(422);
							}
							case IllegalQueryParameterException ex -> {
								message = String.format("Illegal value '%s' for query parameter '%s'",
										ex.getQueryParameterValue().orElse("[not provided]"),
										ex.getQueryParameterName());
								statusCode = 400;
							}
							case BadRequestException ignored -> {
								message = strings.get("Your request was improperly formatted.");
								statusCode = 400;
							}
							case AuthenticationException ignored -> {
								message = strings.get("You must be authenticated to perform this action.");
								statusCode = 401;
							}
							case AuthorizationException ignored -> {
								message = strings.get("You are not authorized to perform this action.");
								statusCode = 403;
							}
							case NotFoundException ignored -> {
								message = strings.get("The resource you requested was not found.");
								statusCode = 404;
							}
							default -> {
								message = strings.get("An unexpected error occurred.");
								statusCode = 500;
							}
						}

						// Use Gson to turn response objects into JSON to go over the wire
						byte[] body = gson.toJson(Map.of("message", message)).getBytes(StandardCharsets.UTF_8);

						Map<String, Set<String>> headers = new HashMap<>();
						headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

						return new MarshaledResponse.Builder(statusCode)
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
		return Database.forDataSource(dataSource)
				// Use Google Guice when Pyranid needs to vend instances
				.instanceProvider(new DefaultInstanceProvider() {
					@Override
					@Nonnull
					public <T> T provide(@Nonnull StatementContext<T> statementContext,
															 @Nonnull Class<T> instanceType) {
						return injector.getInstance(instanceType);
					}
				})
				.statementLogger(new DefaultStatementLogger() {
					@Nonnull
					private final Logger logger = LoggerFactory.getLogger("com.soklet.example.StatementLogger");

					@Override
					public void log(@Nonnull StatementLog statementLog) {
						logger.trace("SQL took {}ms:\n{}\nParameters: {}", statementLog.getTotalDuration().toNanos() / 1000000.0,
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

		String fallbackLanguageCode = Configuration.getFallbackLocale().getLanguage();

		return new DefaultStrings.Builder(fallbackLanguageCode,
				() -> LocalizedStringLoader.loadFromFilesystem(Paths.get("src/main/resources/strings")))
				// Rely on the current context's preferred locale to pick the appropriate localization file
				.localeSupplier(() -> currentContextProvider.get().getPreferredLocale())
				.build();
	}

	@Nonnull
	@Provides
	@Singleton
	public Gson provideGson() {
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
				// Support our custom `AuthenticationToken` type
				.registerTypeAdapter(AuthenticationToken.class, new TypeAdapter<AuthenticationToken>() {
					@Override
					public void write(@Nonnull JsonWriter jsonWriter,
														@Nonnull AuthenticationToken authenticationToken) throws IOException {
						jsonWriter.value(authenticationToken.encodeAsString());
					}

					@Override
					@Nullable
					public AuthenticationToken read(@Nonnull JsonReader jsonReader) throws IOException {
						return AuthenticationToken.decodeFromString(jsonReader.nextString());
					}
				});
		return gsonBuilder.create();
	}

	@Override
	protected void configure() {
		// Lets Guice know to set up our factory builder
		install(new FactoryModuleBuilder().build(EmployeeApiResponseFactory.class));
	}
}
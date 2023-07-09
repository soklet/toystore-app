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
import com.soklet.example.CurrentContext;
import com.soklet.example.exception.UserFacingException;
import com.soklet.example.model.api.request.EmployeeAuthenticateApiRequest;
import com.soklet.example.model.api.request.EmployeeCreateApiRequest;
import com.soklet.example.model.api.request.EmployeeUpdateApiRequest;
import com.soklet.example.model.auth.AuthenticationToken;
import com.soklet.example.model.db.Employee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class EmployeeService {
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;
	@Nonnull
	private final Database database;
	@Nonnull
	private final Strings strings;
	@Nonnull
	private final Logger logger;

	@Inject
	public EmployeeService(@Nonnull Provider<CurrentContext> currentContextProvider,
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
	public List<Employee> findEmployees() {
		return getDatabase().queryForList("""
				  SELECT *
				  FROM employee
				  ORDER BY name
				""", Employee.class);
	}

	@Nonnull
	public Optional<Employee> findEmployeeById(@Nullable UUID employeeId) {
		if (employeeId == null)
			return Optional.empty();

		return getDatabase().executeForObject("""
				SELECT *
				FROM employee
				WHERE employee_id=?
				""", Employee.class, employeeId);
	}

	@Nonnull
	public UUID createEmployee(@Nonnull EmployeeCreateApiRequest request) {
		requireNonNull(request);

		UUID employeeId = UUID.randomUUID();

		getDatabase().execute("""
				INSERT INTO employee (
					employee_id,
					role_id,
					name,
					email_address,
					time_zone,
					locale
				) VALUES (?,?,?,?,?,?)
				""", employeeId, request.roleId(), request.name(), request.emailAddress(), request.timeZone(), request.locale());

		return employeeId;
	}

	@Nonnull
	public Boolean updateEmployee(@Nonnull UUID employeeId,
																@Nonnull EmployeeUpdateApiRequest request) {
		requireNonNull(employeeId);
		requireNonNull(request);

		return getDatabase().execute("""
				UPDATE employee
				SET name=?, email_address=?, time_zone=?, locale=?
				WHERE employee_id=?
				""", request.name(), request.emailAddress(), request.timeZone(), request.locale(), employeeId) > 0;
	}

	@Nonnull
	public Boolean deleteEmployee(@Nonnull UUID employeeId) {
		requireNonNull(employeeId);
		return getDatabase().execute("DELETE FROM employee WHERE employee_id=?", employeeId) > 0;
	}

	@Nonnull
	public AuthenticationToken authenticateEmployee(@Nonnull EmployeeAuthenticateApiRequest request) {
		requireNonNull(request);

		Employee employee = getDatabase().executeForObject("""
				SELECT *
				FROM employee
				WHERE email_address=LOWER(?)
				""", Employee.class, request.emailAddress().toLowerCase(Locale.US)).orElse(null);

		if (employee == null)
			throw new UserFacingException(getStrings().get("Sorry, we could not authenticate you."));

		UUID employeeId = employee.employeeId();
		String assertion = UUID.randomUUID().toString();
		Instant expiration = Instant.now().plus(10, ChronoUnit.MINUTES);

		// A real system would cryptographically sign the assertion and embed a nonce to prevent replay attacks
		return new AuthenticationToken(employeeId, assertion, expiration);
	}

	@Nonnull
	public Optional<Employee> findEmployeeByAuthenticationToken(@Nullable AuthenticationToken authenticationToken) {
		if (authenticationToken == null)
			return Optional.empty();

		if (Instant.now().isAfter(authenticationToken.expiration())) {
			String expirationDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
					.localizedBy(getCurrentContext().getPreferredLocale())
					.withZone(getCurrentContext().getPreferredTimeZone())
					.format(authenticationToken.expiration());

			throw new UserFacingException(getStrings().get("Your authentication token expired on {{expirationDateTime}}, please re-authenticate.",
					Map.of("expirationDateTime", expirationDateTime)));
		}

		// Note: A real system would perform a cryptographic check against the assertion and validate/track the nonce
		// (in addition to any other checks...)

		return findEmployeeById(authenticationToken.employeeId());
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
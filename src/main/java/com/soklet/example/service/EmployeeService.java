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
import com.pyranid.Database;
import com.soklet.example.model.api.request.EmployeeCreateApiRequest;
import com.soklet.example.model.api.request.EmployeeUpdateApiRequest;
import com.soklet.example.model.db.Employee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class EmployeeService {
	@Nonnull
	private final Database database;
	@Nonnull
	private final Logger logger;

	@Inject
	public EmployeeService(@Nonnull Database database) {
		requireNonNull(database);
		this.database = database;
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
	protected Database getDatabase() {
		return this.database;
	}

	@Nonnull
	protected Logger getLogger() {
		return this.logger;
	}
}
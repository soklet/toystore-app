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
import com.soklet.annotation.DELETE;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.Resource;
import com.soklet.example.model.db.Employee;
import com.soklet.example.model.api.request.EmployeeCreateApiRequest;
import com.soklet.example.model.api.request.EmployeeUpdateApiRequest;
import com.soklet.example.service.EmployeeService;
import com.soklet.example.util.RequestBodyParser;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Resource
@ThreadSafe
public class EmployeeResource {
	@Nonnull
	private final EmployeeService employeeService;
	@Nonnull
	private final RequestBodyParser requestBodyParser;

	@Inject
	public EmployeeResource(@Nonnull EmployeeService employeeService,
													@Nonnull RequestBodyParser requestBodyParser) {
		requireNonNull(employeeService);
		requireNonNull(employeeService);

		this.employeeService = employeeService;
		this.requestBodyParser = requestBodyParser;
	}

	@Nonnull
	@GET("/employees")
	public EmployeesReponse findEmployees() {
		List<Employee> employees = getEmployeeService().findEmployees();
		return new EmployeesReponse(employees);
	}

	@Nonnull
	@POST("/employees")
	public EmployeeReponse createEmployee(@Nonnull @RequestBody String requestBody) {
		requireNonNull(requestBody);

		EmployeeCreateApiRequest request = getRequestBodyParser().parse(requestBody, EmployeeCreateApiRequest.class);
		UUID employeeId = getEmployeeService().createEmployee(request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(employee);
	}

	@Nonnull
	@PUT("/employees/{employeeId}")
	public EmployeeReponse updateEmployee(@Nonnull @PathParameter UUID employeeId,
																				@Nonnull @RequestBody String requestBody) {
		requireNonNull(employeeId);
		requireNonNull(requestBody);

		EmployeeUpdateApiRequest request = getRequestBodyParser().parse(requestBody, EmployeeUpdateApiRequest.class);
		getEmployeeService().updateEmployee(employeeId, request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(employee);
	}

	@DELETE("/employees/{employeeId}")
	public void deleteEmployee(@Nonnull @PathParameter UUID employeeId) {
		requireNonNull(employeeId);
		getEmployeeService().deleteEmployee(employeeId);
	}

	public record EmployeesReponse(
			@Nonnull List<Employee> employees
	) {
		public EmployeesReponse {
			requireNonNull(employees);
		}
	}

	public record EmployeeReponse(
			@Nonnull Employee employee
	) {
		public EmployeeReponse {
			requireNonNull(employee);
		}
	}

	@Nonnull
	protected EmployeeService getEmployeeService() {
		return this.employeeService;
	}

	@Nonnull
	protected RequestBodyParser getRequestBodyParser() {
		return this.requestBodyParser;
	}
}
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
import com.soklet.example.exception.AuthorizationException;
import com.soklet.example.exception.NotFoundException;
import com.soklet.example.model.api.request.EmployeeAuthenticateApiRequest;
import com.soklet.example.model.api.request.EmployeeCreateApiRequest;
import com.soklet.example.model.api.request.EmployeeUpdateApiRequest;
import com.soklet.example.model.auth.AuthenticationToken;
import com.soklet.example.model.db.Employee;
import com.soklet.example.model.db.Role.RoleId;
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
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public EmployeeResource(@Nonnull EmployeeService employeeService,
													@Nonnull RequestBodyParser requestBodyParser,
													@Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(employeeService);
		requireNonNull(requestBodyParser);
		requireNonNull(currentContextProvider);

		this.employeeService = employeeService;
		this.requestBodyParser = requestBodyParser;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@AuthorizationRequired
	@GET("/employees")
	public EmployeesReponse findEmployees() {
		List<Employee> employees = getEmployeeService().findEmployees();
		return new EmployeesReponse(employees);
	}

	@Nonnull
	@AuthorizationRequired
	@POST("/employees")
	public EmployeeReponse createEmployee(@Nonnull @RequestBody String requestBody) {
		requireNonNull(requestBody);

		Employee currentEmployee = getCurrentContext().getEmployee().get();

		if (currentEmployee.roleId() != RoleId.ADMINISTRATOR)
			throw new AuthorizationException();

		EmployeeCreateApiRequest request = getRequestBodyParser().parse(requestBody, EmployeeCreateApiRequest.class);
		UUID employeeId = getEmployeeService().createEmployee(request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(employee);
	}

	@Nonnull
	@AuthorizationRequired
	@PUT("/employees/{employeeId}")
	public EmployeeReponse updateEmployee(@Nonnull @PathParameter UUID employeeId,
																				@Nonnull @RequestBody String requestBody) {
		requireNonNull(employeeId);
		requireNonNull(requestBody);

		Employee employeeToUpdate = getEmployeeService().findEmployeeById(employeeId).orElse(null);

		if (employeeToUpdate == null)
			throw new NotFoundException();

		Employee currentEmployee = getCurrentContext().getEmployee().get();

		// 1. Administrators can update anyone
		// 2. Non-administrators can only update themselves
		if (currentEmployee.roleId() != RoleId.ADMINISTRATOR
				|| !currentEmployee.employeeId().equals(employeeToUpdate.employeeId()))
			throw new AuthorizationException();

		EmployeeUpdateApiRequest request = getRequestBodyParser().parse(requestBody, EmployeeUpdateApiRequest.class);
		getEmployeeService().updateEmployee(employeeId, request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(employee);
	}

	@AuthorizationRequired
	@DELETE("/employees/{employeeId}")
	public void deleteEmployee(@Nonnull @PathParameter UUID employeeId) {
		requireNonNull(employeeId);

		Employee employeeToDelete = getEmployeeService().findEmployeeById(employeeId).orElse(null);

		if (employeeToDelete == null)
			throw new NotFoundException();

		Employee currentEmployee = getCurrentContext().getEmployee().get();

		// Only administrators may delete employees
		if (currentEmployee.roleId() != RoleId.ADMINISTRATOR)
			throw new AuthorizationException();

		requireNonNull(employeeId);
		getEmployeeService().deleteEmployee(employeeId);
	}

	@Nonnull
	@POST("/employees/authenticate")
	public EmployeeAuthenticateReponse authenticateEmployee(@Nonnull @RequestBody String requestBody) {
		requireNonNull(requestBody);

		EmployeeAuthenticateApiRequest request = getRequestBodyParser().parse(requestBody, EmployeeAuthenticateApiRequest.class);
		AuthenticationToken authenticationToken = getEmployeeService().authenticateEmployee(request);
		Employee employee = getEmployeeService().findEmployeeByAuthenticationToken(authenticationToken.value()).get();

		return new EmployeeAuthenticateReponse(authenticationToken, employee);
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

	public record EmployeeAuthenticateReponse(
			@Nonnull AuthenticationToken authenticationToken,
			@Nonnull Employee employee
	) {
		public EmployeeAuthenticateReponse {
			requireNonNull(authenticationToken);
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

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}
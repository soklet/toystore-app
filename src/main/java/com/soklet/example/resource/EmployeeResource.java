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
import com.soklet.example.model.api.response.EmployeeApiResponse;
import com.soklet.example.model.api.response.EmployeeApiResponse.EmployeeApiResponseFactory;
import com.soklet.example.model.auth.AuthenticationToken;
import com.soklet.example.model.db.Employee;
import com.soklet.example.model.db.Role.RoleId;
import com.soklet.example.service.EmployeeService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
	private final EmployeeApiResponseFactory employeeApiResponseFactory;
	@Nonnull
	private final Provider<CurrentContext> currentContextProvider;

	@Inject
	public EmployeeResource(@Nonnull EmployeeService employeeService,
													@Nonnull EmployeeApiResponseFactory employeeApiResponseFactory,
													@Nonnull Provider<CurrentContext> currentContextProvider) {
		requireNonNull(employeeService);
		requireNonNull(employeeApiResponseFactory);
		requireNonNull(currentContextProvider);

		this.employeeService = employeeService;
		this.employeeApiResponseFactory = employeeApiResponseFactory;
		this.currentContextProvider = currentContextProvider;
	}

	@Nonnull
	@AuthorizationRequired
	@GET("/employees")
	public EmployeesReponse findEmployees() {
		List<EmployeeApiResponse> employees = getEmployeeService().findEmployees().stream()
				.map(employee -> getEmployeeApiResponseFactory().create(employee))
				.collect(Collectors.toList());

		return new EmployeesReponse(employees);
	}

	@Nonnull
	@AuthorizationRequired(RoleId.ADMINISTRATOR)
	@POST("/employees")
	public EmployeeReponse createEmployee(@Nonnull @RequestBody EmployeeCreateApiRequest request) {
		requireNonNull(request);

		UUID employeeId = getEmployeeService().createEmployee(request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(getEmployeeApiResponseFactory().create(employee));
	}

	@Nonnull
	@AuthorizationRequired
	@PUT("/employees/{employeeId}")
	public EmployeeReponse updateEmployee(@Nonnull @PathParameter UUID employeeId,
																				@Nonnull @RequestBody EmployeeUpdateApiRequest request) {
		requireNonNull(employeeId);
		requireNonNull(request);

		Employee employeeToUpdate = getEmployeeService().findEmployeeById(employeeId).orElse(null);

		if (employeeToUpdate == null)
			throw new NotFoundException();

		Employee currentEmployee = getCurrentContext().getEmployee().get();

		// 1. Administrators can update anyone
		// 2. Non-administrators can only update themselves
		if (currentEmployee.roleId() != RoleId.ADMINISTRATOR
				|| !currentEmployee.employeeId().equals(employeeToUpdate.employeeId()))
			throw new AuthorizationException();

		getEmployeeService().updateEmployee(employeeId, request);
		Employee employee = getEmployeeService().findEmployeeById(employeeId).get();

		return new EmployeeReponse(getEmployeeApiResponseFactory().create(employee));
	}

	@AuthorizationRequired(RoleId.ADMINISTRATOR)
	@DELETE("/employees/{employeeId}")
	public void deleteEmployee(@Nonnull @PathParameter UUID employeeId) {
		requireNonNull(employeeId);

		Employee employeeToDelete = getEmployeeService().findEmployeeById(employeeId).orElse(null);

		if (employeeToDelete == null)
			throw new NotFoundException();

		getEmployeeService().deleteEmployee(employeeId);
	}

	@Nonnull
	@POST("/employees/authenticate")
	public EmployeeAuthenticateReponse authenticateEmployee(@Nonnull @RequestBody EmployeeAuthenticateApiRequest request) {
		requireNonNull(request);

		AuthenticationToken authenticationToken = getEmployeeService().authenticateEmployee(request);
		Employee employee = getEmployeeService().findEmployeeByAuthenticationToken(authenticationToken).get();

		return new EmployeeAuthenticateReponse(authenticationToken, getEmployeeApiResponseFactory().create(employee));
	}

	public record EmployeesReponse(
			@Nonnull List<EmployeeApiResponse> employees
	) {
		public EmployeesReponse {
			requireNonNull(employees);
		}
	}

	public record EmployeeReponse(
			@Nonnull EmployeeApiResponse employee
	) {
		public EmployeeReponse {
			requireNonNull(employee);
		}
	}

	public record EmployeeAuthenticateReponse(
			@Nonnull AuthenticationToken authenticationToken,
			@Nonnull EmployeeApiResponse employee
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
	protected EmployeeApiResponseFactory getEmployeeApiResponseFactory() {
		return this.employeeApiResponseFactory;
	}

	@Nonnull
	protected CurrentContext getCurrentContext() {
		return this.currentContextProvider.get();
	}
}
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

package com.soklet.example.model.api.response;

import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.soklet.example.CurrentContext;
import com.soklet.example.model.db.Employee;
import com.soklet.example.model.db.Role.RoleId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class EmployeeApiResponse {
	@Nonnull
	private final UUID employeeId;
	@Nonnull
	private final RoleId roleId;
	@Nonnull
	private final String name;
	@Nullable
	private final String emailAddress;
	@Nonnull
	private final ZoneId timeZone;
	@Nonnull
	private final String timeZoneDescription;
	@Nonnull
	private final Locale locale;
	@Nonnull
	private final String localeDescription;
	@Nonnull
	private final Instant createdAt;
	@Nonnull
	private final String createdAtDescription;

	@ThreadSafe
	public interface EmployeeApiResponseFactory {
		@Nonnull
		EmployeeApiResponse create(@Nonnull Employee employee);
	}

	@AssistedInject
	public EmployeeApiResponse(@Nonnull Provider<CurrentContext> currentContextProvider,
														 @Assisted @Nonnull Employee employee) {
		requireNonNull(currentContextProvider);
		requireNonNull(employee);

		// We can tailor our response based on current context
		CurrentContext currentContext = currentContextProvider.get();
		Locale currentLocale = currentContext.getPreferredLocale();
		ZoneId currentTimeZone = currentContext.getPreferredTimeZone();

		this.employeeId = employee.employeeId();
		this.roleId = employee.roleId();
		this.name = employee.name();
		this.emailAddress = employee.emailAddress();
		this.locale = employee.locale();
		this.localeDescription = this.locale.getDisplayName(currentLocale);
		this.timeZone = employee.timeZone();
		this.timeZoneDescription = this.timeZone.getDisplayName(TextStyle.FULL, currentLocale);
		this.createdAt = employee.createdAt();
		this.createdAtDescription = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.localizedBy(currentLocale)
				.withZone(currentTimeZone)
				.format(employee.createdAt());
	}

	@Nonnull
	public UUID getEmployeeId() {
		return this.employeeId;
	}

	@Nonnull
	public RoleId getRoleId() {
		return this.roleId;
	}

	@Nonnull
	public String getName() {
		return this.name;
	}

	@Nonnull
	public Optional<String> getEmailAddress() {
		return Optional.ofNullable(this.emailAddress);
	}

	@Nonnull
	public ZoneId getTimeZone() {
		return this.timeZone;
	}

	@Nonnull
	public String getTimeZoneDescription() {
		return this.timeZoneDescription;
	}

	@Nonnull
	public Locale getLocale() {
		return this.locale;
	}

	@Nonnull
	public String getLocaleDescription() {
		return this.localeDescription;
	}

	@Nonnull
	public Instant getCreatedAt() {
		return this.createdAt;
	}

	@Nonnull
	public String getCreatedAtDescription() {
		return this.createdAtDescription;
	}
}
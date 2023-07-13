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

import com.soklet.core.Request;
import com.soklet.example.model.db.Employee;
import jdk.incubator.concurrent.ScopedValue;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CurrentContext {
	@Nonnull
	private static final ScopedValue<Deque<CurrentContext>> CURRENT_CONTEXT_STACK_SCOPED_VALUE;

	static {
		CURRENT_CONTEXT_STACK_SCOPED_VALUE = ScopedValue.newInstance();
	}

	@Nullable
	private Request request;
	@Nullable
	private Employee employee;

	@Nonnull
	public static CurrentContext get() {
		if (!CURRENT_CONTEXT_STACK_SCOPED_VALUE.isBound())
			throw new IllegalStateException(format("No %s is bound to the current scope",
					CurrentContext.class.getSimpleName()));

		CurrentContext currentContext = CURRENT_CONTEXT_STACK_SCOPED_VALUE.get().peek();

		// Indicates programmer error
		if (currentContext == null)
			throw new IllegalStateException(format("The %s stack bound to the current scope is empty",
					CurrentContext.class.getSimpleName()));

		return currentContext;
	}

	@NotThreadSafe
	public static class Builder {
		@Nullable
		private Request request;
		@Nullable
		private Employee employee;

		@Nonnull
		public Builder request(@Nullable Request request) {
			this.request = request;
			return this;
		}

		@Nonnull
		public Builder employee(@Nullable Employee employee) {
			this.employee = employee;
			return this;
		}

		@Nonnull
		public CurrentContext build() {
			return new CurrentContext(this);
		}
	}

	@Nonnull
	public static CurrentContext.Builder forRequest(@Nullable Request request) {
		return new CurrentContext.Builder().request(request);
	}

	@Nonnull
	public static CurrentContext.Builder forEmployee(@Nullable Employee employee) {
		return new CurrentContext.Builder().employee(employee);
	}

	private CurrentContext(@Nonnull Builder builder) {
		this.request = builder.request;
		this.employee = builder.employee;
	}

	public void run(@Nonnull Runnable runnable) {
		requireNonNull(runnable);

		// Maintain a stack of scoped current contexts so we can set logging context
		// and clear it out after the topmost context has been unwound
		Deque<CurrentContext> currentContextStack = CURRENT_CONTEXT_STACK_SCOPED_VALUE.orElse(new ArrayDeque<>());

		ScopedValue.where(CURRENT_CONTEXT_STACK_SCOPED_VALUE, currentContextStack).run(() -> {
			currentContextStack.push(this);
			MDC.put("CURRENT_CONTEXT", getLoggingDescription());

			try {
				runnable.run();
			} finally {
				currentContextStack.pop();

				if (currentContextStack.size() == 0)
					MDC.remove("CURRENT_CONTEXT");
			}
		});
	}

	@Nonnull
	protected String getLoggingDescription() {
		CurrentContext currentContext = get();
		Request request = currentContext.getRequest().orElse(null);
		Employee employee = currentContext.getEmployee().orElse(null);

		String requestDescription = request == null ? "background thread" : request.getId().toString();
		String employeeDescription = employee == null ? "unauthenticated" : employee.name();

		return format("%s (%s)", requestDescription, employeeDescription);
	}

	@Nonnull
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
	}

	@Nonnull
	public Optional<Employee> getEmployee() {
		return Optional.ofNullable(this.employee);
	}

	@Nonnull
	public Locale getPreferredLocale() {
		Request request = getRequest().orElse(null);

		// If this is in the context of a web request, allow clients to specify a special header which indicates preferred locale
		if (request != null) {
			String localeHeader = request.getHeaderValue("X-Locale").orElse(null);

			if (localeHeader != null) {
				try {
					return Locale.forLanguageTag(localeHeader);
				} catch (Exception ignored) {
					// Illegal locale specified, we'll just try one of our fallbacks
				}
			}
		}

		// Next, if there's a signed-in employee, use their configured locale
		Employee employee = getEmployee().orElse(null);

		if (employee != null)
			return employee.locale();

		// If that didn't work, and we're in the context of a web request, try its Accept-Language header
		if (request != null && request.getLocales().size() > 0)
			return request.getLocales().get(0);

		// Still not sure?  Fall back to a safe default
		return Configuration.getFallbackLocale();
	}

	@Nonnull
	public ZoneId getPreferredTimeZone() {
		Request request = getRequest().orElse(null);

		// If this is in the context of a web request, allow clients to specify a special header which indicates preferred timezone
		if (request != null) {
			String timeZoneHeader = request.getHeaderValue("X-Time-Zone").orElse(null);

			if (timeZoneHeader != null) {
				try {
					return ZoneId.of(timeZoneHeader);
				} catch (Exception ignored) {
					// Illegal timezone specified, we'll just try one of our fallbacks
				}
			}
		}

		// Next, if there's a signed-in employee, use their configured timezone
		Employee employee = getEmployee().orElse(null);

		if (employee != null)
			return employee.timeZone();

		// Still not sure?  Fall back to a safe default
		return Configuration.getFallbackTimeZone();
	}
}

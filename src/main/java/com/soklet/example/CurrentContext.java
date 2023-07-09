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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

import static java.lang.String.format;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CurrentContext {
	@Nonnull
	private static final ScopedValue<CurrentContext> CURRENT_CONTEXT_SCOPED_VALUE;

	@Nullable
	private Request request;
	@Nullable
	private Employee employee;

	static {
		CURRENT_CONTEXT_SCOPED_VALUE = ScopedValue.newInstance();
	}

	@Nonnull
	public static CurrentContext get() {
		if (!CURRENT_CONTEXT_SCOPED_VALUE.isBound())
			throw new IllegalStateException(format("No %s is bound to the current thread", CurrentContext.class.getSimpleName()));

		return CURRENT_CONTEXT_SCOPED_VALUE.get();
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
		ScopedValue.where(CURRENT_CONTEXT_SCOPED_VALUE, this).run(runnable);
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
		Employee employee = getEmployee().orElse(null);

		if (employee != null)
			return employee.locale();

		Request request = getRequest().orElse(null);

		if (request != null && request.getLocales().size() > 0)
			return request.getLocales().get(0);

		return Configuration.getFallbackLocale();
	}

	@Nonnull
	public ZoneId getPreferredTimeZone() {
		Employee employee = getEmployee().orElse(null);

		if (employee != null)
			return employee.timeZone();

		Request request = getRequest().orElse(null);

		// Allow clients to specify a special header which indicates preferred timezone
		if (request != null) {
			String timeZoneHeader = request.getHeaderValue("X-Time-Zone").orElse(null);

			if (timeZoneHeader != null) {
				try {
					return ZoneId.of(timeZoneHeader);
				} catch (Exception ignored) {
					// Illegal timezone specified, we'll just use the fallback
				}
			}
		}

		return Configuration.getFallbackTimeZone();
	}
}

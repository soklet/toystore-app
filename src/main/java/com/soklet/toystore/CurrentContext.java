/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.Request;
import com.soklet.ResourceMethod;
import com.soklet.toystore.model.db.Account;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Keeps track of context: which request/account/time zone/locale/etc. is applied to the current thread of execution?
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class CurrentContext {
	@Nonnull
	private static final ScopedValue<CurrentContext> CURRENT_CONTEXT_SCOPED_VALUE;

	static {
		CURRENT_CONTEXT_SCOPED_VALUE = ScopedValue.newInstance();
	}

	@Nonnull
	public static CurrentContext get() {
		if (!CURRENT_CONTEXT_SCOPED_VALUE.isBound())
			throw new IllegalStateException(format("No %s is bound to the current scope", CurrentContext.class.getSimpleName()));

		return CURRENT_CONTEXT_SCOPED_VALUE.get();
	}

	@NotThreadSafe
	public static class Builder {
		@Nullable
		private Locale locale;
		@Nullable
		private ZoneId timeZone;
		@Nullable
		private Request request;
		@Nullable
		private ResourceMethod resourceMethod;
		@Nullable
		private Account account;

		private Builder() {}

		@Nonnull
		public Builder locale(@Nullable Locale locale) {
			this.locale = locale;
			return this;
		}

		@Nonnull
		public Builder timeZone(@Nullable ZoneId timeZone) {
			this.timeZone = timeZone;
			return this;
		}

		@Nonnull
		public Builder request(@Nullable Request request) {
			this.request = request;
			return this;
		}

		@Nonnull
		public Builder resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.resourceMethod = resourceMethod;
			return this;
		}

		@Nonnull
		public Builder account(@Nullable Account account) {
			this.account = account;
			return this;
		}

		@Nonnull
		public CurrentContext build() {
			return new CurrentContext(this);
		}
	}

	@Nonnull
	public static Builder with(@Nullable Locale locale,
														 @Nullable ZoneId timeZone) {
		return new Builder().locale(locale).timeZone(timeZone);
	}

	@Nonnull
	public static Builder withRequest(@Nullable Request request,
																		@Nullable ResourceMethod resourceMethod) {
		return new Builder().request(request).resourceMethod(resourceMethod);
	}

	@Nonnull
	public static Builder withAccount(@Nullable Account account) {
		return new Builder().account(account);
	}

	@Nonnull
	private final Locale locale;
	@Nonnull
	private final ZoneId timeZone;
	@Nullable
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;
	@Nullable
	private final Account account;

	private CurrentContext(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.timeZone = builder.timeZone == null ? Configuration.getDefaultTimeZone() : builder.timeZone;
		this.locale = builder.locale == null ? Configuration.getDefaultLocale() : builder.locale;
		this.request = builder.request;
		this.resourceMethod = builder.resourceMethod;
		this.account = builder.account;
	}

	public void run(@Nonnull Runnable runnable) {
		requireNonNull(runnable);
		run(() -> {
			runnable.run();
			return null;
		});
	}

	@Nullable
	public <T> T run(@Nonnull Supplier<T> supplier) {
		requireNonNull(supplier);

		// Capture the previous MDC value to restore it later
		String previousMdc = MDC.get("CURRENT_CONTEXT");

		// Create the binding for the new scope
		return ScopedValue.where(CURRENT_CONTEXT_SCOPED_VALUE, this).call(() -> {
			try {
				// Apply new logging context
				MDC.put("CURRENT_CONTEXT", determineLoggingDescription());
				return supplier.get();
			} finally {
				// Restore previous logging context (or clear if we were at the root)
				if (previousMdc != null) {
					MDC.put("CURRENT_CONTEXT", previousMdc);
				} else {
					MDC.remove("CURRENT_CONTEXT");
				}
			}
		});
	}

	@Override
	public String toString() {
		StringJoiner joiner = new StringJoiner(", ", format("%s{", CurrentContext.class.getSimpleName()), "}");

		getAccount().ifPresent(account -> joiner.add(format("accountId=%s", account.accountId())));

		joiner.add(format("locale=%s", getLocale().toLanguageTag()));
		joiner.add(format("timeZone=", getTimeZone().getId()));

		getRequest().ifPresent(request -> joiner.add(format("request=%s %s", request.getHttpMethod().name(), request.getRawPath())));

		return joiner.toString();
	}

	@Nonnull
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
	}

	@Nonnull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}

	@Nonnull
	public Optional<Account> getAccount() {
		return Optional.ofNullable(this.account);
	}

	@Nonnull
	public ZoneId getTimeZone() {
		return this.timeZone;
	}

	@Nonnull
	public Locale getLocale() {
		return this.locale;
	}

	@Nonnull
	private String determineLoggingDescription() {
		Request request = this.getRequest().orElse(null);
		Account account = this.getAccount().orElse(null);

		String requestDescription = request == null ? "background thread" : request.getId().toString();
		String accountDescription = account == null ? "unauthenticated" : account.name();

		return format("%s (%s)", requestDescription, accountDescription);
	}
}
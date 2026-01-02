/*
 * Copyright 2022-2026 Revetware LLC.
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

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
	@NonNull
	private static final ScopedValue<CurrentContext> CURRENT_CONTEXT_SCOPED_VALUE;

	static {
		CURRENT_CONTEXT_SCOPED_VALUE = ScopedValue.newInstance();
	}

	@NonNull
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

		@NonNull
		public Builder locale(@Nullable Locale locale) {
			this.locale = locale;
			return this;
		}

		@NonNull
		public Builder timeZone(@Nullable ZoneId timeZone) {
			this.timeZone = timeZone;
			return this;
		}

		@NonNull
		public Builder request(@Nullable Request request) {
			this.request = request;
			return this;
		}

		@NonNull
		public Builder resourceMethod(@Nullable ResourceMethod resourceMethod) {
			this.resourceMethod = resourceMethod;
			return this;
		}

		@NonNull
		public Builder account(@Nullable Account account) {
			this.account = account;
			return this;
		}

		@NonNull
		public CurrentContext build() {
			return new CurrentContext(this);
		}
	}

	@NonNull
	public static Builder with(@Nullable Locale locale,
														 @Nullable ZoneId timeZone) {
		return new Builder().locale(locale).timeZone(timeZone);
	}

	@NonNull
	public static Builder withRequest(@Nullable Request request) {
		return withRequest(request, null);
	}

	@NonNull
	public static Builder withRequest(@Nullable Request request,
																		@Nullable ResourceMethod resourceMethod) {
		return new Builder().request(request).resourceMethod(resourceMethod);
	}

	@NonNull
	public static Builder withAccount(@Nullable Account account) {
		return new Builder().account(account);
	}

	@NonNull
	private final Locale locale;
	@NonNull
	private final ZoneId timeZone;
	@Nullable
	private final Request request;
	@Nullable
	private final ResourceMethod resourceMethod;
	@Nullable
	private final Account account;

	private CurrentContext(@NonNull Builder builder) {
		requireNonNull(builder);

		this.timeZone = builder.timeZone == null ? Configuration.getDefaultTimeZone() : builder.timeZone;
		this.locale = builder.locale == null ? Configuration.getDefaultLocale() : builder.locale;
		this.request = builder.request;
		this.resourceMethod = builder.resourceMethod;
		this.account = builder.account;
	}

	public void run(@NonNull Runnable runnable) {
		requireNonNull(runnable);
		run(() -> {
			runnable.run();
			return null;
		});
	}

	@Nullable
	public <T> T run(@NonNull Supplier<T> supplier) {
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

	@NonNull
	@Override
	public String toString() {
		StringJoiner joiner = new StringJoiner(", ", format("%s{", CurrentContext.class.getSimpleName()), "}");

		getAccount().ifPresent(account -> joiner.add(format("accountId=%s", account.accountId())));

		joiner.add(format("locale=%s", getLocale().toLanguageTag()));
		joiner.add(format("timeZone=%s", getTimeZone().getId()));

		getRequest().ifPresent(request -> joiner.add(format("request=%s %s", request.getHttpMethod().name(), request.getRawPath())));

		return joiner.toString();
	}

	@NonNull
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
	}

	@NonNull
	public Optional<ResourceMethod> getResourceMethod() {
		return Optional.ofNullable(this.resourceMethod);
	}

	@NonNull
	public Optional<Account> getAccount() {
		return Optional.ofNullable(this.account);
	}

	@NonNull
	public ZoneId getTimeZone() {
		return this.timeZone;
	}

	@NonNull
	public Locale getLocale() {
		return this.locale;
	}

	@NonNull
	private String determineLoggingDescription() {
		Request request = this.getRequest().orElse(null);
		Account account = this.getAccount().orElse(null);

		String requestDescription = request == null ? "background thread" : request.getId().toString();
		String accountDescription = account == null ? "unauthenticated" : account.name();

		return format("%s (%s)", requestDescription, accountDescription);
	}
}

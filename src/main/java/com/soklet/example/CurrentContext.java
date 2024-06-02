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
import com.soklet.example.model.db.Account;
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
	private final Request request;
	@Nullable
	private final Account account;
	@Nonnull
	private final Locale locale;
	@Nonnull
	private final ZoneId timeZone;

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
		private Locale locale;
		@Nullable
		private ZoneId timeZone;
		@Nullable
		private Request request;
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
	public static Builder with(@Nonnull Locale locale,
														 @Nonnull ZoneId timeZone) {
		return new Builder().locale(locale).timeZone(timeZone);
	}

	@Nonnull
	public static Builder withRequest(@Nullable Request request) {
		return new Builder().request(request);
	}

	@Nonnull
	public static Builder withAccount(@Nullable Account account) {
		return new Builder().account(account);
	}

	private CurrentContext(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.request = builder.request;
		this.account = builder.account;
		this.timeZone = determineTimeZone(builder);
		this.locale = determineLocale(builder);
	}

	public void run(@Nonnull Runnable runnable) {
		requireNonNull(runnable);

		// Maintain a stack of scoped current contexts so we can set logging context
		// and clear it out after the topmost context has been unwound
		Deque<CurrentContext> currentContextStack = CURRENT_CONTEXT_STACK_SCOPED_VALUE.orElse(new ArrayDeque<>());

		ScopedValue.where(CURRENT_CONTEXT_STACK_SCOPED_VALUE, currentContextStack).run(() -> {
			currentContextStack.push(this);
			MDC.put("CURRENT_CONTEXT", determineLoggingDescription());

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
	public Optional<Request> getRequest() {
		return Optional.ofNullable(this.request);
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
	protected String determineLoggingDescription() {
		CurrentContext currentContext = get();
		Request request = currentContext.getRequest().orElse(null);
		Account account = currentContext.getAccount().orElse(null);

		String requestDescription = request == null ? "background thread" : request.getId().toString();
		String accountDescription = account == null ? "unauthenticated" : account.name();

		return format("%s (%s)", requestDescription, accountDescription);
	}

	@Nonnull
	protected Locale determineLocale(@Nonnull Builder builder) {
		requireNonNull(builder);

		// If an explicit locale was specified, use it
		if (builder.locale != null)
			return builder.locale;

		Request request = builder.request;

		// If this is in the context of a web request, allow clients to specify a special header which indicates preferred locale
		if (request != null) {
			String localeHeader = request.getHeader("X-Locale").orElse(null);

			if (localeHeader != null) {
				try {
					return Locale.forLanguageTag(localeHeader);
				} catch (Exception ignored) {
					// Illegal locale specified, we'll just try one of our fallbacks
				}
			}
		}

		// Next, if there's a signed-in account, use their configured locale
		Account account = builder.account;

		if (account != null)
			return account.locale();

		// If that didn't work, and we're in the context of a web request, try its Accept-Language header
		if (request != null && request.getLocales().size() > 0)
			return request.getLocales().get(0);

		// Still not sure?  Fall back to a safe default
		return Configuration.getDefaultLocale();
	}

	@Nonnull
	protected ZoneId determineTimeZone(@Nonnull Builder builder) {
		requireNonNull(builder);

		// If an explicit time zone was specified, use it
		if (builder.timeZone != null)
			return builder.timeZone;

		Request request = builder.request;

		// If this is in the context of a web request, allow clients to specify a special header which indicates preferred timezone
		if (request != null) {
			String timeZoneHeader = request.getHeader("X-Time-Zone").orElse(null);

			if (timeZoneHeader != null) {
				try {
					return ZoneId.of(timeZoneHeader);
				} catch (Exception ignored) {
					// Illegal timezone specified, we'll just try one of our fallbacks
				}
			}
		}

		// Next, if there's a signed-in account, use their configured timezone
		Account account = builder.account;

		if (account != null)
			return account.timeZone();

		// Still not sure?  Fall back to a safe default
		return Configuration.getDefaultTimeZone();
	}
}
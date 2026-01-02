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

package com.soklet.toystore.model.api.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Public-facing representation of an exception that bubbled out of the system.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ErrorResponse {
	@NonNull
	private final String summary;
	@NonNull
	private final List<@NonNull String> generalErrors;
	@NonNull
	private final Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors;
	@NonNull
	private final Map<@NonNull String, @NonNull Object> metadata;

	@NonNull
	public static Builder withSummary(@NonNull String summary) {
		requireNonNull(summary);
		return new Builder(summary);
	}

	private ErrorResponse(@NonNull Builder builder) {
		requireNonNull(builder);

		this.summary = requireNonNull(builder.summary);
		this.generalErrors = builder.generalErrors == null ? List.of() : Collections.unmodifiableList(builder.generalErrors);
		this.fieldErrors = builder.fieldErrors == null ? Map.of() : Collections.unmodifiableMap(builder.fieldErrors);
		this.metadata = builder.metadata == null ? Map.of() : Collections.unmodifiableMap(builder.metadata);
	}

	@NotThreadSafe
	public static class Builder {
		@NonNull
		private String summary;
		@Nullable
		private List<@NonNull String> generalErrors;
		@Nullable
		private Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors;
		@Nullable
		private Map<@NonNull String, @NonNull Object> metadata;

		private Builder(@NonNull String summary) {
			requireNonNull(summary);
			this.summary = summary;
		}

		@NonNull
		public Builder summary(@NonNull String summary) {
			requireNonNull(summary);
			this.summary = summary;
			return this;
		}

		@NonNull
		public Builder generalErrors(@Nullable List<@NonNull String> generalErrors) {
			this.generalErrors = generalErrors;
			return this;
		}

		@NonNull
		public Builder fieldErrors(@Nullable Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors) {
			this.fieldErrors = fieldErrors;
			return this;
		}

		@NonNull
		public Builder metadata(@Nullable Map<@NonNull String, @NonNull Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		@NonNull
		public ErrorResponse build() {
			return new ErrorResponse(this);
		}
	}

	@NonNull
	public String getSummary() {
		return this.summary;
	}

	@NonNull
	public List<@NonNull String> getGeneralErrors() {
		return this.generalErrors;
	}

	@NonNull
	public Map<@NonNull String, @NonNull List<@NonNull String>> getFieldErrors() {
		return this.fieldErrors;
	}

	@NonNull
	public Map<@NonNull String, @NonNull Object> getMetadata() {
		return this.metadata;
	}
}

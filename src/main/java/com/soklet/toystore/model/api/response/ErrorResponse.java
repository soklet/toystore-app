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

package com.soklet.toystore.model.api.response;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	@Nonnull
	private final String summary;
	@Nonnull
	private final List<String> generalErrors;
	@Nonnull
	private final Map<String, List<String>> fieldErrors;
	@Nonnull
	private final Map<String, Object> metadata;

	@Nonnull
	public static Builder withSummary(@Nonnull String summary) {
		requireNonNull(summary);
		return new Builder(summary);
	}

	private ErrorResponse(@Nonnull Builder builder) {
		requireNonNull(builder);

		this.summary = requireNonNull(builder.summary);
		this.generalErrors = builder.generalErrors == null ? List.of() : Collections.unmodifiableList(builder.generalErrors);
		this.fieldErrors = builder.fieldErrors == null ? Map.of() : Collections.unmodifiableMap(builder.fieldErrors);
		this.metadata = builder.metadata == null ? Map.of() : Collections.unmodifiableMap(builder.metadata);
	}

	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private String summary;
		@Nullable
		private List<String> generalErrors;
		@Nullable
		private Map<String, List<String>> fieldErrors;
		@Nullable
		private Map<String, Object> metadata;

		private Builder(@Nonnull String summary) {
			requireNonNull(summary);
			this.summary = summary;
		}

		@Nonnull
		public Builder summary(@Nonnull String summary) {
			requireNonNull(summary);
			this.summary = summary;
			return this;
		}

		@Nonnull
		public Builder generalErrors(@Nullable List<String> generalErrors) {
			this.generalErrors = generalErrors;
			return this;
		}

		@Nonnull
		public Builder fieldErrors(@Nullable Map<String, List<String>> fieldErrors) {
			this.fieldErrors = fieldErrors;
			return this;
		}

		@Nonnull
		public Builder metadata(@Nullable Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		@Nonnull
		public ErrorResponse build() {
			return new ErrorResponse(this);
		}
	}

	@Nonnull
	public String getSummary() {
		return this.summary;
	}

	@Nonnull
	public List<String> getGeneralErrors() {
		return this.generalErrors;
	}

	@Nonnull
	public Map<String, List<String>> getFieldErrors() {
		return this.fieldErrors;
	}

	@Nonnull
	public Map<String, Object> getMetadata() {
		return this.metadata;
	}
}
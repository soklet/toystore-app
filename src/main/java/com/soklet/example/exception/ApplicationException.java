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

package com.soklet.example.exception;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class ApplicationException extends RuntimeException {
	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final List<String> errors;
	@Nonnull
	private final Map<String, String> fieldErrors;
	@Nonnull
	private final Map<String, Object> metadata;

	@Nonnull
	public static Builder withStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	protected ApplicationException(@Nonnull Builder builder) {
		super();

		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.errors = builder.errors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(builder.errors));
		this.fieldErrors = builder.fieldErrors == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(builder.fieldErrors));
		this.metadata = builder.metadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(builder.metadata));
	}

	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private Integer statusCode;
		@Nullable
		private List<String> errors;
		@Nullable
		private Map<String, String> fieldErrors;
		@Nullable
		private Map<String, Object> metadata;

		protected Builder(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@Nonnull
		public Builder statusCode(@Nonnull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
			return this;
		}

		@Nonnull
		public Builder error(@Nullable String error) {
			this.errors = error == null ? null : List.of(error);
			return this;
		}

		@Nonnull
		public Builder errors(@Nullable List<String> errors) {
			this.errors = errors;
			return this;
		}

		@Nonnull
		public Builder fieldErrors(@Nullable Map<String, String> fieldErrors) {
			this.fieldErrors = fieldErrors;
			return this;
		}

		@Nonnull
		public Builder metadata(@Nullable Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		@Nonnull
		public ApplicationException build() {
			return new ApplicationException(this);
		}
	}

	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	@Nonnull
	public List<String> getErrors() {
		return this.errors;
	}

	@Nonnull
	public Map<String, String> getFieldErrors() {
		return this.fieldErrors;
	}

	@Nonnull
	public Map<String, Object> getMetadata() {
		return this.metadata;
	}
}
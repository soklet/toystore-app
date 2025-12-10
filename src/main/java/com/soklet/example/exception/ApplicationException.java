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

package com.soklet.example.exception;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * An application-specific exception which can be effectively serialized for client consumption.
 * <p>
 * Supports the following:
 * <ul>
 *   <li>HTTP status code</li>
 *   <li>General errors</li>
 *   <li>Field-specific errors</li>
 *   <li>Arbitrary bag of metadata</li>
 * </ul>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class ApplicationException extends RuntimeException {
	@Nonnull
	private final Integer statusCode;
	@Nonnull
	private final List<String> generalErrors;
	@Nonnull
	private final Map<String, List<String>> fieldErrors;
	@Nonnull
	private final Map<String, Object> metadata;

	@Nonnull
	public static Builder withStatusCode(@Nonnull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	@Nonnull
	public static Builder withStatusCodeAndErrors(@Nonnull Integer statusCode,
																								@Nullable ErrorCollector errorCollector) {
		requireNonNull(statusCode);

		Builder builder = new Builder(statusCode);

		if (errorCollector != null) {
			builder.generalErrors(errorCollector.generalErrors);
			builder.fieldErrors(errorCollector.fieldErrors);
		}

		return builder;
	}

	@Nonnull
	public static Builder withStatusCodeAndGeneralError(@Nonnull Integer statusCode,
																											@Nonnull String generalError) {
		requireNonNull(statusCode);
		requireNonNull(generalError);

		Builder builder = new Builder(statusCode);
		builder.generalErrors(List.of(generalError));

		return builder;
	}

	private ApplicationException(@Nonnull String message,
															 @Nonnull Builder builder) {
		super(requireNonNull(message));
		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.generalErrors = builder.generalErrors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(builder.generalErrors));
		this.fieldErrors = builder.fieldErrors == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.fieldErrors));
		this.metadata = builder.metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
	}

	@NotThreadSafe
	public static class ErrorCollector {
		@Nonnull
		private final List<String> generalErrors;
		@Nonnull
		private final Map<String, List<String>> fieldErrors;

		public ErrorCollector() {
			this.generalErrors = new ArrayList<>();
			this.fieldErrors = new LinkedHashMap<>();
		}

		public void addGeneralError(@Nonnull String generalError) {
			requireNonNull(generalError);
			this.generalErrors.add(generalError);
		}

		public void addFieldError(@Nonnull String field,
															@Nonnull String error) {
			requireNonNull(field);
			requireNonNull(error);

			List<String> errors = this.fieldErrors.get(field);

			if (errors == null) {
				errors = new ArrayList<>(4);
				this.fieldErrors.put(field, errors);
			}

			if (!errors.contains(error))
				errors.add(error);
		}

		@Nonnull
		public Boolean hasErrors() {
			return this.generalErrors.size() > 0 || this.fieldErrors.size() > 0;
		}
	}

	@NotThreadSafe
	public static class Builder {
		@Nonnull
		private Integer statusCode;
		@Nullable
		private List<String> generalErrors;
		@Nullable
		private Map<String, List<String>> fieldErrors;
		@Nullable
		private Map<String, Object> metadata;

		private Builder(@Nonnull Integer statusCode) {
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
		public Builder generalError(@Nullable String generalError) {
			this.generalErrors = generalError == null ? null : List.of(generalError);
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
		public ApplicationException build() {
			// Create an exception message by combining fields
			List<String> messageComponents = new ArrayList<>(4);
			messageComponents.add(format("Status %d", this.statusCode));

			if (this.generalErrors != null && this.generalErrors.size() > 0)
				messageComponents.add(format("General Errors: %s", this.generalErrors));

			if (this.fieldErrors != null && this.fieldErrors.size() > 0)
				messageComponents.add(format("Field Errors: %s", this.fieldErrors));

			if (this.metadata != null && this.metadata.size() > 0)
				messageComponents.add(format("Metadata: %s", this.metadata));

			String message = messageComponents.stream().collect(Collectors.joining(", "));

			return new ApplicationException(message, this);
		}
	}

	@Nonnull
	public Integer getStatusCode() {
		return this.statusCode;
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
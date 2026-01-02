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

package com.soklet.toystore.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
	@NonNull
	private final Integer statusCode;
	@NonNull
	private final List<@NonNull String> generalErrors;
	@NonNull
	private final Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors;
	@NonNull
	private final Map<@NonNull String, @NonNull Object> metadata;

	@NonNull
	public static Builder withStatusCode(@NonNull Integer statusCode) {
		requireNonNull(statusCode);
		return new Builder(statusCode);
	}

	@NonNull
	public static Builder withStatusCodeAndErrors(@NonNull Integer statusCode,
																								@Nullable ErrorCollector errorCollector) {
		requireNonNull(statusCode);

		Builder builder = new Builder(statusCode);

		if (errorCollector != null) {
			builder.generalErrors(errorCollector.generalErrors);
			builder.fieldErrors(errorCollector.fieldErrors);
		}

		return builder;
	}

	@NonNull
	public static Builder withStatusCodeAndGeneralError(@NonNull Integer statusCode,
																											@NonNull String generalError) {
		requireNonNull(statusCode);
		requireNonNull(generalError);

		Builder builder = new Builder(statusCode);
		builder.generalErrors(List.of(generalError));

		return builder;
	}

	private ApplicationException(@NonNull String message,
															 @NonNull Builder builder) {
		super(requireNonNull(message));
		requireNonNull(builder);

		this.statusCode = builder.statusCode;
		this.generalErrors = builder.generalErrors == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(builder.generalErrors));
		this.fieldErrors = builder.fieldErrors == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.fieldErrors));
		this.metadata = builder.metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
	}

	@NotThreadSafe
	public static class ErrorCollector {
		@NonNull
		private final List<@NonNull String> generalErrors;
		@NonNull
		private final Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors;

		public ErrorCollector() {
			this.generalErrors = new ArrayList<>();
			this.fieldErrors = new LinkedHashMap<>();
		}

		public void addGeneralError(@NonNull String generalError) {
			requireNonNull(generalError);
			this.generalErrors.add(generalError);
		}

		public void addFieldError(@NonNull String field,
															@NonNull String error) {
			requireNonNull(field);
			requireNonNull(error);

			List<@NonNull String> errors = this.fieldErrors.get(field);

			if (errors == null) {
				errors = new ArrayList<>(4);
				this.fieldErrors.put(field, errors);
			}

			if (!errors.contains(error))
				errors.add(error);
		}

		@NonNull
		public Boolean hasErrors() {
			return this.generalErrors.size() > 0 || this.fieldErrors.size() > 0;
		}
	}

	@NotThreadSafe
	public static class Builder {
		@NonNull
		private Integer statusCode;
		@Nullable
		private List<@NonNull String> generalErrors;
		@Nullable
		private Map<@NonNull String, @NonNull List<@NonNull String>> fieldErrors;
		@Nullable
		private Map<@NonNull String, @NonNull Object> metadata;

		private Builder(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
		}

		@NonNull
		public Builder statusCode(@NonNull Integer statusCode) {
			requireNonNull(statusCode);
			this.statusCode = statusCode;
			return this;
		}

		@NonNull
		public Builder generalError(@Nullable String generalError) {
			this.generalErrors = generalError == null ? null : List.of(generalError);
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

	@NonNull
	public Integer getStatusCode() {
		return this.statusCode;
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

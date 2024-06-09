/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example.model.api.response;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class ErrorResponse {
	@Nonnull
	private final String errorSummary;
	@Nonnull
	private final List<String> generalErrors;
	@Nonnull
	private final Map<String, String> fieldErrors;
	@Nonnull
	private final Map<String, Object> metadata;

	public ErrorResponse(@Nonnull String errorSummary,
											 @Nullable List<String> generalErrors,
											 @Nullable Map<String, String> fieldErrors,
											 @Nullable Map<String, Object> metadata) {
		requireNonNull(errorSummary);

		this.errorSummary = errorSummary;
		this.generalErrors = generalErrors == null ? List.of() : Collections.unmodifiableList(generalErrors);
		this.fieldErrors = fieldErrors == null ? Map.of() : Collections.unmodifiableMap(fieldErrors);
		this.metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
	}

	@Nonnull
	public String getErrorSummary() {
		return this.errorSummary;
	}

	@Nonnull
	public List<String> getGeneralErrors() {
		return this.generalErrors;
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
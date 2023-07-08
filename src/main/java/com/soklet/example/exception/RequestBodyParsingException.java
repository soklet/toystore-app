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

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Optional;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class RequestBodyParsingException extends RuntimeException {
	@Nullable
	private final String requestBody;

	public RequestBodyParsingException(@Nullable String requestBody) {
		this(requestBody, null);
	}

	public RequestBodyParsingException(@Nullable String requestBody,
																		 @Nullable Throwable cause) {
		super(null, cause);
		this.requestBody = requestBody;
	}

	@Nullable
	public Optional<String> getRequestBody() {
		return Optional.ofNullable(this.requestBody);
	}
}
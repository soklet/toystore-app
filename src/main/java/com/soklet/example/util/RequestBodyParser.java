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

package com.soklet.example.util;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.soklet.example.exception.RequestBodyParsingException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestBodyParser {
	@Nonnull
	private final Gson gson;

	@Inject
	public RequestBodyParser(@Nonnull Gson gson) {
		requireNonNull(gson);
		this.gson = gson;
	}

	@Nonnull
	public <T> T parse(@Nonnull String requestBody,
										 @Nonnull Class<T> requestBodyType) {
		requireNonNull(requestBody);
		requireNonNull(requestBodyType);

		try {
			return getGson().fromJson(requestBody, requestBodyType);
		} catch (Exception e) {
			throw new RequestBodyParsingException(requestBody, e);
		}
	}

	@Nonnull
	protected Gson getGson() {
		return this.gson;
	}
}

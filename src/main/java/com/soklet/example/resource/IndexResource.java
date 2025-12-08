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

package com.soklet.example.resource;

import com.google.inject.Inject;
import com.lokalized.Strings;
import com.soklet.MarshaledResponse;
import com.soklet.annotation.GET;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IndexResource {
	@Nonnull
	private final Strings strings;

	@Inject
	public IndexResource(@Nonnull Strings strings) {
		requireNonNull(strings);
		this.strings = strings;
	}

	@Nonnull
	@GET("/")
	public MarshaledResponse helloWorld() {
		// TODO: load an HTML page from disk that acts as a simple UI for making API calls + an SSE listener

		// By returning MarshaledResponse instead of Response,
		// we are saying "I already know how to turn my response into bytes,
		// so please don't perform extra processing on it (e.g. turn it into JSON)"
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain;charset=UTF-8")))
				.body(getStrings().get("Hello, world!").getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	@GET("/health-check")
	public MarshaledResponse healthCheck() {
		// By returning MarshaledResponse instead of Response,
		// we are saying "I already know how to turn my response into bytes,
		// so please don't perform extra processing on it (e.g. turn it into JSON)"
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain;charset=UTF-8")))
				.body(getStrings().get("OK").getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Nonnull
	protected Strings getStrings() {
		return this.strings;
	}
}
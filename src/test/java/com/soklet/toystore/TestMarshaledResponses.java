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

package com.soklet.toystore;

import com.soklet.MarshaledResponse;
import com.soklet.MarshaledResponseBody;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

/**
 * Test helpers for working with Soklet responses.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TestMarshaledResponses {
	private TestMarshaledResponses() {
		// Utility class
	}

	@NonNull
	public static String responseBodyAsString(@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		MarshaledResponseBody responseBody = marshaledResponse.getBody().orElseThrow();

		if (responseBody instanceof MarshaledResponseBody.Bytes bytesBody)
			return new String(bytesBody.getBytes(), StandardCharsets.UTF_8);

		if (responseBody instanceof MarshaledResponseBody.ByteBuffer byteBufferBody) {
			java.nio.ByteBuffer byteBuffer = byteBufferBody.getBuffer();
			byte[] bytes = new byte[byteBuffer.remaining()];
			byteBuffer.get(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		}

		throw new AssertionError("Expected byte-backed response body");
	}
}

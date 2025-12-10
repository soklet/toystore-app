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

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Exception thrown when the authenticated account required to perform an operation does not meet security criteria (e.g. wrong role, doesn't own the data, ...)
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public class AuthorizationException extends RuntimeException {
	public AuthorizationException() {
		super();
	}

	public AuthorizationException(@Nullable String message) {
		super(message);
	}

	public AuthorizationException(@Nullable String message,
																@Nullable Throwable cause) {
		super(message, cause);
	}
}
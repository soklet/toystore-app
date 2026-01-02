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

package com.soklet.toystore.mock;

import com.soklet.toystore.util.ErrorReporter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

import static com.soklet.toystore.util.Normalizer.trimAggressivelyToNull;

/**
 * Mock implementation of {@link ErrorReporter} which dumps errors to a logger.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MockErrorReporter implements ErrorReporter {
	@NonNull
	private final Logger logger;

	public MockErrorReporter() {
		this.logger = LoggerFactory.getLogger(getClass());
	}

	@Override
	public void reportError(@Nullable String message) {
		reportError(message, null);
	}

	@Override
	public void reportError(@Nullable Throwable throwable) {
		reportError(null, throwable);
	}

	@Override
	public void reportError(@Nullable String message,
													@Nullable Throwable throwable) {
		message = trimAggressivelyToNull(message);

		if (message == null)
			message = "An unexpected error occurred.";

		if (throwable != null)
			getLogger().error(message, throwable);
		else
			getLogger().error(message);
	}

	@NonNull
	private Logger getLogger() {
		return this.logger;
	}
}
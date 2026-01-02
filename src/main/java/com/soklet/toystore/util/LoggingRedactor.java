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

package com.soklet.toystore.util;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.regex.Pattern;

/**
 * Logback converter that redacts JWT tokens from log messages.
 * <p>
 * JWTs are identified by their characteristic structure: three base64url-encoded
 * segments separated by dots, where the header and payload segments start with "eyJ"
 * (the base64url encoding of '{"').
 * <p>
 * Usage in logback.xml:
 * <pre>{@code
 * <conversionRule conversionWord="msg" converterClass="com.soklet.toystore.util.LoggingRedactor"/>
 * }</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class LoggingRedactor extends MessageConverter {
	@NonNull
	private static final Pattern JWT_PATTERN;

	static {
		// Matches JWTs: header.payload.signature where header and payload are JSON objects (start with "eyJ")
		// eyJ = base64url encoding of '{"' which all JWT headers/payloads begin with
		JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+");
	}

	@Override
	public String convert(ILoggingEvent event) {
		String message = super.convert(event);
		return JWT_PATTERN.matcher(message).replaceAll("[REDACTED]");
	}
}

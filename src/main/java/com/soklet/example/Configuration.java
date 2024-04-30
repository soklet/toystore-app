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

package com.soklet.example;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Configuration {
	@Nonnull
	private static final Locale FALLBACK_LOCALE;
	@Nonnull
	private static final ZoneId FALLBACK_TIME_ZONE;

	static {
		FALLBACK_LOCALE = Locale.US;
		FALLBACK_TIME_ZONE = ZoneId.of("UTC");
	}

	@Nonnull
	private final Boolean runningInDocker;
	@Nonnull
	private final Boolean stopOnKeypress;
	@Nonnull
	private final Integer port;
	@Nonnull
	private final KeyPair keyPair;
	@Nonnull
	private final Set<String> corsWhitelistedOrigins;

	public Configuration() {
		// TODO: this ctor should pull from env vars, an alternate might pull from a file
		this.runningInDocker = "true".equalsIgnoreCase(System.getenv("RUNNING_IN_DOCKER"));
		this.stopOnKeypress = !this.runningInDocker;
		this.port = 8080;
		this.corsWhitelistedOrigins = Set.of();

		// This example app generates a transient in-memory keypair.
		// Don't do this in real systems.
		// A real app would load from a trusted location on the filesystem or a cloud platform's Secrets Manager
		this.keyPair = generateKeyPair("RSA", 2048);
	}

	@Nonnull
	protected KeyPair generateKeyPair(@Nonnull String algorithm,
																		@Nonnull Integer keySize) {
		requireNonNull(algorithm);
		requireNonNull(keySize);

		try {
			KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(algorithm);
			SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
			keyPairGenerator.initialize(keySize, secureRandom);
			return keyPairGenerator.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Nonnull
	public static Locale getFallbackLocale() {
		return FALLBACK_LOCALE;
	}

	@Nonnull
	public static ZoneId getFallbackTimeZone() {
		return FALLBACK_TIME_ZONE;
	}

	@Nonnull
	public Boolean getRunningInDocker() {
		return this.runningInDocker;
	}

	@Nonnull
	public Boolean getStopOnKeypress() {
		return this.stopOnKeypress;
	}

	@Nonnull
	public Integer getPort() {
		return this.port;
	}

	@Nonnull
	public KeyPair getKeyPair() {
		return this.keyPair;
	}

	@Nonnull
	public Set<String> getCorsWhitelistedOrigins() {
		return this.corsWhitelistedOrigins;
	}
}

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Configuration {
	@Nonnull
	private static final Locale DEFAULT_LOCALE;
	@Nonnull
	private static final ZoneId DEFAULT_TIME_ZONE;

	static {
		DEFAULT_LOCALE = Locale.US;
		DEFAULT_TIME_ZONE = ZoneId.of("UTC");
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
		// TODO: this ctor could pull from env vars, or alternately pull from a file
		this.runningInDocker = "true".equalsIgnoreCase(System.getenv("RUNNING_IN_DOCKER"));
		this.stopOnKeypress = !this.runningInDocker;
		this.port = 8080;
		this.corsWhitelistedOrigins = Set.of();
		this.keyPair = loadKeyPair();

		// Initialize Logback if not done already
		if (System.getProperty("logback.configurationFile") == null)
			System.setProperty("logback.configurationFile", "logback.xml");
	}

	@Nonnull
	protected KeyPair loadKeyPair() {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance("RSA");

			String publicKeyAsString = Files.readString(Path.of("src/main/resources/rsa.public"), StandardCharsets.UTF_8);
			X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyAsString));
			PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

			// A real app would load from a trusted location on the filesystem or a cloud platform's Secrets Manager
			String privateKeyAsString = Files.readString(Path.of("src/main/resources/rsa.private"), StandardCharsets.UTF_8);
			PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyAsString));
			PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);

			return new KeyPair(publicKey, privateKey);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Nonnull
	public static Locale getDefaultLocale() {
		return DEFAULT_LOCALE;
	}

	@Nonnull
	public static ZoneId getDefaultTimeZone() {
		return DEFAULT_TIME_ZONE;
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

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

package com.soklet.toystore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates system-wide configuration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Configuration {
	@Nonnull
	private static final Locale DEFAULT_LOCALE;
	@Nonnull
	private static final ZoneId DEFAULT_TIME_ZONE;
	@Nonnull
	private static final Gson GSON;

	static {
		DEFAULT_LOCALE = Locale.US;
		DEFAULT_TIME_ZONE = ZoneId.of("UTC");
		GSON = new GsonBuilder().disableHtmlEscaping().create();
	}

	@Nonnull
	private final String environment;
	@Nonnull
	private final Boolean runningInDocker;
	@Nonnull
	private final Boolean stopOnKeypress;
	@Nonnull
	private final Integer port;
	@Nonnull
	private final Integer serverSentEventPort;
	@Nonnull
	private final Duration authenticationExpiration;
	@Nonnull
	private final Duration serverSentEventContextExpiration;
	@Nonnull
	private final KeyPair keyPair;
	@Nonnull
	private final Set<String> corsWhitelistedOrigins;

	public Configuration(@Nonnull String environment) {
		requireNonNull(environment);

		ConfigFile configFile = loadConfigFileForEnvironment(environment);

		this.environment = environment;
		this.runningInDocker = "true".equalsIgnoreCase(System.getenv("APP_RUNNING_IN_DOCKER"));
		this.stopOnKeypress = !this.runningInDocker;
		this.port = requireNonNull(configFile.port());
		this.serverSentEventPort = requireNonNull(configFile.serverSentEventPort());
		this.authenticationExpiration = Duration.ofSeconds(configFile.authenticationExpirationInSeconds());
		this.serverSentEventContextExpiration = Duration.ofSeconds(configFile.serverSentEventContextExpirationInSeconds());
		this.corsWhitelistedOrigins = configFile.corsWhitelistedOrigins() == null ? Set.of() : configFile.corsWhitelistedOrigins();
		this.keyPair = loadKeyPair(configFile.keyPair());

		// Initialize Logback if not done already
		if (System.getProperty("logback.configurationFile") == null)
			System.setProperty("logback.configurationFile", format("config/%s/logback.xml", environment));
	}

	@Nonnull
	private KeyPair loadKeyPair(@Nonnull ConfigFile.ConfigKeyPair configKeyPair) {
		requireNonNull(configKeyPair);
		requireNonNull(configKeyPair.algorithm());
		requireNonNull(configKeyPair.publicKey());
		requireNonNull(configKeyPair.privateKey());

		// Keypair generation is documented at https://www.soklet.com/docs/toy-store-app#generating-keypairs
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(configKeyPair.algorithm());

			EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(configKeyPair.publicKey()));
			PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

			// A real app would load from a secure location on the filesystem or a cloud platform's Secrets Manager
			EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(configKeyPair.privateKey()));
			PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);

			return new KeyPair(publicKey, privateKey);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException(e);
		}
	}

	@Nonnull
	private ConfigFile loadConfigFileForEnvironment(@Nonnull String environment) {
		Path configFile = Path.of(format("config/%s/settings.json", environment));

		if (!Files.isRegularFile(configFile))
			throw new IllegalArgumentException(format("Config file not found at %s", configFile.toAbsolutePath()));

		try {
			return GSON.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), ConfigFile.class);
		} catch (IOException e) {
			throw new UncheckedIOException(format("Error reading from %s", configFile.toAbsolutePath()), e);
		}
	}

	// Record that maps to the config/{environment}/settings.json file format
	private record ConfigFile(
			@Nonnull Integer port,
			@Nonnull Integer serverSentEventPort,
			@Nonnull Set<String> corsWhitelistedOrigins,
			@Nonnull Integer authenticationExpirationInSeconds,
			@Nonnull Integer serverSentEventContextExpirationInSeconds,
			@Nonnull ConfigKeyPair keyPair
	) {
		public ConfigFile {
			requireNonNull(port);
			requireNonNull(serverSentEventPort);
			requireNonNull(corsWhitelistedOrigins);
			requireNonNull(authenticationExpirationInSeconds);
			requireNonNull(serverSentEventContextExpirationInSeconds);
			requireNonNull(keyPair);
		}

		private record ConfigKeyPair(
				@Nonnull String algorithm,
				@Nonnull String publicKey,
				@Nonnull String privateKey
		) {
			public ConfigKeyPair {
				requireNonNull(algorithm);
				requireNonNull(publicKey);
				requireNonNull(privateKey);
			}
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
	public String getEnvironment() {
		return this.environment;
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
	public Integer getServerSentEventPort() {
		return this.serverSentEventPort;
	}

	@Nonnull
	public Duration getAuthenticationExpiration() {
		return this.authenticationExpiration;
	}

	@Nonnull
	public Duration getServerSentEventContextExpiration() {
		return this.serverSentEventContextExpiration;
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

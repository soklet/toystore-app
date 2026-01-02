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

package com.soklet.toystore.util;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Supports hashing passwords via {@link #hashPassword(String)} and verifying
 * plaintext against a hash via {@link #verifyPassword(String, String)}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class PasswordManager {
	@NonNull
	private static final Integer DEFAULT_ITERATIONS;
	@NonNull
	private static final Integer DEFAULT_SALT_LENGTH;
	@NonNull
	private static final Integer DEFAULT_KEY_LENGTH;

	static {
		DEFAULT_ITERATIONS = 210_000; // OWASP 2023 recommendation
		DEFAULT_SALT_LENGTH = 64;
		DEFAULT_KEY_LENGTH = 512;
	}

	@NonNull
	private final String hashAlgorithm;
	@NonNull
	private final Integer iterations;
	@NonNull
	private final Integer saltLength;
	@NonNull
	private final Integer keyLength;

	@NonNull
	public static Builder withHashAlgorithm(@NonNull String hashAlgorithm) {
		requireNonNull(hashAlgorithm);
		return new Builder(hashAlgorithm);
	}

	private PasswordManager(@NonNull Builder builder) {
		requireNonNull(builder);

		this.hashAlgorithm = requireNonNull(builder.hashAlgorithm);
		this.iterations = builder.iterations == null ? DEFAULT_ITERATIONS : builder.iterations;
		this.saltLength = builder.saltLength == null ? DEFAULT_SALT_LENGTH : builder.saltLength;
		this.keyLength = builder.keyLength == null ? DEFAULT_KEY_LENGTH : builder.keyLength;
	}

	@NonNull
	public String hashPassword(@NonNull String plaintextPassword) {
		requireNonNull(plaintextPassword);

		try {
			// Generate the salt
			byte[] salt = new byte[getSaltLength()];
			SecureRandom secureRandom = new SecureRandom();
			secureRandom.nextBytes(salt);

			// Generate the hash
			PBEKeySpec keySpec = new PBEKeySpec(plaintextPassword.toCharArray(), salt, getIterations(), getKeyLength());
			SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(getHashAlgorithm());
			byte[] hashedPassword = secretKeyFactory.generateSecret(keySpec).getEncoded();

			// Generate a string of the form:
			// <hash algorithm>:<iterations>:<key length>:<salt>:<hashed password>
			return format("%s:%d:%d:%s:%s", getHashAlgorithm(), getIterations(), getKeyLength(), base64Encode(salt), base64Encode(hashedPassword));
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException(e);
		}
	}

	@NonNull
	public Boolean verifyPassword(@NonNull String plaintextPassword,
																@NonNull String hashedPassword) {
		requireNonNull(plaintextPassword);
		requireNonNull(hashedPassword);

		try {
			String[] components = hashedPassword.split(":");

			if (components.length != 5)
				throw new IllegalArgumentException("Malformed password hash");

			String hashAlgorithm = components[0];
			int iterations = Integer.parseInt(components[1]);
			int keyLength = Integer.parseInt(components[2]);
			byte[] salt = base64Decode(components[3]);
			byte[] hashedPasswordComponent = base64Decode(components[4]);

			PBEKeySpec keySpec = new PBEKeySpec(plaintextPassword.toCharArray(), salt, iterations, keyLength);
			SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(hashAlgorithm);
			byte[] comparisonHash = secretKeyFactory.generateSecret(keySpec).getEncoded();

			return MessageDigest.isEqual(hashedPasswordComponent, comparisonHash);
		} catch (NumberFormatException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalArgumentException("Malformed password hash", e);
		}
	}

	@NonNull
	private static String base64Encode(@NonNull byte[] bytes) {
		requireNonNull(bytes);
		return Base64.getEncoder().withoutPadding().encodeToString(bytes);
	}

	@NonNull
	private static byte[] base64Decode(@NonNull String string) {
		requireNonNull(string);
		return Base64.getDecoder().decode(string);
	}

	@NotThreadSafe
	public static class Builder {
		@NonNull
		private final String hashAlgorithm;
		@Nullable
		private Integer iterations;
		@Nullable
		private Integer saltLength;
		@Nullable
		private Integer keyLength;

		private Builder(@NonNull String hashAlgorithm) {
			requireNonNull(hashAlgorithm);
			this.hashAlgorithm = hashAlgorithm;
		}

		@NonNull
		public Builder iterations(@Nullable Integer iterations) {
			this.iterations = iterations;
			return this;
		}

		@NonNull
		public Builder saltLength(@Nullable Integer saltLength) {
			this.saltLength = saltLength;
			return this;
		}

		@NonNull
		public Builder keyLength(@Nullable Integer keyLength) {
			this.keyLength = keyLength;
			return this;
		}

		@NonNull
		public PasswordManager build() {
			return new PasswordManager(this);
		}
	}

	@NonNull
	public String getHashAlgorithm() {
		return this.hashAlgorithm;
	}

	@NonNull
	public Integer getIterations() {
		return this.iterations;
	}

	@NonNull
	public Integer getSaltLength() {
		return this.saltLength;
	}

	@NonNull
	public Integer getKeyLength() {
		return this.keyLength;
	}
}
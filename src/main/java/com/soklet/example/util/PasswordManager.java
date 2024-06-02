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

package com.soklet.example.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class PasswordManager {
	@Nonnull
	private static final PasswordManager SHARED_INSTANCE;

	static {
		String rngAlgorithm = "SHA1PRNG";
		String hashAlgorithm = "PBKDF2WithHmacSHA512";
		int iterations = 5_000;
		int saltLength = 16;
		int keyLength = 128 * 8;

		SHARED_INSTANCE = new PasswordManager(rngAlgorithm, hashAlgorithm, iterations, saltLength, keyLength);
	}

	@Nonnull
	public static PasswordManager sharedInstance() {
		return SHARED_INSTANCE;
	}

	@Nullable
	private final String rngAlgorithm;
	@Nullable
	private final String hashAlgorithm;
	@Nullable
	private final Integer iterations;
	@Nonnull
	private final Integer saltLength;
	@Nonnull
	private final Integer keyLength;

	public PasswordManager(@Nonnull String rngAlgorithm,
												 @Nonnull String hashAlgorithm,
												 @Nonnull Integer iterations,
												 @Nonnull Integer saltLength,
												 @Nonnull Integer keyLength) {
		this.rngAlgorithm = requireNonNull(rngAlgorithm);
		this.hashAlgorithm = requireNonNull(hashAlgorithm);
		this.iterations = requireNonNull(iterations);
		this.saltLength = requireNonNull(saltLength);
		this.keyLength = requireNonNull(keyLength);
	}

	@Nonnull
	public String hashPassword(@Nonnull String plaintextPassword) {
		requireNonNull(plaintextPassword);

		try {
			SecureRandom secureRandom = SecureRandom.getInstance(getRngAlgorithm());
			byte[] salt = new byte[getSaltLength()];
			secureRandom.nextBytes(salt);

			PBEKeySpec keySpec = new PBEKeySpec(plaintextPassword.toCharArray(), salt, getIterations(), getKeyLength());
			SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(getHashAlgorithm());
			byte[] hashedPassword = secretKeyFactory.generateSecret(keySpec).getEncoded();

			// Generates a string of the form:
			// <iterations>:<salt>:<hashed password>
			return format("%d:%s:%s", getIterations(), base64Encode(salt), base64Encode(hashedPassword));
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException(e);
		}
	}

	@Nonnull
	public Boolean verifyPassword(@Nonnull String plaintextPassword,
																@Nonnull String hashedPassword) {
		requireNonNull(plaintextPassword);
		requireNonNull(hashedPassword);

		String[] components = hashedPassword.split(":");
		int iterationComponent = Integer.parseInt(components[0]);
		byte[] saltComponent = base64Decode(components[1]);
		byte[] hashedPasswordComponent = base64Decode(components[2]);

		try {
			PBEKeySpec keySpec = new PBEKeySpec(plaintextPassword.toCharArray(), saltComponent, iterationComponent, getKeyLength());
			SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(getHashAlgorithm());
			byte[] comparisonHash = secretKeyFactory.generateSecret(keySpec).getEncoded();

			int difference = hashedPasswordComponent.length ^ comparisonHash.length;

			for (int i = 0; i < hashedPasswordComponent.length && i < comparisonHash.length; i++)
				difference |= hashedPasswordComponent[i] ^ comparisonHash[i];

			return difference == 0;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException(e);
		}
	}

	@Nonnull
	protected String base64Encode(@Nonnull byte[] bytes) {
		requireNonNull(bytes);
		return Base64.getEncoder().withoutPadding().encodeToString(bytes);
	}

	@Nonnull
	protected static byte[] base64Decode(@Nonnull String string) {
		requireNonNull(string);
		return Base64.getDecoder().decode(string);
	}

	@Nullable
	public String getRngAlgorithm() {
		return this.rngAlgorithm;
	}

	@Nullable
	public String getHashAlgorithm() {
		return this.hashAlgorithm;
	}

	@Nullable
	public Integer getIterations() {
		return this.iterations;
	}

	@Nonnull
	public Integer getSaltLength() {
		return this.saltLength;
	}

	@Nonnull
	public Integer getKeyLength() {
		return this.keyLength;
	}
}
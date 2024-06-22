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

package com.soklet.example.model.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.annotation.Nonnull;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record AccountJwt(
		@Nonnull UUID accountId,
		@Nonnull Instant expiration
) {
	@Nonnull
	private static final Gson GSON;

	static {
		GSON = new GsonBuilder().disableHtmlEscaping().create();
	}

	public AccountJwt {
		requireNonNull(accountId);
		requireNonNull(expiration);
	}

	@Nonnull
	public Boolean isExpired() {
		return expiration().isBefore(Instant.now());
	}

	@Nonnull
	public String toStringRepresentation(@Nonnull PrivateKey privateKey) {
		requireNonNull(privateKey);
		return AccountJwt.toStringRepresentation(accountId(), expiration(), privateKey);
	}

	// Parsing an AccountJwt can have many outcomes.
	public sealed interface AccountJwtResult {
		record Succeeded(@Nonnull AccountJwt accountJwt) implements AccountJwtResult {}

		record InvalidStructure() implements AccountJwtResult {}

		record SignatureMismatch() implements AccountJwtResult {}

		record Expired(@Nonnull AccountJwt accountJwt, @Nonnull Instant expiredAt) implements AccountJwtResult {}

		record MissingClaims(@Nonnull Set<String> claims) implements AccountJwtResult {}

		record InvalidClaims(@Nonnull Set<String> claims) implements AccountJwtResult {}
	}

	@Nonnull
	public static AccountJwtResult fromStringRepresentation(@Nonnull String string,
																													@Nonnull PrivateKey privateKey) {
		requireNonNull(string);
		requireNonNull(privateKey);

		String[] components = string.trim().split("\\.");

		if (components.length != 3)
			return new AccountJwtResult.InvalidStructure();

		String encodedHeader = components[0];
		String encodedPayload = components[1];
		String encodedSignature = components[2];

		String decodedPayload = new String(base64Decode(encodedPayload), StandardCharsets.UTF_8);
		byte[] decodedSignature = base64Decode(encodedSignature);
		byte[] expectedSignature = hmacSha256(format("%s.%s", encodedHeader, encodedPayload), privateKey);

		if (!Arrays.equals(expectedSignature, decodedSignature))
			return new AccountJwtResult.SignatureMismatch();

		Map<String, Object> decodedPayloadAsMap = GSON.fromJson(decodedPayload, Map.class);
		String subAsString = (String) decodedPayloadAsMap.get("sub");
		Number iatAsNumber = (Number) decodedPayloadAsMap.get("iat");

		Set<String> missingClaims = new HashSet<>();

		if (subAsString == null)
			missingClaims.add("sub");
		if (iatAsNumber == null)
			missingClaims.add("iat");

		// Validation
		if (missingClaims.size() > 0)
			return new AccountJwtResult.MissingClaims(missingClaims);

		try {
			UUID.fromString(subAsString);
		} catch (IllegalArgumentException ignored) {
			return new AccountJwtResult.InvalidClaims(Set.of("sub"));
		}

		UUID sub = UUID.fromString(subAsString);
		Instant iat = Instant.ofEpochMilli(iatAsNumber.longValue());

		AccountJwt accountJwt = new AccountJwt(sub, iat);

		if (iat.isBefore(Instant.now()))
			return new AccountJwtResult.Expired(accountJwt, iat);

		return new AccountJwtResult.Succeeded(accountJwt);
	}

	@Nonnull
	public static String toStringRepresentation(@Nonnull UUID accountId,
																							@Nonnull Instant expiration,
																							@Nonnull PrivateKey privateKey) {
		requireNonNull(accountId);
		requireNonNull(expiration);
		requireNonNull(privateKey);

		String header = GSON.toJson(Map.of(
				"alg", "HS256",
				"typ", "JWT"
		));

		String payload = GSON.toJson(Map.of(
				"sub", accountId,
				"iat", expiration.toEpochMilli()
		));

		String encodedHeader = base64Encode(header.getBytes(StandardCharsets.UTF_8));
		String encodedPayload = base64Encode(payload.getBytes(StandardCharsets.UTF_8));

		byte[] signature = hmacSha256(format("%s.%s", encodedHeader, encodedPayload), privateKey);
		String encodedSignature = base64Encode(signature);

		return format("%s.%s.%s", encodedHeader, encodedPayload, encodedSignature);
	}

	@Nonnull
	private static byte[] hmacSha256(@Nonnull String string,
																	 @Nonnull PrivateKey privateKey) {
		requireNonNull(string);
		requireNonNull(privateKey);

		try {
			Mac hmacSha256 = Mac.getInstance("HmacSHA256");
			SecretKeySpec secretKeySpec = new SecretKeySpec(privateKey.getEncoded(), privateKey.getAlgorithm());
			hmacSha256.init(secretKeySpec);
			return hmacSha256.doFinal(string.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Nonnull
	private static String base64Encode(@Nonnull byte[] bytes) {
		requireNonNull(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Nonnull
	private static byte[] base64Decode(@Nonnull String string) {
		requireNonNull(string);
		return Base64.getUrlDecoder().decode(string);
	}
}
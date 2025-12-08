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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates a JWT which securely carries information about an authenticated account.
 * <p>
 * Note: real systems that issue different kinds of JWTs should decouple generic JWT behavior from account claims.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record AccountJwt(
		@Nonnull UUID accountId,
		@Nonnull Instant issuedAt,
		@Nonnull Instant expiresAt
) {
	@Nonnull
	private static final Gson GSON;

	static {
		GSON = new GsonBuilder().disableHtmlEscaping().create();
	}

	public AccountJwt {
		requireNonNull(accountId);
		requireNonNull(issuedAt);
		requireNonNull(expiresAt);
	}

	@Nonnull
	public Boolean isExpired() {
		return expiresAt().isBefore(Instant.now());
	}


	/**
	 * Encodes this JWT to a string representation and signs it using Ed25519.
	 */
	@Nonnull
	public String toStringRepresentation(@Nonnull PrivateKey privateKey) {
		requireNonNull(privateKey);
		return AccountJwt.toStringRepresentation(accountId(), issuedAt(), expiresAt(), privateKey);
	}

	// Parsing an AccountJwt can have many outcomes.
	public sealed interface AccountJwtResult {
		record Succeeded(@Nonnull AccountJwt accountJwt) implements AccountJwtResult {}

		record InvalidStructure() implements AccountJwtResult {}

		record SignatureMismatch() implements AccountJwtResult {}

		record Expired(@Nonnull AccountJwt accountJwt, @Nonnull Instant expiredAt) implements AccountJwtResult {}

		record MissingHeaders(@Nonnull Set<String> headers) implements AccountJwtResult {}

		record InvalidHeaders(@Nonnull Set<String> headers) implements AccountJwtResult {}

		record MissingClaims(@Nonnull Set<String> claims) implements AccountJwtResult {}

		record InvalidClaims(@Nonnull Set<String> claims) implements AccountJwtResult {}
	}

	/**
	 * Parses and verifies an AccountJwt from its string representation using the given Ed25519 public key.
	 */
	@Nonnull
	public static AccountJwtResult fromStringRepresentation(@Nonnull String string,
																													@Nonnull PublicKey publicKey) {
		requireNonNull(string);
		requireNonNull(publicKey);

		String[] components = string.trim().split("\\.");

		if (components.length != 3)
			return new AccountJwtResult.InvalidStructure();

		String encodedHeader = components[0];
		String encodedPayload = components[1];
		String encodedSignature = components[2];

		String decodedHeaderJson;
		String decodedPayloadJson;
		byte[] signatureBytes;

		try {
			decodedHeaderJson = new String(base64UrlDecode(encodedHeader), StandardCharsets.UTF_8);
			decodedPayloadJson = new String(base64UrlDecode(encodedPayload), StandardCharsets.UTF_8);
			signatureBytes = base64UrlDecode(encodedSignature);
		} catch (Exception e) {
			// Bad base64, etc.
			return new AccountJwtResult.InvalidStructure();
		}

		// Verify header alg
		Map<String, Object> header;

		try {
			header = GSON.fromJson(decodedHeaderJson, Map.class);
		} catch (Exception e) {
			return new AccountJwtResult.InvalidStructure();
		}

		// Validate required headers
		Set<String> missingHeaders = new LinkedHashSet<>();
		Set<String> invalidHeaders = new LinkedHashSet<>();

		Object algAsObject = header.get("alg");

		if (algAsObject == null)
			missingHeaders.add("alg");
		else if (!(algAsObject instanceof String alg) || !"EdDSA".equals(alg))
			invalidHeaders.add("alg");

		if (!missingHeaders.isEmpty())
			return new AccountJwtResult.MissingHeaders(missingHeaders);

		if (!invalidHeaders.isEmpty())
			return new AccountJwtResult.InvalidHeaders(invalidHeaders);

		// Verify signature
		String signingInput = format("%s.%s", encodedHeader, encodedPayload);
		boolean signatureValid;

		try {
			signatureValid = verifyEd25519(signingInput, signatureBytes, publicKey);
		} catch (Exception e) {
			return new AccountJwtResult.InvalidStructure();
		}

		if (!signatureValid)
			return new AccountJwtResult.SignatureMismatch();

		// Parse payload claims
		Map<String, Object> payload;

		try {
			payload = GSON.fromJson(decodedPayloadJson, Map.class);
		} catch (Exception e) {
			return new AccountJwtResult.InvalidStructure();
		}

		String subAsString = (String) payload.get("sub");
		Number iatAsNumber = (Number) payload.get("iat");
		Number expAsNumber = (Number) payload.get("exp");

		Set<String> missingClaims = new LinkedHashSet<>();

		if (subAsString == null)
			missingClaims.add("sub");

		if (iatAsNumber == null)
			missingClaims.add("iat");

		if (expAsNumber == null)
			missingClaims.add("exp");

		if (!missingClaims.isEmpty())
			return new AccountJwtResult.MissingClaims(missingClaims);

		UUID sub;

		try {
			sub = UUID.fromString(subAsString);
		} catch (Exception ignored) {
			return new AccountJwtResult.InvalidClaims(Set.of("sub"));
		}

		Instant issuedAt = Instant.ofEpochSecond(iatAsNumber.longValue());
		Instant expiresAt = Instant.ofEpochSecond(expAsNumber.longValue());

		AccountJwt accountJwt = new AccountJwt(sub, issuedAt, expiresAt);

		if (expiresAt.isBefore(Instant.now()))
			return new AccountJwtResult.Expired(accountJwt, expiresAt);

		return new AccountJwtResult.Succeeded(accountJwt);
	}

	@Nonnull
	public static String toStringRepresentation(@Nonnull UUID accountId,
																							@Nonnull Instant issuedAt,
																							@Nonnull Instant expiresAt,
																							@Nonnull PrivateKey privateKey) {
		requireNonNull(accountId);
		requireNonNull(issuedAt);
		requireNonNull(expiresAt);
		requireNonNull(privateKey);

		String headerJson = GSON.toJson(Map.of(
				"alg", "EdDSA",
				"typ", "JWT"
		));

		String payloadJson = GSON.toJson(Map.of(
				"sub", accountId,
				"iat", issuedAt.getEpochSecond(),
				"exp", expiresAt.getEpochSecond()
		));

		String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
		String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

		String signingInput = format("%s.%s", encodedHeader, encodedPayload);

		byte[] signatureBytes;

		try {
			signatureBytes = signEd25519(signingInput, privateKey);
		} catch (Exception e) {
			throw new IllegalArgumentException("Unable to compute Ed25519 signature", e);
		}

		String encodedSignature = base64UrlEncode(signatureBytes);

		return format("%s.%s.%s", encodedHeader, encodedPayload, encodedSignature);
	}

	@Nonnull
	private static byte[] signEd25519(@Nonnull String signingInput,
																		@Nonnull PrivateKey privateKey) throws GeneralSecurityException {
		requireNonNull(signingInput);
		requireNonNull(privateKey);

		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(privateKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

		return signature.sign();
	}

	@Nonnull
	private static Boolean verifyEd25519(@Nonnull String signingInput,
																			 @Nonnull byte[] signatureBytes,
																			 @Nonnull PublicKey publicKey) throws GeneralSecurityException {
		requireNonNull(signingInput);
		requireNonNull(signatureBytes);
		requireNonNull(publicKey);

		Signature signature = Signature.getInstance("Ed25519");
		signature.initVerify(publicKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

		return signature.verify(signatureBytes);
	}

	@Nonnull
	private static String base64UrlEncode(@Nonnull byte[] bytes) {
		requireNonNull(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@Nonnull
	private static byte[] base64UrlDecode(@Nonnull String string) {
		requireNonNull(string);
		return Base64.getUrlDecoder().decode(string);
	}
}
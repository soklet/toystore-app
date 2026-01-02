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

package com.soklet.toystore.model.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.soklet.toystore.util.Normalizer.trimAggressivelyToNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates a JWT which securely carries information about an authenticated account.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public record AccessToken(
		@NonNull UUID accountId,
		@NonNull Instant issuedAt,
		@NonNull Instant expiresAt,
		@NonNull Audience audience,
		@NonNull Set<@NonNull Scope> scopes
) {
	// Manage our own internal GSON instance because our needs are simple - no need to inject one
	@NonNull
	private static final Gson GSON;

	static {
		GSON = new GsonBuilder().disableHtmlEscaping().create();
	}

	public AccessToken {
		requireNonNull(accountId);
		requireNonNull(issuedAt);
		requireNonNull(expiresAt);
		requireNonNull(audience);
		requireNonNull(scopes);
	}

	public enum Audience {
		API("api"),
		SSE("sse");

		@NonNull
		private final String wireValue;

		Audience(@NonNull String wireValue) {
			requireNonNull(wireValue);
			this.wireValue = wireValue;
		}

		@NonNull
		public String getWireValue() {
			return this.wireValue;
		}

		@NonNull
		public static Optional<Audience> fromWireValue(@NonNull String value) {
			for (Audience audience : values())
				if (audience.getWireValue().equals(value))
					return Optional.of(audience);

			return Optional.empty();
		}
	}

	public enum Scope {
		API_READ("api:read"),
		API_WRITE("api:write"),
		SSE_HANDSHAKE("sse:handshake");

		@NonNull
		private final String wireValue;

		Scope(@NonNull String wireValue) {
			requireNonNull(wireValue);
			this.wireValue = wireValue;
		}

		@NonNull
		public String getWireValue() {
			return this.wireValue;
		}

		@NonNull
		public static Optional<Scope> fromWireValue(@NonNull String value) {
			for (Scope scope : values())
				if (scope.getWireValue().equals(value))
					return Optional.of(scope);

			return Optional.empty();
		}
	}

	// Parsing an AccessToken can have many outcomes.
	public sealed interface AccessTokenResult {
		record Succeeded(@NonNull AccessToken accessToken) implements AccessTokenResult {}

		record InvalidStructure() implements AccessTokenResult {}

		record SignatureMismatch() implements AccessTokenResult {}

		record Expired(@NonNull AccessToken accessToken, @NonNull Instant expiredAt) implements AccessTokenResult {}

		record MissingHeaders(@NonNull Set<@NonNull String> headers) implements AccessTokenResult {}

		record InvalidHeaders(@NonNull Set<@NonNull String> headers) implements AccessTokenResult {}

		record MissingClaims(@NonNull Set<@NonNull String> claims) implements AccessTokenResult {}

		record InvalidClaims(@NonNull Set<@NonNull String> claims) implements AccessTokenResult {}
	}

	@NonNull
	public Boolean isExpired() {
		return expiresAt().isBefore(Instant.now());
	}

	/**
	 * Encodes this JWT to a string representation and signs it using Ed25519.
	 */
	@NonNull
	public String toStringRepresentation(@NonNull PrivateKey privateKey) {
		requireNonNull(privateKey);
		return AccessToken.toStringRepresentation(accountId(), issuedAt(), expiresAt(), audience(), scopes(), privateKey);
	}

	/**
	 * Parses and verifies an AccessToken from its string representation using the given Ed25519 public key.
	 */
	@NonNull
	@SuppressWarnings("unchecked")
	public static AccessTokenResult fromStringRepresentation(@NonNull String string,
																													 @NonNull PublicKey publicKey) {
		requireNonNull(string);
		requireNonNull(publicKey);

		String trimmed = trimAggressivelyToNull(string);

		if (trimmed == null)
			return new AccessTokenResult.InvalidStructure();

		String[] components = trimmed.split("\\.");

		if (components.length != 3)
			return new AccessTokenResult.InvalidStructure();

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
			return new AccessTokenResult.InvalidStructure();
		}

		// Verify header alg
		Map<String, Object> header;

		try {
			header = GSON.fromJson(decodedHeaderJson, Map.class);
		} catch (Exception e) {
			return new AccessTokenResult.InvalidStructure();
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
			return new AccessTokenResult.MissingHeaders(missingHeaders);

		if (!invalidHeaders.isEmpty())
			return new AccessTokenResult.InvalidHeaders(invalidHeaders);

		// Verify signature
		String signingInput = format("%s.%s", encodedHeader, encodedPayload);
		boolean signatureValid;

		try {
			signatureValid = verifyEd25519(signingInput, signatureBytes, publicKey);
		} catch (Exception e) {
			return new AccessTokenResult.InvalidStructure();
		}

		if (!signatureValid)
			return new AccessTokenResult.SignatureMismatch();

		// Parse payload claims
		Map<String, Object> payload;

		try {
			payload = GSON.fromJson(decodedPayloadJson, Map.class);
		} catch (Exception e) {
			return new AccessTokenResult.InvalidStructure();
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
			return new AccessTokenResult.MissingClaims(missingClaims);

		UUID sub;

		try {
			sub = UUID.fromString(subAsString);
		} catch (Exception ignored) {
			return new AccessTokenResult.InvalidClaims(Set.of("sub"));
		}

		Instant issuedAt = Instant.ofEpochSecond(iatAsNumber.longValue());
		Instant expiresAt = Instant.ofEpochSecond(expAsNumber.longValue());

		String audAsString = (String) payload.get("aud");
		Object scopeAsObject = payload.get("scope"); // usually String

		if (audAsString == null)
			missingClaims.add("aud");
		if (scopeAsObject == null)
			missingClaims.add("scope");

		if (!missingClaims.isEmpty())
			return new AccessTokenResult.MissingClaims(missingClaims);

		Audience audience;

		try {
			audience = Audience.fromWireValue(audAsString).orElseThrow();
		} catch (Exception e) {
			return new AccessTokenResult.InvalidClaims(Set.of("aud"));
		}

		Set<Scope> scopes = new LinkedHashSet<>();

		try {
			if (scopeAsObject instanceof String scopeString) {
				String trimmedScope = trimAggressivelyToNull(scopeString);

				if (trimmedScope != null)
					for (String part : trimmedScope.split("\\s+"))
						scopes.add(Scope.fromWireValue(part).orElseThrow());
			} else {
				return new AccessTokenResult.InvalidClaims(Set.of("scope"));
			}
		} catch (Exception e) {
			return new AccessTokenResult.InvalidClaims(Set.of("scope"));
		}

		AccessToken accessToken = new AccessToken(sub, issuedAt, expiresAt, audience, scopes);

		if (expiresAt.isBefore(Instant.now()))
			return new AccessTokenResult.Expired(accessToken, expiresAt);

		return new AccessTokenResult.Succeeded(accessToken);
	}

	@NonNull
	public static String toStringRepresentation(@NonNull UUID accountId,
																							@NonNull Instant issuedAt,
																							@NonNull Instant expiresAt,
																							@NonNull Audience audience,
																							@NonNull Set<@NonNull Scope> scopes,
																							@NonNull PrivateKey privateKey) {
		requireNonNull(accountId);
		requireNonNull(issuedAt);
		requireNonNull(expiresAt);
		requireNonNull(audience);
		requireNonNull(scopes);
		requireNonNull(privateKey);

		String headerJson = GSON.toJson(Map.of(
				"alg", "EdDSA",
				"typ", "JWT"
		));

		String payloadJson = GSON.toJson(Map.of(
				"sub", accountId.toString(),
				"iat", issuedAt.getEpochSecond(),
				"exp", expiresAt.getEpochSecond(),
				"aud", audience.getWireValue(),
				"scope", scopes.stream()
						.map(Scope::getWireValue)
						.sorted()
						.reduce((a, b) -> a + " " + b)
						.orElse("")
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

	@NonNull
	private static byte[] signEd25519(@NonNull String signingInput,
																		@NonNull PrivateKey privateKey) throws GeneralSecurityException {
		requireNonNull(signingInput);
		requireNonNull(privateKey);

		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(privateKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

		return signature.sign();
	}

	@NonNull
	private static Boolean verifyEd25519(@NonNull String signingInput,
																			 @NonNull byte[] signatureBytes,
																			 @NonNull PublicKey publicKey) throws GeneralSecurityException {
		requireNonNull(signingInput);
		requireNonNull(signatureBytes);
		requireNonNull(publicKey);

		Signature signature = Signature.getInstance("Ed25519");
		signature.initVerify(publicKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));

		return signature.verify(signatureBytes);
	}

	@NonNull
	private static String base64UrlEncode(@NonNull byte[] bytes) {
		requireNonNull(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@NonNull
	private static byte[] base64UrlDecode(@NonNull String string) {
		requireNonNull(string);
		return Base64.getUrlDecoder().decode(string);
	}
}

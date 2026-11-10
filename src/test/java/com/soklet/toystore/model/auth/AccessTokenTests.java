package com.soklet.toystore.model.auth;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AccessTokenTests {
	@Test void malformedHeaderAndPayloadReturnStructuredFailures() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		for (String header : new String[] {"", "null", "[]", "1", "{}"}) {
			String token = encoded(header) + "." + encoded("{}") + ".AA";
			assertFalse(AccessToken.fromStringRepresentation(token, keyPair.getPublic()) instanceof AccessToken.AccessTokenResult.Succeeded);
		}
		for (String payload : new String[] {"null", "[]", "{\"sub\":123}", "{\"iat\":\"invalid\"}"}) {
			String signingInput = encoded("{\"alg\":\"EdDSA\"}") + "." + encoded(payload);
			Signature signature = Signature.getInstance("Ed25519");
			signature.initSign(keyPair.getPrivate());
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			String token = signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
			assertFalse(AccessToken.fromStringRepresentation(token, keyPair.getPublic()) instanceof AccessToken.AccessTokenResult.Succeeded);
		}
	}
	private static String encoded(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}

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

package com.soklet.toystore.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Opt-in host-to-container smoke for the README's running local Docker image.
 * It deliberately uses the published ports, not in-container localhost or the
 * simulator. No tokens or credentials are printed to test output.
 */
@EnabledIfEnvironmentVariable(named = "TOYSTORE_DOCKER_SMOKE", matches = "true")
class ToyStoreMcpDockerSmokeTests {

	@Test
	void publishedMcpPortSupportsAuthenticatedDiscoveryAndInvocationWithHostValidation()
			throws Exception {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			HttpResponse<String> authentication = post(client,
					"http://127.0.0.1:8080/accounts/authenticate", null, null, null,
					"{\"emailAddress\":\"admin@soklet.com\",\"password\":\"administrator-password\"}");
			Assertions.assertEquals(200, authentication.statusCode());
			String apiToken = object(authentication).get("authenticationToken").getAsString();
			HttpResponse<String> mint = post(client,
					"http://127.0.0.1:8080/accounts/mcp-access-token", apiToken, null, null, "");
			Assertions.assertEquals(200, mint.statusCode());
			String mcpToken = object(mint).get("accessToken").getAsString();
			for (String hostname : new String[]{"127.0.0.1", "localhost"}) {
				String endpoint = "http://" + hostname + ":8082/mcp";
				HttpResponse<String> discovery = post(client, endpoint, mcpToken,
						"server/discover", null, mcpBody("server/discover", ""));
				Assertions.assertEquals(200, discovery.statusCode());
				Assertions.assertTrue(object(discovery).has("result"));
				HttpResponse<String> invocation = post(client, endpoint, mcpToken,
						"tools/call", "list_toys", mcpBody("tools/call", ",\"name\":\"list_toys\",\"arguments\":{}"));
				Assertions.assertEquals(200, invocation.statusCode());
				Assertions.assertTrue(object(invocation).getAsJsonObject("result")
						.getAsJsonObject("structuredContent").has("toys"));
			}
			HttpResponse<String> unauthenticated = post(client, "http://127.0.0.1:8082/mcp",
					null, "tools/call", "list_toys", mcpBody("tools/call", ",\"name\":\"list_toys\",\"arguments\":{}"));
			Assertions.assertEquals(401, unauthenticated.statusCode());
		}
		assertAuthorityRejected("untrusted.example:8082");
		assertAuthorityRejected("localhost:8083");
		assertAuthorityRejected("localhost");
	}

	@NonNull
	private static HttpResponse<String> post(@NonNull HttpClient client, @NonNull String uri,
			@Nullable String token, @Nullable String method, @Nullable String name, @NonNull String body)
			throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null)
			builder.header("Authorization", "Bearer " + token);
		if (method != null)
			builder.header("Mcp-Method", method).header("Mcp-Protocol-Version", "2026-07-28");
		if (name != null)
			builder.header("Mcp-Name", name);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@NonNull
	private static JsonObject object(@NonNull HttpResponse<String> response) {
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	@NonNull
	private static String mcpBody(@NonNull String method, @NonNull String fields) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"docker-smoke\",\"method\":\"" + method
				+ "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" + fields + "}}";
	}

	private static void assertAuthorityRejected(@NonNull String authority) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", 8082), 5_000);
			socket.setSoTimeout(5_000);
			socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: " + authority
					+ "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			String status = new BufferedReader(new InputStreamReader(socket.getInputStream(),
					StandardCharsets.US_ASCII)).readLine();
			Assertions.assertEquals("HTTP/1.1 421 Misdirected Request", status);
		}
	}
}

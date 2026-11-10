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

package com.soklet.toystore.resource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.MarshaledResponseBody;
import com.soklet.Request;
import com.soklet.SokletSimulator;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.service.AccountService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IndexResourceTests {
	@Test
	public void indexPageRestrictsScriptExecutionAndFraming() throws IOException {
		MarshaledResponse response = new IndexResource().indexPage();
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertEquals(List.of(
				"script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"),
				response.getHeaders().get("Content-Security-Policy"));
	}

	@Test
	public void metricsRequireAdministrator() {
		App app = new App(new Configuration("local"));
		String customerToken = apiToken(app, "customer@soklet.com", "customer-password");
		String adminToken = apiToken(app, "admin@soklet.com", "administrator-password");
		Logger lifecycleLogger = (Logger) LoggerFactory.getLogger("com.soklet.toystore.LifecycleObserver");
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		lifecycleLogger.addAppender(events);

		try {
			SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
				Assertions.assertEquals(401, simulator.performHttpRequest(
						Request.withPath(HttpMethod.GET, "/metrics")
								.queryParameters(Map.of("sse-access-token", List.of("secret-sentinel"))).build())
						.getMarshaledResponse().getStatusCode());
				Assertions.assertEquals(401, simulator.performHttpRequest(
						Request.withPath(HttpMethod.GET, "/metrics")
								.headers(Map.of("Authorization", List.of("Bearer invalid-token"))).build())
						.getMarshaledResponse().getStatusCode());
				Assertions.assertEquals(403, simulator.performHttpRequest(
						Request.withPath(HttpMethod.GET, "/metrics")
								.headers(Map.of("Authorization", List.of("Bearer " + customerToken))).build())
						.getMarshaledResponse().getStatusCode());
				int authorizedStatus = simulator.performHttpRequest(
						Request.withPath(HttpMethod.GET, "/metrics")
								.headers(Map.of("Authorization", List.of("Bearer " + adminToken))).build())
						.getMarshaledResponse().getStatusCode();
				Assertions.assertTrue(authorizedStatus == 200 || authorizedStatus == 204);
			});
		} finally {
			lifecycleLogger.detachAppender(events);
			events.stop();
		}
		Assertions.assertTrue(events.list.stream().anyMatch(event ->
				event.getFormattedMessage().equals("Request authorization denied (HTTP 401)")
					&& event.getThrowableProxy() == null));
		Assertions.assertFalse(events.list.stream().anyMatch(event ->
				event.getThrowableProxy() != null
					&& (event.getThrowableProxy().getClassName().contains("AuthenticationException")
					|| event.getThrowableProxy().getClassName().contains("AuthorizationException"))));
		Assertions.assertTrue(events.list.stream().noneMatch(event ->
				event.getFormattedMessage().contains("secret-sentinel")));
	}

	private String apiToken(App app, String emailAddress, String password) {
		AccountService accountService = app.getInjector().getInstance(AccountService.class);
		AtomicReference<AccessToken> token = new AtomicReference<>();
		CurrentContext.with(Locale.US, ZoneId.of("UTC")).build().run(() ->
				token.set(accountService.authenticateAccount(
						new AccountAuthenticateRequest(emailAddress, password))));
		return token.get().toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());
	}

	@Test
	public void staticFileUsesStaticFilesHelper() throws IOException {
		IndexResource indexResource = new IndexResource();

		MarshaledResponse cssResponse = indexResource.staticFile(Request.fromPath(HttpMethod.GET, "/static/css/demo.css"), "css/demo.css");
		Assertions.assertEquals(200, cssResponse.getStatusCode());
		Assertions.assertEquals(List.of("text/css; charset=UTF-8"), cssResponse.getHeaders().get("Content-Type"));
		Assertions.assertEquals(List.of("bytes"), cssResponse.getHeaders().get("Accept-Ranges"));
		Assertions.assertTrue(cssResponse.getHeaders().containsKey("ETag"));
		Assertions.assertTrue(cssResponse.getHeaders().containsKey("Last-Modified"));
		MarshaledResponseBody.SecureFile body = Assertions.assertInstanceOf(
				MarshaledResponseBody.SecureFile.class, cssResponse.getBody().orElseThrow());
		byte[] expected = Files.readAllBytes(Path.of("web/static/css/demo.css"));
		Assertions.assertEquals(0L, body.getOffset());
		Assertions.assertEquals((long) expected.length, body.getCount());
		try (SeekableByteChannel channel = body.openChannel()) {
			channel.position(body.getOffset());
			ByteBuffer actual = ByteBuffer.allocate(Math.toIntExact(body.getCount()));
			while (actual.hasRemaining())
				Assertions.assertTrue(channel.read(actual) > 0, "The approved static body must be complete.");
			Assertions.assertArrayEquals(expected, actual.array());
		}

		MarshaledResponse missingResponse = indexResource.staticFile(Request.fromPath(HttpMethod.GET, "/static/missing.css"), "missing.css");
		Assertions.assertEquals(404, missingResponse.getStatusCode());
	}
}

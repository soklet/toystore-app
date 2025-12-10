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

package com.soklet.toystore.resource;

import com.google.gson.Gson;
import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.resource.AccountResource.AccountAuthenticateReponseHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountResourceTests {
	@Test
	public void testAuthenticate() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfig config = app.getInjector().getInstance(SokletConfig.class);

		Soklet.runSimulator(config, (simulator -> {
			// Correct email/password
			String requestBodyJson = gson.toJson(new AccountAuthenticateRequest("admin@soklet.com", "test123"));

			Request request = Request.withPath(HttpMethod.POST, "/accounts/authenticate")
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			AccountAuthenticateReponseHolder response = gson.fromJson(responseBody, AccountAuthenticateReponseHolder.class);

			Assertions.assertEquals(200, marshaledResponse.getStatusCode().intValue(), "Bad status code");
			Assertions.assertEquals("admin@soklet.com", response.account().getEmailAddress().get(), "Email doesn't match");

			// Incorrect email/password
			requestBodyJson = gson.toJson(new AccountAuthenticateRequest("fake@soklet.com", "fake"));

			request = Request.withPath(HttpMethod.POST, "/accounts/authenticate")
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request).getMarshaledResponse();

			Assertions.assertEquals(401, marshaledResponse.getStatusCode().intValue(), "Bad status code");
		}));
	}
}
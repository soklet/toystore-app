/*
 * Copyright 2022-2023 Revetware LLC.
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

package com.soklet.example.resource;

import com.google.gson.Gson;
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.core.HttpMethod;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.example.App;
import com.soklet.example.Configuration;
import com.soklet.example.model.api.request.AccountAuthenticateRequest;
import com.soklet.example.resource.AccountResource.AccountAuthenticateReponseHolder;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AccountResourceTests {
	@Test
	public void testAuthenticate() {
		App app = new App(new Configuration());
		Gson gson = app.getInjector().getInstance(Gson.class);
		SokletConfiguration config = app.getInjector().getInstance(SokletConfiguration.class);

		Soklet.runSimulator(config, (simulator -> {
			// Correct email/password
			String requestBodyJson = gson.toJson(new AccountAuthenticateRequest("admin@soklet.com", "test123"));

			Request request = Request.with(HttpMethod.POST, "/accounts/authenticate")
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			MarshaledResponse marshaledResponse = simulator.performRequest(request);

			String responseBody = new String(marshaledResponse.getBody().get(), StandardCharsets.UTF_8);
			AccountAuthenticateReponseHolder response = gson.fromJson(responseBody, AccountAuthenticateReponseHolder.class);

			Assert.assertEquals("Bad status code", 200, marshaledResponse.getStatusCode().intValue());
			Assert.assertEquals("Email doesn't match", "admin@soklet.com", response.account().getEmailAddress().get());

			// Incorrect email/password
			requestBodyJson = gson.toJson(new AccountAuthenticateRequest("fake@soklet.com", "fake"));

			request = Request.with(HttpMethod.POST, "/accounts/authenticate")
					.body(requestBodyJson.getBytes(StandardCharsets.UTF_8))
					.build();

			marshaledResponse = simulator.performRequest(request);

			Assert.assertEquals("Bad status code", 401, marshaledResponse.getStatusCode().intValue());
		}));
	}
}
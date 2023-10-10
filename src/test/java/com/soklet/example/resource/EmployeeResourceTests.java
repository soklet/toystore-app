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

import com.soklet.annotation.Resource;
import com.soklet.example.App;
import com.soklet.example.Configuration;
import com.soklet.example.CurrentContext;
import com.soklet.example.model.api.request.EmployeeAuthenticateApiRequest;
import com.soklet.example.resource.EmployeeResource.EmployeeAuthenticateReponse;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Resource
@ThreadSafe
public class EmployeeResourceTests {
	@Test
	public void testAuthenticate() {
		App app = new App(new Configuration());

		CurrentContext.empty().run(() -> {
			EmployeeResource employeeResource = app.getInjector().getInstance(EmployeeResource.class);

			// Call the resource method
			EmployeeAuthenticateApiRequest request = new EmployeeAuthenticateApiRequest("admin@soklet.com", "fake-password");
			EmployeeAuthenticateReponse response = employeeResource.authenticateEmployee(request);

			Assert.assertEquals("Email doesn't match", "admin@soklet.com", response.employee().getEmailAddress().get());
		});
	}
}
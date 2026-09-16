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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Configuration checks run without the opt-in Docker runtime smoke. */
class ToyStoreDockerSmokeConfigurationTests {

	@Test
	void httpPortDefaultsAndAcceptedBoundaries() {
		Assertions.assertEquals(8080, ToyStoreMcpDockerSmokeTests.resolveHttpPort(null));
		Assertions.assertEquals(1, ToyStoreMcpDockerSmokeTests.resolveHttpPort("1"));
		Assertions.assertEquals(18080, ToyStoreMcpDockerSmokeTests.resolveHttpPort("18080"));
		Assertions.assertEquals(65535, ToyStoreMcpDockerSmokeTests.resolveHttpPort("65535"));
	}

	@Test
	void invalidHttpPortOverridesFailClosed() {
		for (String value : new String[]{"", " ", "0", "-1", "+8080", "08080", "65536",
				"99999999999999999999", "1.5", "8080\n", " 8080", "8080 ", "８０８０",
				"http://example.com:8080", "8080/accounts/authenticate"}) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> ToyStoreMcpDockerSmokeTests.resolveHttpPort(value), value);
		}
	}
}

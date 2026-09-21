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

package com.soklet.toystore;

import com.soklet.McpServer;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.SokletConfig;
import com.soklet.SokletSimulator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;

class ConfigurationTests {

	@Test
	void nativeMcpDefaultsRemainLoopback() {
		Configuration configuration = new Configuration("local", Map.of());
		Assertions.assertFalse(configuration.getRunningInDocker());
		Assertions.assertEquals("127.0.0.1", configuration.getMcpServerHost());
		Assertions.assertEquals(Set.of("localhost", "127.0.0.1"), configuration.getMcpServerAllowedHosts());
	}

	@Test
	void dockerMcpDefaultsBindAllInterfacesWithAnExplicitLocalAllowlist() {
		Configuration configuration = new Configuration("local", Map.of("TOYSTORE_RUNNING_IN_DOCKER", "true"));
		Assertions.assertTrue(configuration.getRunningInDocker());
		Assertions.assertEquals("0.0.0.0", configuration.getMcpServerHost());
		Assertions.assertEquals(Set.of("localhost", "127.0.0.1"), configuration.getMcpServerAllowedHosts());
		// Exercise the real Guice configuration, including the core's requirement
		// for an explicit allowlist when binding a non-loopback address.
		App app = new App(configuration);
		Assertions.assertTrue(app.getInjector().getInstance(SokletConfig.class).getMcpServer().isPresent());
	}

	@Test
	@Timeout(60)
	void productionAndSimulatorExplicitlyDenyMcpSubscriptions() {
		for (boolean runningInDocker : new boolean[]{false, true}) {
			App app = new App(new Configuration("local", Map.of(
					"TOYSTORE_RUNNING_IN_DOCKER", Boolean.toString(runningInDocker))));
			McpServer productionServer = app.getInjector()
					.getInstance(SokletConfig.class).getMcpServer().orElseThrow();
			Assertions.assertSame(McpSubscriptionAuthorizer.denyAllInstance(),
					productionServer.getSubscriptionAuthorizer());

			SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
				McpServer simulatorServer = simulator.getMcpServer().orElseThrow();
				Assertions.assertNotSame(productionServer, simulatorServer);
				Assertions.assertSame(productionServer.getSubscriptionAuthorizer(),
						simulatorServer.getSubscriptionAuthorizer());
				Assertions.assertEquals(0,
						simulatorServer.getDiagnostics().getActiveSubscriptions());
			});
		}
	}

	@Test
	void explicitBindAndAllowlistOverridesAreNormalizedAndImmutable() {
		Configuration configuration = new Configuration("local", Map.of(
				"TOYSTORE_RUNNING_IN_DOCKER", "true",
				"TOYSTORE_MCP_HOST", " 127.0.0.1 ",
				"TOYSTORE_MCP_ALLOWED_HOSTS", " mcp.example.test,127.0.0.1,mcp.example.test "));
		Assertions.assertEquals("127.0.0.1", configuration.getMcpServerHost());
		Assertions.assertEquals(Set.of("mcp.example.test", "127.0.0.1"), configuration.getMcpServerAllowedHosts());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> configuration.getMcpServerAllowedHosts().add("untrusted.example"));
	}

	@Test
	void blankExplicitBindOrAllowlistFailsFast() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new Configuration("local", Map.of("TOYSTORE_MCP_HOST", " \t")));
		for (String value : new String[]{"", " ", ",", "localhost,", ",localhost", "localhost, ,127.0.0.1"})
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new Configuration("local", Map.of("TOYSTORE_MCP_ALLOWED_HOSTS", value)));
	}
}

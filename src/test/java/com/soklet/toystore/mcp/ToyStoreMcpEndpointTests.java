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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pyranid.Database;
import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.McpCachePolicy;
import com.soklet.McpSimulation;
import com.soklet.McpSimulationBodyType;
import com.soklet.McpSimulationResponse;
import com.soklet.McpStreamTerminationReason;
import com.soklet.Request;
import com.soklet.Simulator;
import com.soklet.SokletSimulator;
import com.soklet.toystore.App;
import com.soklet.toystore.Configuration;
import com.soklet.toystore.CurrentContext;
import com.soklet.toystore.model.api.request.AccountAuthenticateRequest;
import com.soklet.toystore.model.api.request.ToyCreateRequest;
import com.soklet.toystore.model.api.response.ToyResponse;
import com.soklet.toystore.model.api.response.ToyResponse.ToyResponseHolder;
import com.soklet.toystore.model.api.response.ToyResponse.ToysResponseHolder;
import com.soklet.toystore.model.auth.AccessToken;
import com.soklet.toystore.model.auth.AccessToken.AccessTokenResult;
import com.soklet.toystore.model.auth.AccessToken.Audience;
import com.soklet.toystore.model.auth.AccessToken.Scope;
import com.soklet.toystore.resource.AccountResource.McpAccessTokenResponseHolder;
import com.soklet.toystore.service.AccountService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.toystore.TestMarshaledResponses.responseBodyAsString;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class ToyStoreMcpEndpointTests {
	@NonNull
	private static final Duration MCP_WAIT = Duration.ofSeconds(5);
	@NonNull
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String SKILL_URI = "skill://toystore/v1/en-US/toy-catalog-guide/SKILL.md";
	private static final String SKILL_REFERENCE_URI =
			"skill://toystore/v1/en-US/toy-catalog-guide/references/catalog-fields.md";
	private static final String APP_URI = "ui://toystore/catalog-v1";
	private static final String APP_MIME_TYPE = "text/html;profile=mcp-app";
	private static final String APPS_AND_SKILLS = """
			{"extensions":{"io.modelcontextprotocol/ui":{"mimeTypes":["text/html;profile=mcp-app"]},
			"io.modelcontextprotocol/skills":{}}}
			""";

	@Test
	public void testCatalogAppLocaleUsesAccountLanguageForOpeningAndRefresh() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com", "administrator-password");
			createToy(simulator, gson, adminToken, "Locale Rocket",
					BigDecimal.valueOf(17.25), Currency.getInstance("EUR"));
			for (String[] account : new String[][]{
					{"admin@soklet.com", "administrator-password", "en-US", "pt-BR", "Found 1 toy(s)."},
					{"employee@soklet.com", "employee-password", "de-DE", "en-US", "1 Spielzeug(e) gefunden."},
					{"customer@soklet.com", "customer-password", "pt-BR", "de-DE", "1 brinquedo(s) encontrado(s)."}}) {
				String token = mcpToken(simulator, gson, app, apiToken(app, account[0], account[1]));
				JsonObject catalog = jsonBody(appRequest(simulator, app, token,
						"tools/list", null, account[3], "", true));
				JsonObject listSchema = tool(catalog, "list_toys").getAsJsonObject("outputSchema");
				Assertions.assertEquals(listSchema,
						tool(catalog, "show_toy_catalog").getAsJsonObject("outputSchema"));
				Assertions.assertEquals(Set.of("summary", "toys", "locale"),
						listSchema.getAsJsonObject("properties").keySet());
				Assertions.assertEquals(JsonParser.parseString("{\"type\":\"string\"}"),
						listSchema.getAsJsonObject("properties").get("locale"));
				Assertions.assertTrue(listSchema.getAsJsonArray("required")
						.contains(JsonParser.parseString("\"locale\"")));

				// Matching, conflicting, and absent Accept-Language must all keep
				// business data and App UI language tied to the admitted account.
				for (String language : new String[]{account[2], account[3], null}) {
					JsonObject ordinary = successfulResult(appRequest(simulator, app, token,
							"tools/call", "list_toys", language,
							",\"name\":\"list_toys\",\"arguments\":{}", true)).getAsJsonObject("structuredContent");
					JsonObject shown = successfulResult(appRequest(simulator, app, token,
							"tools/call", "show_toy_catalog", language,
							",\"name\":\"show_toy_catalog\",\"arguments\":{}", true)).getAsJsonObject("structuredContent");
					JsonObject refreshed = successfulResult(appRequest(simulator, app, token,
							"tools/call", "list_toys", language,
							",\"name\":\"list_toys\",\"arguments\":{}", true)).getAsJsonObject("structuredContent");
					Assertions.assertEquals(account[2], stringValue(ordinary, "locale"));
					Assertions.assertEquals(account[2], stringValue(shown, "locale"));
					Assertions.assertEquals(account[4], stringValue(ordinary, "summary"));
					Assertions.assertEquals(ordinary, refreshed);
					Assertions.assertEquals(ordinary.get("toys"), shown.get("toys"));
					Assertions.assertTrue(stringValue(shown, "summary").startsWith(account[4] + "\nLocale Rocket — "));
				}
			}

			// Descriptor negotiation remains a separate framework concern.
			String germanAccountToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			McpSimulationResponse portugueseCatalog = appRequest(simulator, app, germanAccountToken,
					"tools/list", null, "pt-BR", "", true);
			Assertions.assertEquals("Obter brinquedo",
					stringValue(tool(jsonBody(portugueseCatalog), "get_toy"), "title"));
			assertHeader(portugueseCatalog, "Content-Language", "pt-BR");
		});
	}

	@Test
	public void testCatalogAppLocaleCanonicalizesAccountLanguageAndFallsBack() {
		App app = new App(new Configuration("local"));
		Database database = app.getInjector().getInstance(Database.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			for (String[] language : new String[][]{
					{"de-AT", "de-DE", "0 Spielzeug(e) gefunden."},
					{"pt-PT", "pt-BR", "0 brinquedo(s) encontrado(s)."},
					{"fr-FR", "en-US", "Found 0 toy(s)."}}) {
				UUID accountId = UUID.randomUUID();
				// Add isolated test accounts; do not alter the application's seeded
				// accounts or its globally configured language catalog.
				database.query("""
						INSERT INTO account (account_id, role_id, name, email_address, password_hash, time_zone, locale)
						SELECT :accountId, role_id, 'Catalog locale test', :emailAddress, password_hash, time_zone, :locale
						FROM account WHERE email_address = 'employee@soklet.com'
						""")
						.bind("accountId", accountId)
						.bind("emailAddress", accountId + "@example.test")
						.bind("locale", Locale.forLanguageTag(language[0]))
						.execute();
				Instant issuedAt = Instant.now();
				String token = new AccessToken(accountId, issuedAt, issuedAt.plusSeconds(300),
						Audience.MCP, Set.of(Scope.MCP_READ))
						.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());
				for (String tool : new String[]{"list_toys", "show_toy_catalog"}) {
					JsonObject result = successfulResult(appRequest(simulator, app, token,
							"tools/call", tool, "pt-BR", ",\"name\":\"" + tool + "\",\"arguments\":{}", true));
					JsonObject structured = result.getAsJsonObject("structuredContent");
					Assertions.assertEquals(language[1], stringValue(structured, "locale"));
					Assertions.assertEquals(language[2], stringValue(structured, "summary"));
					Assertions.assertEquals(language[2], stringValue(result.getAsJsonArray("content")
							.get(0).getAsJsonObject(), "text"));
				}
			}
		});
	}

	@Test
	public void testCatalogAppAssociationAndLocalizedFallbackUseTheExistingCatalog() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com", "administrator-password");
			createToy(simulator, gson, adminToken, "Catalog Rocket",
					BigDecimal.valueOf(17.25), Currency.getInstance("EUR"));
			createToy(simulator, gson, adminToken, "Wooden Train",
					BigDecimal.valueOf(21.50), Currency.getInstance("USD"));
			String token = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			String query = ",\"name\":\"%s\",\"arguments\":{\"query\":\"Catalog\"}";
			JsonObject ordinary = successfulResult(appRequest(simulator, app, token,
					"tools/call", "list_toys", "de-DE", query.formatted("list_toys"), true));
			JsonObject expectedToy = ordinary.getAsJsonObject("structuredContent")
					.getAsJsonArray("toys").get(0).getAsJsonObject();
			String expectedSummary = ordinary.getAsJsonObject("structuredContent").get("summary").getAsString()
					+ "\nCatalog Rocket — " + stringValue(expectedToy, "priceDescription") + " (EUR)";

			// Changing capabilities on the same authenticated client is per-request.
			for (boolean appsEnabled : new boolean[]{true, false, true}) {
				JsonObject catalog = jsonBody(appRequest(simulator, app, token,
						"tools/list", null, "de-DE", "", appsEnabled));
				Assertions.assertEquals(3, catalog.getAsJsonObject("result").getAsJsonArray("tools").size());
				tool(catalog, "list_toys");
				tool(catalog, "get_toy");
				JsonObject show = tool(catalog, "show_toy_catalog");
				Assertions.assertEquals("Spielzeugkatalog anzeigen", stringValue(show, "title"));
				if (appsEnabled) {
					JsonObject ui = show.getAsJsonObject("_meta").getAsJsonObject("ui");
					Assertions.assertEquals(APP_URI, stringValue(ui, "resourceUri"));
					Assertions.assertEquals(JsonParser.parseString("[\"model\",\"app\"]"), ui.get("visibility"));
				} else {
					Assertions.assertFalse(show.has("_meta"));
					Assertions.assertFalse(catalog.toString().contains(APP_URI));
				}
				JsonObject shown = successfulResult(appRequest(simulator, app, token,
						"tools/call", "show_toy_catalog", "de-DE", query.formatted("show_toy_catalog"), appsEnabled));
				Assertions.assertEquals(ordinary.getAsJsonObject("structuredContent").get("toys"),
						shown.getAsJsonObject("structuredContent").get("toys"));
				Assertions.assertEquals(expectedSummary, stringValue(shown.getAsJsonObject("structuredContent"), "summary"));
				Assertions.assertEquals(1, shown.getAsJsonArray("content").size());
				Assertions.assertEquals(expectedSummary, stringValue(shown.getAsJsonArray("content").get(0).getAsJsonObject(), "text"));
				Assertions.assertFalse(shown.toString().contains("Wooden Train"));
			}
			JsonObject empty = successfulResult(appRequest(simulator, app, token, "tools/call",
					"show_toy_catalog", "de-DE", ",\"name\":\"show_toy_catalog\",\"arguments\":{\"query\":\"NoMatch\"}", false));
			Assertions.assertEquals(0, empty.getAsJsonObject("structuredContent").getAsJsonArray("toys").size());
			Assertions.assertEquals("0 Spielzeug(e) passend zu „NoMatch“ gefunden.",
					stringValue(empty.getAsJsonArray("content").get(0).getAsJsonObject(), "text"));
		});
	}

	@Test
	public void testStaticCatalogAppResourceAndSkillsCoexistWithOrdinaryResources() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		String expectedHtml;
		try (InputStream input = ToyStoreMcpEndpointTests.class.getResourceAsStream("/mcp/apps/catalog.html")) {
			expectedHtml = new String(requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com", "administrator-password");
			ToyResponseHolder toy = createToy(simulator, gson, adminToken, "Private catalog sentinel 6721",
					BigDecimal.valueOf(31.20), Currency.getInstance("USD"));
			String toyUri = "toystore://toys/" + toy.toy().getToyId();
			String token = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			JsonObject extensions = successfulResult(appRequest(simulator, app, token,
					"server/discover", null, "en-US", "", true))
					.getAsJsonObject("capabilities").getAsJsonObject("extensions");
			Assertions.assertEquals(JsonParser.parseString("{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}"),
					extensions.get("io.modelcontextprotocol/ui"));
			Assertions.assertTrue(extensions.has("io.modelcontextprotocol/skills"));
			Assertions.assertEquals(1, successfulResult(appRequest(simulator, app, token,
					"skills/list", null, "en-US", "", true)).getAsJsonArray("skills").size());

			for (boolean appsEnabled : new boolean[]{true, false, true}) {
				JsonArray resources = successfulResult(appRequest(simulator, app, token,
						"resources/list", null, "de-DE", "", appsEnabled)).getAsJsonArray("resources");
				Assertions.assertEquals(appsEnabled ? 2 : 1, resources.size());
				Set<String> uris = new java.util.HashSet<>();
				for (var resource : resources) {
					JsonObject descriptor = resource.getAsJsonObject();
					String uri = stringValue(descriptor, "uri");
					Assertions.assertTrue(uris.add(uri));
					if (uri.equals(APP_URI))
						Assertions.assertEquals(APP_MIME_TYPE, stringValue(descriptor, "mimeType"));
				}
				Assertions.assertEquals(appsEnabled ? Set.of(toyUri, APP_URI) : Set.of(toyUri), uris);
				JsonArray templates = successfulResult(appRequest(simulator, app, token,
						"resources/templates/list", null, "de-DE", "", appsEnabled)).getAsJsonArray("resourceTemplates");
				Assertions.assertEquals(1, templates.size());
				Assertions.assertEquals("toystore://toys/{toyId}", stringValue(templates.get(0).getAsJsonObject(), "uriTemplate"));
			}

			// Capabilities guide presentation, not authorization: an admitted caller
			// can still read this exact URI without first listing or opening a tool.
			for (String[] credentials : new String[][]{
					{"admin@soklet.com", "administrator-password", "en-US"},
					{"employee@soklet.com", "employee-password", "de-DE"},
					{"customer@soklet.com", "customer-password", "pt-BR"}}) {
				String callerToken = mcpToken(simulator, gson, app, apiToken(app, credentials[0], credentials[1]));
				for (boolean appsEnabled : new boolean[]{false, true}) {
					JsonArray contents = successfulResult(appRequest(simulator, app, callerToken,
							"resources/read", APP_URI, credentials[2], ",\"uri\":\"" + APP_URI + "\"", appsEnabled))
							.getAsJsonArray("contents");
					Assertions.assertEquals(1, contents.size());
					JsonObject content = contents.get(0).getAsJsonObject();
					Assertions.assertEquals(APP_URI, stringValue(content, "uri"));
					Assertions.assertEquals(APP_MIME_TYPE, stringValue(content, "mimeType"));
					Assertions.assertFalse(content.has("blob"));
					Assertions.assertEquals(expectedHtml, stringValue(content, "text"));
					Assertions.assertFalse(expectedHtml.contains(callerToken));
					Assertions.assertFalse(expectedHtml.contains(credentials[0]));
					Assertions.assertFalse(expectedHtml.contains(toy.toy().getName()));
					Assertions.assertFalse(expectedHtml.contains(toy.toy().getToyId().toString()));
					Assertions.assertEquals(JsonParser.parseString("""
							{"csp":{"connectDomains":[],"resourceDomains":[],"frameDomains":[],"baseUriDomains":[]},
							"prefersBorder":true}
							"""), content.getAsJsonObject("_meta").getAsJsonObject("ui"));
				}
			}
		});
	}

	@Test
	public void testCatalogAppOpenReadAndRefreshRequireFreshMcpCredentials() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "employee@soklet.com", "employee-password");
			String token = mcpToken(simulator, gson, app, apiToken);
			String insufficient = tokenWithClaims(app, token, Audience.MCP, Set.of(Scope.API_READ));
			for (String[] operation : new String[][]{
					{"tools/call", "show_toy_catalog", ",\"name\":\"show_toy_catalog\",\"arguments\":{}"},
					{"tools/call", "list_toys", ",\"name\":\"list_toys\",\"arguments\":{}"},
					{"resources/read", APP_URI, ",\"uri\":\"" + APP_URI + "\""}}) {
				for (String rejected : new String[]{null, "not-a-jwt", apiToken, expiredToken(app, token)}) {
					successfulResult(appRequest(simulator, app, token,
							operation[0], operation[1], "en-US", operation[2], true));
					McpSimulationResponse response = appRequest(simulator, app, rejected,
							operation[0], operation[1], "en-US", operation[2], true);
					assertAuthenticationResponseRejected(response,
							"Opening an App, reading its HTML, and refreshing must each authenticate independently.");
					Assertions.assertFalse(jsonBody(response).has("result"));
				}
				McpSimulationResponse response = appRequest(simulator, app, insufficient,
						operation[0], operation[1], "en-US", operation[2], true);
				assertInsufficientScopeResponseRejected(response, "An Apps bridge is not a grant of mcp:read.");
				Assertions.assertFalse(jsonBody(response).has("result"));
			}
		});
	}

	@Test
	public void testCatalogSkillSelectionUsesEachFreshAuthenticatedAccount() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			Map<String, String> tokens = new LinkedHashMap<>();
			for (String[] account : new String[][]{
					{"admin@soklet.com", "administrator-password", "en-US"},
					{"employee@soklet.com", "employee-password", "de-DE"},
					{"customer@soklet.com", "customer-password", "pt-BR"}})
				tokens.put(account[2], mcpToken(simulator, gson, app,
						apiToken(app, account[0], account[1])));
			// Repeat interleaved accounts on one simulator: no preceding request's
			// selected language or principal may survive into the next request.
			for (int round = 0; round < 2; round++) {
				for (var account : tokens.entrySet()) {
					for (String language : new String[]{null, "en-US", "de-DE", "pt-BR", "fr-FR"})
						assertListedSkillLocale(simulator, app, account.getValue(), language, account.getKey());
				}
			}
		});
	}

	@Test
	public void testCatalogSkillSelectionMatchesAccountLocalesAndReloadsAccountChanges() {
		App app = new App(new Configuration("local"));
		Database database = app.getInjector().getInstance(Database.class);
		UUID accountId = UUID.randomUUID();
		database.query("""
				INSERT INTO account (account_id, role_id, name, email_address, password_hash, time_zone, locale)
				SELECT :accountId, role_id, 'Skill locale test', :emailAddress, password_hash, time_zone, :locale
				FROM account WHERE email_address = 'employee@soklet.com'
				""")
				.bind("accountId", accountId)
				.bind("emailAddress", accountId + "@example.test")
				.bind("locale", Locale.forLanguageTag("de-AT"))
				.execute();
		Instant issuedAt = Instant.now();
		String token = new AccessToken(accountId, issuedAt, issuedAt.plusSeconds(300),
				Audience.MCP, Set.of(Scope.MCP_READ))
				.toStringRepresentation(app.getConfiguration().getKeyPair().getPrivate());

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			// Reuse the exact token while changing only this isolated account.
			for (String[] language : new String[][]{
					{"de-AT", "de-DE"}, {"pt-PT", "pt-BR"}, {"fr-FR", "en-US"}, {"de-DE", "de-DE"}}) {
				database.query("UPDATE account SET locale = :locale WHERE account_id = :accountId")
						.bind("locale", Locale.forLanguageTag(language[0]))
						.bind("accountId", accountId).execute();
				assertListedSkillLocale(simulator, app, token, "pt-BR", language[1]);
				assertListedSkillLocale(simulator, app, token, null, language[1]);
			}
		});
	}

	@Test
	public void testCatalogSkillSelectionHonorsExplicitLanguageExclusionsWithoutGrantingAccess() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String token = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			for (String[] preference : new String[][]{
					{"de;q=0", "en-US"},
					{"de-DE;q=0, en;q=0, pt-BR;q=1", null},
					{"*;q=0", null},
					{"*;q=0, de;q=1", "de-DE"},
					{"de;q=0, de-DE;q=1", "de-DE"},
					{"de;q=1, de-DE;q=0", "en-US"},
					{"de;q=0, en-US;q=1, *;q=0", "en-US"},
					{"de-DE;q=0, de-DE;q=1", "en-US"},
					{"de-DE;q=1, de-DE;q=0", "de-DE"}})
				assertListedSkillLocale(simulator, app, token, preference[0], preference[1]);
			// A list preference is not an authorization rule. Even after listing
			// no variant, direct get/read of every variant remains available.
			for (String locale : new String[]{"en-US", "de-DE", "pt-BR"}) {
				String uri = skillUri(locale, "SKILL.md");
				Assertions.assertEquals(uri, stringValue(successfulResult(skillRequest(simulator,
						app, token, "skills/get", uri, "*;q=0")).getAsJsonObject("skill"), "uri"));
				Assertions.assertEquals(1, successfulResult(skillRequest(simulator, app,
						token, "resources/read", uri, "*;q=0")).getAsJsonArray("contents").size());
			}
		});
	}

	@Test
	public void testCatalogSkillDiscoveryLookupAndByteExactReads() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);
		var group = ToyStoreMcpSkills.catalogGuideGroup();
		Assertions.assertEquals("toy-catalog-guide", group.getKey());
		Assertions.assertEquals(Set.of(Locale.US, Locale.GERMANY, Locale.forLanguageTag("pt-BR")),
				group.getSkillRegistrations().stream().map(registration -> registration.getLocale().orElseThrow())
						.collect(java.util.stream.Collectors.toSet()));
		for (var registration : group.getSkillRegistrations())
			Assertions.assertEquals(McpCachePolicy.fromPrivateTimeToLive(Duration.ZERO), registration.getCachePolicy());
		Map<String, String> descriptions = Map.of(
				"en-US", "Explore and explain the Toy Store catalog using its read-only MCP tools and resources.",
				"de-DE", "Den Toy-Store-Katalog mit seinen schreibgeschützten MCP-Werkzeugen und Ressourcen durchsuchen und erklären.",
				"pt-BR", "Explore e explique o catálogo da Toy Store usando suas ferramentas e recursos MCP somente de leitura.");

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String token = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			JsonObject discovery = successfulResult(skillRequest(simulator, app,
					token, "server/discover", null, "de-DE"));
			Assertions.assertTrue(discovery.getAsJsonObject("capabilities")
					.getAsJsonObject("extensions").has("io.modelcontextprotocol/skills"));
			for (String[] account : new String[][]{
					{"admin@soklet.com", "administrator-password"},
					{"employee@soklet.com", "employee-password"},
					{"customer@soklet.com", "customer-password"}}) {
				String accountToken = mcpToken(simulator, gson, app, apiToken(app, account[0], account[1]));
				for (String locale : new String[]{"en-US", "de-DE", "pt-BR"}) {
					Map<String, byte[]> expectedFiles = Map.of(
							skillUri(locale, "SKILL.md"), skillFile(locale, "SKILL.md"),
							skillUri(locale, "references/catalog-fields.md"), skillFile(locale, "references/catalog-fields.md"));
					JsonObject skill = successfulResult(skillRequest(simulator, app,
							accountToken, "skills/get", skillUri(locale, "SKILL.md"), "*;q=0")).getAsJsonObject("skill");
					Assertions.assertEquals(skillUri(locale, "SKILL.md"), stringValue(skill, "uri"));
					JsonObject frontmatter = skill.getAsJsonObject("frontmatter");
					Assertions.assertEquals(Set.of("name", "description", "metadata"), frontmatter.keySet());
					Assertions.assertEquals("toy-catalog-guide", stringValue(frontmatter, "name"));
					Assertions.assertEquals(descriptions.get(locale), stringValue(frontmatter, "description"));
					Assertions.assertEquals(JsonParser.parseString("{\"audience\":\"catalog-readers\",\"mode\":\"read-only\"}"),
							frontmatter.getAsJsonObject("metadata"));
					JsonArray manifest = skill.getAsJsonArray("resources");
					Assertions.assertEquals(expectedFiles.size(), manifest.size());
					Set<String> observedUris = new java.util.HashSet<>();
					for (var entry : manifest) {
						JsonObject file = entry.getAsJsonObject();
						String uri = stringValue(file, "uri");
						Assertions.assertTrue(observedUris.add(uri));
						byte[] expected = requireNonNull(expectedFiles.get(uri));
						Assertions.assertEquals(expected.length, file.get("size").getAsInt());
						Assertions.assertEquals(digest(expected), stringValue(file, "digest"));
						// Every admitted account can read every variant, including when
						// all listing languages are excluded. Authored bytes never vary.
						for (String language : new String[]{null, "*;q=0"}) {
							JsonArray contents = successfulResult(skillRequest(simulator, app,
									accountToken, "resources/read", uri, language)).getAsJsonArray("contents");
							Assertions.assertEquals(1, contents.size());
							JsonObject content = contents.get(0).getAsJsonObject();
							Assertions.assertEquals(uri, stringValue(content, "uri"));
							Assertions.assertEquals("text/markdown", stringValue(content, "mimeType"));
							Assertions.assertFalse(content.has("blob"));
							Assertions.assertArrayEquals(expected,
									stringValue(content, "text").getBytes(StandardCharsets.UTF_8));
						}
					}
					Assertions.assertEquals(expectedFiles.keySet(), observedUris);
				}
			}
		});
	}

	@Test
	public void testEverySkillOperationRequiresFreshMcpCredentials() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "employee@soklet.com", "employee-password");
			String token = mcpToken(simulator, gson, app, apiToken);
			String insufficient = tokenWithClaims(app, token, Audience.MCP, Set.of(Scope.API_READ));
			for (String[] operation : new String[][]{
					{"skills/list", null}, {"skills/get", SKILL_URI},
					{"resources/read", SKILL_URI}, {"resources/read", SKILL_REFERENCE_URI},
					{"skills/get", skillUri("de-DE", "SKILL.md")},
					{"resources/read", skillUri("de-DE", "SKILL.md")},
					{"resources/read", skillUri("de-DE", "references/catalog-fields.md")},
					{"skills/get", skillUri("pt-BR", "SKILL.md")},
					{"resources/read", skillUri("pt-BR", "SKILL.md")},
					{"resources/read", skillUri("pt-BR", "references/catalog-fields.md")}}) {
				for (String rejected : new String[]{null, "not-a-jwt", apiToken, expiredToken(app, token)}) {
					successfulResult(skillRequest(simulator, app,
							token, operation[0], operation[1], "en-US"));
					McpSimulationResponse response = skillRequest(simulator, app,
							rejected, operation[0], operation[1], "en-US");
					assertAuthenticationResponseRejected(response,
							"Skills must not inherit a previous request's credentials.");
					Assertions.assertFalse(jsonBody(response).has("result"));
				}
				McpSimulationResponse response = skillRequest(simulator, app,
						insufficient, operation[0], operation[1], "en-US");
				assertInsufficientScopeResponseRejected(response,
						"Skills require the same mcp:read scope as catalog operations.");
				Assertions.assertFalse(jsonBody(response).has("result"));
			}
		});
	}

	@Test
	public void testListToysViaMcpUsesAccountLocalization() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			createToy(simulator, gson, adminToken, "Catalog Ball",
					BigDecimal.valueOf(12.34), Currency.getInstance("USD"));

			String employeeApiToken = apiToken(app, "employee@soklet.com",
					"employee-password");
			String employeeMcpToken = mcpToken(
					simulator, gson, app, employeeApiToken);
			McpSimulationResponse toolResponse = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "tools/call", "list_toys", "de-DE", """
						,"name":"list_toys","arguments":{}
						""");
			JsonObject result = jsonBody(toolResponse)
					.getAsJsonObject("result");
			JsonObject structured = result.getAsJsonObject("structuredContent");
			JsonObject firstToy = structured.getAsJsonArray("toys")
					.get(0).getAsJsonObject();

			Request request = Request.withPath(HttpMethod.GET, "/toys")
					.headers(Map.of("Authorization",
							Set.of("Bearer " + employeeApiToken)))
					.build();
			MarshaledResponse httpResponse = simulator.performHttpRequest(request)
					.getMarshaledResponse();
			ToysResponseHolder response = gson.fromJson(
					responseBodyAsString(httpResponse), ToysResponseHolder.class);
			ToyResponse httpToy = response.toys().get(0);

			Assertions.assertEquals(httpToy.getName(),
					stringValue(firstToy, "name"));
			Assertions.assertEquals(httpToy.getPriceDescription(),
					stringValue(firstToy, "priceDescription"));
			Assertions.assertEquals(httpToy.getCreatedAtDescription(),
					stringValue(firstToy, "createdAtDescription"));
			Assertions.assertEquals(httpToy.getCurrencyDescription(),
					stringValue(firstToy, "currencyDescription"));
			Assertions.assertEquals("1 Spielzeug(e) gefunden.",
					stringValue(structured, "summary"));
			Assertions.assertEquals("1 Spielzeug(e) gefunden.",
					result.getAsJsonArray("content").get(0).getAsJsonObject()
							.get("text").getAsString());
		});
	}

	@Test
	public void testReadToyResourceAndResourceListViaMcp() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String adminToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			ToyResponseHolder created = createToy(simulator, gson, adminToken,
					"Catalog Robot", BigDecimal.valueOf(88.99),
					Currency.getInstance("EUR"));
			String employeeMcpToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));

			JsonObject resourcesResult = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "resources/list", null, "de-DE", ""))
					.getAsJsonObject("result");
			JsonObject listedResource = resourcesResult
					.getAsJsonArray("resources").get(0).getAsJsonObject();
			Assertions.assertEquals(1, resourcesResult.getAsJsonArray("resources").size(),
					"Skills files must not leak into the annotated ordinary resource catalog.");
			JsonArray templates = successfulResult(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), employeeMcpToken,
					"resources/templates/list", null, "de-DE", ""))
					.getAsJsonArray("resourceTemplates");
			Assertions.assertEquals(1, templates.size());
			Assertions.assertEquals("toystore://toys/{toyId}",
					stringValue(templates.get(0).getAsJsonObject(), "uriTemplate"));
			String uri = "toystore://toys/%s".formatted(
					created.toy().getToyId());
			Assertions.assertEquals(uri, stringValue(listedResource, "uri"));
			Assertions.assertEquals(created.toy().getName(),
					stringValue(listedResource, "title"));

			JsonObject resourceResult = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					employeeMcpToken, "resources/read", uri, "de-DE",
					",\"uri\":\"%s\"".formatted(uri)))
					.getAsJsonObject("result");
			JsonObject firstContent = resourceResult.getAsJsonArray("contents")
					.get(0).getAsJsonObject();
			ToyResponseHolder resource = gson.fromJson(
					stringValue(firstContent, "text"), ToyResponseHolder.class);

			Assertions.assertEquals(created.toy().getToyId(),
					resource.toy().getToyId());
			Assertions.assertEquals(created.toy().getName(),
					resource.toy().getName());
		});
	}

	@Test
	public void testAdmissionRejectsApiAccessTokensWithoutSessionInitialization() {
		App app = new App(new Configuration("local"));

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "admin@soklet.com",
					"administrator-password");
			McpSimulationResponse response = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), apiToken,
					"tools/list", null, "en-US", "");
			JsonObject error = jsonBody(response).getAsJsonObject("error");

			Assertions.assertEquals(401, response.getStatusCode());
			Assertions.assertEquals("Sorry, we could not authenticate you.",
					stringValue(error, "message"));
			assertHeader(response, "WWW-Authenticate", "Bearer");
		});
	}

	@Test
	public void testAdmissionReevaluatesCredentialsForEveryRequest() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String apiToken = apiToken(app, "employee@soklet.com",
					"employee-password");
			String validMcpToken = mcpToken(simulator, gson, app, apiToken);
			String wrongAudienceToken = tokenWithClaims(app, validMcpToken,
					Audience.API, Set.of(Scope.MCP_READ));
			String insufficientScopeToken = tokenWithClaims(app, validMcpToken,
					Audience.MCP, Set.of(Scope.API_READ));
			String expiredToken = expiredToken(app, validMcpToken);

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, "not-a-jwt",
					"Malformed credentials were carried over from the prior request.");
			assertAuthenticationHeaderRejected(simulator, app,
					"Bearer" + validMcpToken,
					"A Bearer credential without the required separator was accepted.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, null,
					"Missing credentials were carried over from the prior request.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, expiredToken,
					"Expired credentials were carried over from the prior request.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertAuthenticationRejected(simulator, app, wrongAudienceToken,
					"A wrong-audience credential inherited the prior identity.");

			assertToolsListAccepted(simulator, app, validMcpToken);
			assertInsufficientScopeRejected(simulator, app,
					insufficientScopeToken,
					"An insufficient-scope credential inherited the prior authorization.");
		});
	}

	@Test
	@Timeout(60)
	public void testAuthenticatedSubscriptionsAreDeniedWithoutAcknowledgment() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			for (String[] credentials : new String[][]{
					{"admin@soklet.com", "administrator-password"},
					{"employee@soklet.com", "employee-password"},
					{"customer@soklet.com", "customer-password"}}) {
				String token = mcpToken(simulator, gson, app,
						apiToken(app, credentials[0], credentials[1]));
				assertToolsListAccepted(simulator, app, token);

				McpSimulationResponse denied = subscriptionRequest(simulator, app, token);
				JsonObject body = jsonBody(denied);
				Assertions.assertEquals(403, denied.getStatusCode());
				Assertions.assertFalse(body.has("result"));
				Assertions.assertEquals(-32603,
						body.getAsJsonObject("error").get("code").getAsInt());
				Assertions.assertFalse(body.toString().contains(
						"notifications/subscriptions/acknowledged"));
				Assertions.assertEquals(0, simulator.getMcpServer().orElseThrow()
						.getDiagnostics().getActiveSubscriptions());

				// Denying a live subscription does not revoke request-response access.
				assertToolsListAccepted(simulator, app, token);
				McpSimulationResponse call = mcpRequest(simulator,
						app.getConfiguration().getMcpServerPort(), token,
						"tools/call", "list_toys", "en-US",
						",\"name\":\"list_toys\",\"arguments\":{}");
				Assertions.assertEquals(200, call.getStatusCode());
				Assertions.assertTrue(jsonBody(call).getAsJsonObject("result")
						.getAsJsonObject("structuredContent").has("toys"));
			}
		});
	}

	@Test
	@Timeout(60)
	public void testSubscriptionRequestsStillRequireFreshMcpCredentials() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String validToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			String wrongAudienceToken = tokenWithClaims(app, validToken,
					Audience.API, Set.of(Scope.MCP_READ));
			String insufficientScopeToken = tokenWithClaims(app, validToken,
					Audience.MCP, Set.of(Scope.API_READ));

			for (String invalidToken : new String[]{null, "not-a-jwt",
					wrongAudienceToken, expiredToken(app, validToken)}) {
				assertToolsListAccepted(simulator, app, validToken);
				assertAuthenticationResponseRejected(
						subscriptionRequest(simulator, app, invalidToken),
						"Subscription admission reused a prior request's identity.");
			}

			assertToolsListAccepted(simulator, app, validToken);
			assertInsufficientScopeResponseRejected(
					subscriptionRequest(simulator, app, insufficientScopeToken),
					"Subscription admission accepted a token without mcp:read.");
			Assertions.assertEquals(0, simulator.getMcpServer().orElseThrow()
					.getDiagnostics().getActiveSubscriptions());
			assertToolsListAccepted(simulator, app, validToken);
		});
	}

	@Test
	public void testFrameworkCatalogLocalizationForGermanAndPortuguese() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String employeeToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			McpSimulationResponse german = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), employeeToken,
					"tools/list", null, "de-DE", "");
			JsonObject germanTool = tool(jsonBody(german), "list_toys");
			Assertions.assertEquals("Spielzeuge auflisten",
					stringValue(germanTool, "title"));
			Assertions.assertEquals(
					"Listet Spielzeuge im Katalog auf. Optional kann nach einem Namenspräfix gefiltert werden.",
					stringValue(germanTool, "description"));
			assertHeader(german, "Content-Language", "de-DE");
			assertVaryAcceptLanguage(german);

			String customerToken = mcpToken(simulator, gson, app,
					apiToken(app, "customer@soklet.com", "customer-password"));
			McpSimulationResponse portuguese = mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(),
					customerToken, "tools/list", null, "pt-BR", "");
			JsonObject portugueseTool = tool(jsonBody(portuguese), "get_toy");
			Assertions.assertEquals("Obter brinquedo",
					stringValue(portugueseTool, "title"));
			Assertions.assertEquals(
					"Obtém um brinquedo pelo identificador.",
					stringValue(portugueseTool, "description"));
			assertHeader(portuguese, "Content-Language", "pt-BR");
			assertVaryAcceptLanguage(portuguese);
		});
	}

	@Test
	public void testMissingToyReturnsLocalizedApplicationToolError() {
		App app = new App(new Configuration("local"));
		Gson gson = app.getInjector().getInstance(Gson.class);

		SokletSimulator.run(app.createSimulatorConfig(), simulator -> {
			String employeeToken = mcpToken(simulator, gson, app,
					apiToken(app, "employee@soklet.com", "employee-password"));
			String missingToyId = UUID.randomUUID().toString();
			JsonObject result = jsonBody(mcpRequest(simulator,
					app.getConfiguration().getMcpServerPort(), employeeToken,
					"tools/call", "get_toy", "de-DE",
					",\"name\":\"get_toy\",\"arguments\":{\"toyId\":\"%s\"}"
							.formatted(missingToyId)))
					.getAsJsonObject("result");

			Assertions.assertTrue(result.get("isError").getAsBoolean());
			Assertions.assertEquals("Spielzeug nicht gefunden.",
					result.getAsJsonArray("content").get(0).getAsJsonObject()
							.get("text").getAsString());
		});
	}

	@NonNull
	private String apiToken(@NonNull App app, @NonNull String emailAddress,
			@NonNull String password) {
		AccountService accountService =
				app.getInjector().getInstance(AccountService.class);
		AtomicReference<AccessToken> holder = new AtomicReference<>();
		CurrentContext.with(Locale.US, ZoneId.of("America/New_York"))
				.build()
				.run(() -> holder.set(accountService.authenticateAccount(
						new AccountAuthenticateRequest(emailAddress, password))));
		return holder.get().toStringRepresentation(
				app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String mcpToken(@NonNull Simulator simulator, @NonNull Gson gson,
			@NonNull App app, @NonNull String apiAccessToken) {
		Request request = Request.withPath(
					HttpMethod.POST, "/accounts/mcp-access-token")
				.headers(Map.of("Authorization",
						Set.of("Bearer " + apiAccessToken)))
				.build();
		MarshaledResponse response = simulator.performHttpRequest(request)
				.getMarshaledResponse();
		Assertions.assertEquals(200, response.getStatusCode());

		McpAccessTokenResponseHolder holder = gson.fromJson(
				responseBodyAsString(response), McpAccessTokenResponseHolder.class);
		Assertions.assertEquals(Audience.MCP, holder.accessToken().audience());
		Assertions.assertTrue(holder.accessToken().scopes()
				.contains(Scope.MCP_READ));
		return holder.accessToken().toStringRepresentation(
				app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String tokenWithClaims(@NonNull App app,
			@NonNull String sourceToken, @NonNull Audience audience,
			@NonNull Set<@NonNull Scope> scopes) {
		AccessToken source = verifiedToken(app, sourceToken);
		return new AccessToken(source.accountId(), source.issuedAt(),
				source.expiresAt(), audience, scopes)
				.toStringRepresentation(
						app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private String expiredToken(@NonNull App app,
			@NonNull String sourceToken) {
		AccessToken source = verifiedToken(app, sourceToken);
		return new AccessToken(source.accountId(), Instant.EPOCH,
				Instant.EPOCH.plusSeconds(1), Audience.MCP,
				Set.of(Scope.MCP_READ))
				.toStringRepresentation(
						app.getConfiguration().getKeyPair().getPrivate());
	}

	@NonNull
	private AccessToken verifiedToken(@NonNull App app,
			@NonNull String sourceToken) {
		AccessTokenResult result = AccessToken.fromStringRepresentation(
				sourceToken, app.getConfiguration().getKeyPair().getPublic());
		if (result instanceof AccessTokenResult.Succeeded succeeded)
			return succeeded.accessToken();
		throw new AssertionError("Source access token could not be parsed: "
				+ result);
	}

	private void assertToolsListAccepted(@NonNull Simulator simulator,
			@NonNull App app, @NonNull String accessToken) {
		McpSimulationResponse response = mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", "");
		Assertions.assertEquals(200, response.getStatusCode());
		Assertions.assertTrue(jsonBody(response).has("result"));
	}

	private void assertAuthenticationRejected(@NonNull Simulator simulator,
			@NonNull App app, @Nullable String accessToken,
			@NonNull String failureMessage) {
		assertAuthenticationResponseRejected(mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", ""), failureMessage);
	}

	private void assertAuthenticationHeaderRejected(
			@NonNull Simulator simulator, @NonNull App app,
			@NonNull String authorizationHeader,
			@NonNull String failureMessage) {
		assertAuthenticationResponseRejected(mcpRequestWithAuthorization(
				simulator, app.getConfiguration().getMcpServerPort(),
				authorizationHeader, "tools/list", null, "en-US", ""),
				failureMessage);
	}

	private void assertAuthenticationResponseRejected(
			@NonNull McpSimulationResponse response,
			@NonNull String failureMessage) {
		JsonObject error = jsonBody(response).getAsJsonObject("error");

		Assertions.assertEquals(401, response.getStatusCode(), failureMessage);
		Assertions.assertEquals(-31901, error.get("code").getAsInt(),
				failureMessage);
		Assertions.assertEquals("Sorry, we could not authenticate you.",
				stringValue(error, "message"), failureMessage);
		assertHeader(response, "WWW-Authenticate", "Bearer");
	}

	private void assertInsufficientScopeRejected(@NonNull Simulator simulator,
			@NonNull App app, @NonNull String accessToken,
			@NonNull String failureMessage) {
		assertInsufficientScopeResponseRejected(mcpRequest(simulator,
				app.getConfiguration().getMcpServerPort(), accessToken,
				"tools/list", null, "en-US", ""), failureMessage);
	}

	private void assertInsufficientScopeResponseRejected(
			@NonNull McpSimulationResponse response,
			@NonNull String failureMessage) {
		JsonObject error = jsonBody(response).getAsJsonObject("error");

		Assertions.assertEquals(403, response.getStatusCode(), failureMessage);
		Assertions.assertEquals(-31903, error.get("code").getAsInt(),
				failureMessage);
		Assertions.assertEquals(
				"Sorry, you are not authorized to perform this action.",
				stringValue(error, "message"), failureMessage);
		assertHeader(response, "WWW-Authenticate",
				"Bearer error=\"insufficient_scope\", scope=\"mcp:read\"");
	}

	@NonNull
	private McpSimulationResponse subscriptionRequest(@NonNull Simulator simulator,
			@NonNull App app, @Nullable String accessToken) {
		return mcpRequest(simulator, app.getConfiguration().getMcpServerPort(),
				accessToken, "subscriptions/listen", null, "en-US",
				",\"notifications\":{\"toolsListChanged\":true}");
	}

	@NonNull
	private ToyResponseHolder createToy(@NonNull Simulator simulator,
			@NonNull Gson gson, @NonNull String accessToken, @NonNull String name,
			@NonNull BigDecimal price, @NonNull Currency currency) {
		String body = gson.toJson(new ToyCreateRequest(name, price, currency));
		Request request = Request.withPath(HttpMethod.POST, "/toys")
				.headers(Map.of("Authorization",
						Set.of("Bearer " + accessToken)))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
		MarshaledResponse response = simulator.performHttpRequest(request)
				.getMarshaledResponse();
		Assertions.assertEquals(200, response.getStatusCode());
		return gson.fromJson(responseBodyAsString(response),
				ToyResponseHolder.class);
	}

	@NonNull
	private McpSimulationResponse mcpRequest(@NonNull Simulator simulator,
			@NonNull Integer mcpPort, @Nullable String accessToken,
			@NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix) {
		return mcpRequestWithAuthorization(simulator, mcpPort,
				accessToken == null ? null : "Bearer " + accessToken, method,
				operationName, acceptLanguage, paramsSuffix);
	}

	@NonNull
	private McpSimulationResponse mcpRequestWithAuthorization(
			@NonNull Simulator simulator, @NonNull Integer mcpPort,
			@Nullable String authorizationHeader, @NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix) {
		return mcpRequestWithAuthorization(simulator, mcpPort, authorizationHeader,
				method, operationName, acceptLanguage, paramsSuffix, false);
	}

	@NonNull
	private McpSimulationResponse mcpRequestWithAuthorization(
			@NonNull Simulator simulator, @NonNull Integer mcpPort,
			@Nullable String authorizationHeader, @NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix, boolean skillsEnabled) {
		return mcpRequestWithCapabilities(simulator, mcpPort, authorizationHeader,
				method, operationName, acceptLanguage, paramsSuffix,
				skillsEnabled ? "{\"extensions\":{\"io.modelcontextprotocol/skills\":{}}}" : "{}");
	}

	@NonNull
	private McpSimulationResponse mcpRequestWithCapabilities(
			@NonNull Simulator simulator, @NonNull Integer mcpPort,
			@Nullable String authorizationHeader, @NonNull String method,
			@Nullable String operationName, @Nullable String acceptLanguage,
			@NonNull String paramsSuffix, @NonNull String capabilities) {
		String body = """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"%s",
				  "params":{
				    "_meta":{
				      "io.modelcontextprotocol/protocolVersion":"%s",
				      "io.modelcontextprotocol/clientCapabilities":%s
				    }%s
				  }
				}
				""".formatted(UUID.randomUUID(), method, PROTOCOL_VERSION,
				capabilities,
				paramsSuffix);
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("Host", Set.of("127.0.0.1:" + mcpPort));
		if (authorizationHeader != null)
			headers.put("Authorization", Set.of(authorizationHeader));
		headers.put("Content-Type", Set.of("application/json; charset=UTF-8"));
		headers.put("Accept",
				Set.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", Set.of(PROTOCOL_VERSION));
		headers.put("Mcp-Method", Set.of(method));
		if (operationName != null)
			headers.put("Mcp-Name", Set.of(operationName));
		if (acceptLanguage != null)
			headers.put("Accept-Language", Set.of(acceptLanguage));

		Request request = Request.withPath(HttpMethod.POST, "/mcp")
				.headers(headers)
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
		try (McpSimulation simulation = simulator.startMcpRequest(request)) {
			McpSimulationResponse response = simulation.awaitResponse(MCP_WAIT)
					.orElseThrow(() -> new AssertionError(
							"Timed out awaiting MCP response."));
			var completion = simulation.awaitCompletion(MCP_WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting MCP completion."));
			Assertions.assertEquals(McpSimulationBodyType.JSON,
					response.getBodyType(), () -> "Unexpected MCP response: status=%s, headers=%s, completion=%s, throwables=%s"
							.formatted(response.getStatusCode(), response.getHeaders(),
									completion.getReason(), completion.getThrowables()));
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					completion.getReason());
			Assertions.assertTrue(simulation.awaitStreamItem(Duration.ZERO).isEmpty(),
					"A JSON response unexpectedly emitted an SSE item.");
			return response;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private McpSimulationResponse appRequest(@NonNull Simulator simulator,
			@NonNull App app, @Nullable String token, @NonNull String method,
			@Nullable String operationName, @Nullable String language,
			@NonNull String paramsSuffix, boolean appsEnabled) {
		return mcpRequestWithCapabilities(simulator, app.getConfiguration().getMcpServerPort(),
				token == null ? null : "Bearer " + token, method, operationName, language,
				paramsSuffix, appsEnabled ? APPS_AND_SKILLS : "{}");
	}

	@NonNull
	private McpSimulationResponse skillRequest(@NonNull Simulator simulator,
			@NonNull App app, @Nullable String token, @NonNull String method,
			@Nullable String uri, @Nullable String language) {
		return mcpRequestWithAuthorization(simulator,
				app.getConfiguration().getMcpServerPort(),
				token == null ? null : "Bearer " + token, method,
				method.equals("resources/read") ? uri : null, language,
				uri == null ? "" : ",\"uri\":\"" + uri + "\"", true);
	}

	@NonNull
	private JsonObject successfulResult(@NonNull McpSimulationResponse response) {
		Assertions.assertEquals(200, response.getStatusCode());
		JsonObject body = jsonBody(response);
		Assertions.assertFalse(body.has("error"), body::toString);
		return requireNonNull(body.getAsJsonObject("result"));
	}

	@NonNull
	private String skillUri(@NonNull String locale, @NonNull String relativePath) {
		return "skill://toystore/v1/" + locale + "/toy-catalog-guide/" + relativePath;
	}

	private void assertListedSkillLocale(@NonNull Simulator simulator, @NonNull App app,
			@NonNull String token, @Nullable String language, @Nullable String expectedLocale) {
		JsonObject result = successfulResult(skillRequest(simulator, app, token, "skills/list", null, language));
		Assertions.assertFalse(result.has("nextCursor"));
		JsonArray skills = result.getAsJsonArray("skills");
		Assertions.assertEquals(expectedLocale == null ? 0 : 1, skills.size(),
				"Unexpected Skill selection for Accept-Language " + language);
		if (expectedLocale != null) {
			JsonObject skill = skills.get(0).getAsJsonObject();
			String uri = skillUri(expectedLocale, "SKILL.md");
			Assertions.assertEquals(uri, stringValue(skill, "uri"));
			Assertions.assertEquals(skill, successfulResult(skillRequest(simulator, app, token,
					"skills/get", uri, language)).getAsJsonObject("skill"));
		}
	}

	@NonNull
	private byte[] skillFile(@NonNull String locale, @NonNull String relativePath) {
		try (InputStream input = ToyStoreMcpEndpointTests.class.getResourceAsStream(
				"/mcp/skills/" + locale + "/toy-catalog-guide/" + relativePath)) {
			return requireNonNull(input, "Missing packaged Skill file").readAllBytes();
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private String digest(byte[] bytes) {
		try {
			return "sha256:" + HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private JsonObject jsonBody(@NonNull McpSimulationResponse response) {
		byte[] body = response.getBody().orElseThrow(() ->
				new AssertionError("MCP JSON body was not captured."));
		return JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
				.getAsJsonObject();
	}

	@NonNull
	private JsonObject tool(@NonNull JsonObject response,
			@NonNull String toolName) {
		JsonArray tools = response.getAsJsonObject("result")
				.getAsJsonArray("tools");
		for (int index = 0; index < tools.size(); ++index) {
			JsonObject tool = tools.get(index).getAsJsonObject();
			if (toolName.equals(stringValue(tool, "name")))
				return tool;
		}
		throw new AssertionError("Tool not found: " + toolName);
	}

	private void assertHeader(@NonNull McpSimulationResponse response,
			@NonNull String name, @NonNull String expectedValue) {
		Set<String> values = response.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"Missing response header: " + name));
		Assertions.assertTrue(values.stream()
				.anyMatch(value -> value.equalsIgnoreCase(expectedValue)),
				() -> "Expected %s=%s but was %s".formatted(
						name, expectedValue, values));
	}

	private void assertVaryAcceptLanguage(
			@NonNull McpSimulationResponse response) {
		Set<String> values = response.getHeaders().entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase("Vary"))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing Vary header."));
		Assertions.assertTrue(values.stream()
				.flatMap(value -> java.util.Arrays.stream(value.split(",")))
				.map(String::trim)
				.anyMatch(value -> value.equalsIgnoreCase("Accept-Language")));
	}

	@NonNull
	private String stringValue(@NonNull JsonObject object,
			@NonNull String fieldName) {
		return requireNonNull(object).get(requireNonNull(fieldName)).getAsString();
	}
}

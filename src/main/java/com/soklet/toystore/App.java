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

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import com.pyranid.Database;
import com.soklet.ShutdownTrigger;
import com.soklet.Soklet;
import com.soklet.SokletConfig;
import com.soklet.toystore.model.db.Role.RoleId;
import com.soklet.toystore.util.PasswordManager;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.soklet.toystore.util.Normalizer.normalizeEmailAddress;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates the entire system in a single reusable type.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class App {
	public static void main(String[] args) throws Exception {
		String environment = System.getenv("TOYSTORE_ENVIRONMENT");

		if (environment == null)
			throw new IllegalArgumentException("You must specify the TOYSTORE_ENVIRONMENT environment variable");

		App app = new App(new Configuration(environment));
		app.startServer();
	}

	@NonNull
	private final Configuration configuration;
	@NonNull
	private final Injector injector;
	@NonNull
	private final Logger logger;

	public App(@NonNull Configuration configuration,
						 @Nullable Module... testingModules) {
		requireNonNull(configuration);

		// Use Guice modules for DI.
		// Also permit overrides for testing, e.g. swap in a mock credit card processor
		Module module = new AppModule(configuration);

		if (testingModules != null)
			module = Modules.override(module).with(testingModules);

		this.configuration = configuration;
		this.injector = Guice.createInjector(module);
		this.logger = LoggerFactory.getLogger(App.class);

		// Load up an example schema and data
		initializeDatabase();
	}

	public void startServer() throws InterruptedException {
		SokletConfig config = getInjector().getInstance(SokletConfig.class);

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			if (getConfiguration().getStopOnKeypress()) {
				getLogger().debug("Press [enter] to exit");
				soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
			} else {
				soklet.awaitShutdown();
			}
		}
	}

	// A real system would keep its table creates/DDL in files outside of Java code.
	// We keep them here for demonstration purposes
	private void initializeDatabase() {
		// Ask Guice for some instances
		Database database = getInjector().getInstance(Database.class);
		PasswordManager passwordManager = getInjector().getInstance(PasswordManager.class);

		database.query("""
				CREATE TABLE role (
					role_id VARCHAR(64) PRIMARY KEY,
					description VARCHAR(256) NOT NULL
				)
				""").execute();

		database.query("INSERT INTO role (role_id, description) VALUES (:roleId, :description)")
				.executeBatch(List.of(
						Map.of(
								"roleId", RoleId.CUSTOMER,
								"description", "Customer"
						),
						Map.of(
								"roleId", RoleId.EMPLOYEE,
								"description", "Employee"
						),
						Map.of(
								"roleId", RoleId.ADMINISTRATOR,
								"description", "Administrator"
						)
				));

		database.query("""
				CREATE TABLE account (
					account_id UUID PRIMARY KEY,
					role_id VARCHAR(64) NOT NULL REFERENCES role(role_id),
					name VARCHAR(128) NOT NULL,
					email_address VARCHAR(320) NOT NULL,
					password_hash VARCHAR(512)  NOT NULL,
					time_zone VARCHAR(128) NOT NULL, -- e.g. 'America/New_York'
					locale VARCHAR(8) NOT NULL, -- e.g. 'pt-BR'
					created_at TIMESTAMP DEFAULT NOW() NOT NULL
				)
				""").execute();

		database.query("CREATE UNIQUE INDEX account_email_address_unique_idx ON account(email_address)").execute();

		// Create a single administrator
		database.query("""
						INSERT INTO account (
							account_id,
							role_id,
							name,
							email_address,
							password_hash,
							time_zone,
							locale
						) VALUES (:accountId, :roleId, :name, :emailAddress, :passwordHash, :timeZone, :locale)
						""")
				.bind("accountId", UUID.fromString("08d0ba3e-b19c-4317-a146-583860fcb5fd"))
				.bind("roleId", RoleId.ADMINISTRATOR)
				.bind("name", "Example Administrator")
				.bind("emailAddress", normalizeEmailAddress("admin@soklet.com"))
				.bind("passwordHash", passwordManager.hashPassword("administrator-password"))
				.bind("timeZone", ZoneId.of("America/New_York"))
				.bind("locale", Locale.forLanguageTag("en-US"))
				.execute();

		// Create an employee and a customer with distinct locale/timezone settings
		database.query("""
						INSERT INTO account (
							account_id,
							role_id,
							name,
							email_address,
							password_hash,
							time_zone,
							locale
						) VALUES (:accountId, :roleId, :name, :emailAddress, :passwordHash, :timeZone, :locale)
						""")
				.bind("accountId", UUID.fromString("3d2c6fd1-6d9b-40bb-9ca0-2d2ee00e24b9"))
				.bind("roleId", RoleId.EMPLOYEE)
				.bind("name", "Example Employee")
				.bind("emailAddress", normalizeEmailAddress("employee@soklet.com"))
				.bind("passwordHash", passwordManager.hashPassword("employee-password"))
				.bind("timeZone", ZoneId.of("Europe/Berlin"))
				.bind("locale", Locale.forLanguageTag("de-DE"))
				.execute();

		database.query("""
						INSERT INTO account (
							account_id,
							role_id,
							name,
							email_address,
							password_hash,
							time_zone,
							locale
						) VALUES (:accountId, :roleId, :name, :emailAddress, :passwordHash, :timeZone, :locale)
						""")
				.bind("accountId", UUID.fromString("a9c0b6d4-1a29-4e83-9b39-32e8e23f4064"))
				.bind("roleId", RoleId.CUSTOMER)
				.bind("name", "Example Customer")
				.bind("emailAddress", normalizeEmailAddress("customer@soklet.com"))
				.bind("passwordHash", passwordManager.hashPassword("customer-password"))
				.bind("timeZone", ZoneId.of("America/Sao_Paulo"))
				.bind("locale", Locale.forLanguageTag("pt-BR"))
				.execute();

		database.query("""
				CREATE TABLE toy (
					toy_id UUID PRIMARY KEY,
					name VARCHAR(256) NOT NULL,
					price DECIMAL(10,2) NOT NULL,
					currency VARCHAR(8) NOT NULL,
					created_at TIMESTAMP DEFAULT NOW() NOT NULL,
					CONSTRAINT toy_name_unique_idx UNIQUE(name)
				)
				""").execute();

		database.query("""
				CREATE TABLE purchase (
					purchase_id UUID PRIMARY KEY,
					account_id UUID NOT NULL REFERENCES account,
					toy_id UUID NOT NULL REFERENCES toy,
					price DECIMAL(10,2) NOT NULL,
					currency VARCHAR(8) NOT NULL,
					credit_card_txn_id VARCHAR(256) NOT NULL,
					created_at TIMESTAMP DEFAULT NOW() NOT NULL
				)
				""").execute();
	}

	@NonNull
	public Configuration getConfiguration() {
		return this.configuration;
	}

	@NonNull
	public Injector getInjector() {
		return this.injector;
	}

	@NonNull
	private Logger getLogger() {
		return this.logger;
	}
}

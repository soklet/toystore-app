/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import com.pyranid.Database;
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.example.model.db.Role.RoleId;
import com.soklet.example.util.PasswordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class App {
	public static void main(@Nullable String[] args) throws Exception {
		App app = new App(new Configuration());
		app.startServer();
	}

	@Nonnull
	private final Configuration configuration;
	@Nonnull
	private final Injector injector;
	@Nonnull
	private final Logger logger;

	public App(@Nonnull Configuration configuration,
						 @Nullable Module... testingModules) {
		requireNonNull(configuration);

		// Use Guice modules for DI.
		// Also permit overrides for testing, e.g. swap in a mock credit card processor
		Module module = new AppModule();

		if (testingModules != null)
			module = Modules.override(module).with(testingModules);

		this.configuration = configuration;
		this.injector = Guice.createInjector(module);
		this.logger = LoggerFactory.getLogger(App.class);

		// Load up an example schema and data
		initializeDatabase();
	}

	public void startServer() throws IOException, InterruptedException {
		SokletConfiguration sokletConfiguration = getInjector().getInstance(SokletConfiguration.class);

		try (Soklet soklet = new Soklet(sokletConfiguration)) {
			soklet.start();

			if (getConfiguration().getStopOnKeypress()) {
				getLogger().debug("Press [enter] to exit");
				System.in.read();
			} else {
				Thread.currentThread().join();
			}
		}
	}

	// A real system would keep its table creates/DDL in files outside of Java code.
	// We keep them here for demonstration purposes
	protected void initializeDatabase() {
		Database database = getInjector().getInstance(Database.class);

		database.execute("""
				CREATE TABLE role (
					role_id VARCHAR(255) PRIMARY KEY,
					description VARCHAR(4096) NOT NULL
				)
				""");

		database.executeBatch("INSERT INTO role (role_id, description) VALUES (?,?)", List.of(
				List.of(RoleId.CUSTOMER, "Customer"),
				List.of(RoleId.EMPLOYEE, "Employee"),
				List.of(RoleId.ADMINISTRATOR, "Administrator"))
		);

		database.execute("""
				CREATE TABLE account (
					account_id UUID PRIMARY KEY,
					role_id VARCHAR(255) NOT NULL REFERENCES role(role_id),
					name VARCHAR(1024) NOT NULL,
					email_address VARCHAR(1024),
					password VARCHAR(1024),
					time_zone VARCHAR(255) NOT NULL, -- e.g. 'America/New_York'
					locale VARCHAR(255) NOT NULL, -- e.g. 'pt-BR'
					created_at TIMESTAMP DEFAULT NOW() NOT NULL
				)
				""");

		// Create a single administrator
		database.execute("""
						INSERT INTO account (
							account_id,
							role_id,
							name,
							email_address,
							password,
							time_zone,
							locale
						) VALUES (?,?,?,?,?,?,?)
						""",
				UUID.fromString("08d0ba3e-b19c-4317-a146-583860fcb5fd"),
				RoleId.ADMINISTRATOR,
				"Example Administrator",
				"admin@soklet.com",
				PasswordManager.sharedInstance().hashPassword("test123"),
				ZoneId.of("America/New_York"),
				Locale.forLanguageTag("en-US")
		);

		database.execute("""
				CREATE TABLE toy (
					toy_id UUID PRIMARY KEY,
					name VARCHAR(4096) NOT NULL,
					price DECIMAL(10,2) NOT NULL,
					currency VARCHAR(8) NOT NULL,
					created_at TIMESTAMP DEFAULT NOW() NOT NULL,
					CONSTRAINT toy_name_unique_idx UNIQUE(name)
				)
				""");

		database.execute("""
				CREATE TABLE purchase (
					purchase_id UUID PRIMARY KEY,
					account_id UUID NOT NULL REFERENCES account,
					toy_id UUID NOT NULL REFERENCES toy,
					price DECIMAL(10,2) NOT NULL,
					currency VARCHAR(8) NOT NULL,
					credit_card_txn_id VARCHAR(1024) NOT NULL,
					created_at TIMESTAMP DEFAULT NOW() NOT NULL
				)
				""");
	}

	@Nonnull
	public Configuration getConfiguration() {
		return this.configuration;
	}

	@Nonnull
	public Injector getInjector() {
		return this.injector;
	}

	@Nonnull
	protected Logger getLogger() {
		return this.logger;
	}
}

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

package com.soklet.example;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.pyranid.Database;
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.example.model.db.Role.RoleId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
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
		Injector injector = Guice.createInjector(new AppModule());
		Configuration configuration = injector.getInstance(Configuration.class);
		Database database = injector.getInstance(Database.class);
		SokletConfiguration sokletConfiguration = injector.getInstance(SokletConfiguration.class);

		initializeDatabase(database);

		try (Soklet soklet = new Soklet(sokletConfiguration)) {
			soklet.start();

			if (configuration.getStopOnKeypress()) {
				System.out.println("Press [enter] to exit");
				System.in.read();
			} else {
				Thread.currentThread().join();
			}
		}
	}

	private static void initializeDatabase(@Nonnull Database database) {
		requireNonNull(database);

		// Create an example schema and load up some data
		database.execute("""
				CREATE TABLE role (
					role_id VARCHAR(255) PRIMARY KEY,
					description VARCHAR(255) NOT NULL
					)
					""");

		database.executeBatch("INSERT INTO role (role_id, description) VALUES (?,?)", List.of(
				List.of(RoleId.ADMINISTRATOR, "Administrator"),
				List.of(RoleId.RANK_AND_FILE, "Rank-and-file"))
		);

		database.execute("""
				CREATE TABLE employee (
					employee_id UUID PRIMARY KEY,
					role_id VARCHAR(255) NOT NULL REFERENCES role(role_id),
					name VARCHAR(255) NOT NULL,
					email_address VARCHAR(255),
					time_zone VARCHAR(255) NOT NULL,
					locale VARCHAR(255) NOT NULL
					)
					""");

		// Create a single administrator
		database.execute("""
						INSERT INTO employee (
							employee_id,
							role_id,
							name,
							email_address,
							time_zone,
							locale
						) VALUES (?,?,?,?,?,?)				 
						""", UUID.fromString("08d0ba3e-b19c-4317-a146-583860fcb5fd"), RoleId.ADMINISTRATOR,
				"Example Administrator", "admin@soklet.com", ZoneId.of("America/New_York"), Locale.forLanguageTag("en-US"));
	}
}

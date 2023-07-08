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
import com.soklet.Soklet;
import com.soklet.SokletConfiguration;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class App {
	public static void main(@Nullable String[] args) throws Exception {
		Injector injector = Guice.createInjector(new AppModule());
		Configuration configuration = injector.getInstance(Configuration.class);
		SokletConfiguration sokletConfiguration = injector.getInstance(SokletConfiguration.class);

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
}

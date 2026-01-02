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

package com.soklet.toystore.util;

import org.jspecify.annotations.NonNull;

/**
 * Contract for loading sensitive data, e.g. passwords and API tokens.
 * <p>
 * A mock implementor might read from the filesystem for local development,
 * while a real implementor might pull from a third party service, e.g. AWS Secrets Manager.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface SecretsManager {
	@NonNull
	String getKeypairPrivateKey();

	enum Type {
		MOCK,
		REAL
	}
}
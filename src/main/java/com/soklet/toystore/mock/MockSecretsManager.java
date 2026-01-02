/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet.toystore.mock;

import com.soklet.toystore.util.SecretsManager;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.soklet.toystore.util.Normalizer.trimAggressivelyToNull;
import static java.lang.String.format;

/**
 * Mock implementation of {@link SecretsManager} which pulls secrets from the filesystem.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MockSecretsManager implements SecretsManager {
	@NonNull
	private String keypairPrivateKey;

	public MockSecretsManager() {
		this.keypairPrivateKey = loadKeypairPrivateKey();
	}

	@NonNull
	@Override
	public String getKeypairPrivateKey() {
		return this.keypairPrivateKey;
	}

	@NonNull
	private String loadKeypairPrivateKey() {
		// Hardcode a path; this is a mock implementation
		Path path = Path.of("secrets/keypair-private-key");

		if (!Files.isRegularFile(path))
			throw new IllegalStateException(format("Keypair private key file not found at %s", path.toAbsolutePath()));

		try {
			String keypairPrivateKey = trimAggressivelyToNull(Files.readString(path, StandardCharsets.UTF_8));

			if (keypairPrivateKey == null)
				throw new IllegalStateException(format("Keypair private key file at %s is empty", path.toAbsolutePath()));

			return keypairPrivateKey;
		} catch (IOException e) {
			throw new UncheckedIOException(format("Error reading keypair private key from %s", path.toAbsolutePath()), e);
		}
	}
}

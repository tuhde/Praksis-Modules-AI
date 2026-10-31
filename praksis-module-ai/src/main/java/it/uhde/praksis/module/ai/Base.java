// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import java.nio.file.Path;
import java.util.Map;

/**
 * What the capabilities of the module share: the profiles, the key, and the log in the data
 * directory.
 */
abstract class Base implements Capability {
  private Profile.Settings settings = new Profile.Settings(Map.of(), null, 14, true, false);
  private Credentials credentials;
  private Path directory;
  private Store store;
  private Runner runner;

  @Override
  public void configure(Map<String, Object> settings) throws CapabilityException {
    this.settings = Profile.parse(settings);
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    this.credentials = credentials;
  }

  @Override
  public synchronized void useDataDirectory(Path directory) {
    this.directory = directory;
  }

  final synchronized Store store() throws CapabilityException {
    if (store == null) {
      store = Store.open(directory);
    }
    return store;
  }

  final synchronized Runner runner() throws CapabilityException {
    if (runner == null) {
      runner = new Runner(settings, credentials, store());
    }
    return runner;
  }

  /**
   * The answers of the capabilities that ask a model all say where the text went and what it cost.
   */
  static Map<String, Object> with(Map<String, Object> result, Runner.Done done) {
    result.putAll(done.facts());
    return result;
  }

  @Override
  public synchronized void close() {
    if (store != null) {
      store.release();
      store = null;
      runner = null;
    }
  }
}

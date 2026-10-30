// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the two capabilities share: the settings, and what they tell about what was heard. */
abstract class Base implements Capability {
  private Settings settings;
  private volatile Path uploads;

  @Override
  public void configure(Map<String, Object> settings) throws CapabilityException {
    this.settings = Settings.parse(settings);
  }

  @Override
  public void useUploadsDirectory(Path directory) {
    this.uploads = directory;
  }

  final Settings settings() throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("the speech module is not set up", false);
    }
    return settings;
  }

  final Path uploads() {
    return uploads;
  }

  static Map<String, Object> result(Recognizer.Heard heard, Settings.Profile profile) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("text", heard.text());
    m.put("heard", !heard.text().isEmpty());
    m.put("profile", profile.name());
    m.put("language", profile.language());
    m.put("seconds", heard.seconds());
    return m;
  }

  static Map<String, Object> nothing(Settings.Profile profile) {
    return result(new Recognizer.Heard("", 0.0), profile);
  }
}

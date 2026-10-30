// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.Map;

/**
 * {@code speech.listen}: listens on the microphone until one sentence has been said, or the seconds
 * are over, and gives the text. Nothing is recorded to a file and nothing leaves the machine.
 */
public final class SpeechListen extends Base {
  @Override
  public String name() {
    return "speech.listen";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    Settings settings = settings();
    Settings.Profile profile = settings.profile(text(args, "profile"));
    int seconds = 8;
    if (args.get("seconds") != null) {
      if (!(args.get("seconds") instanceof Number n)
          || n.intValue() < 1
          || n.intValue() > settings.maxSeconds()) {
        throw new CapabilityException(
            "'seconds' must be a whole number from 1 to " + settings.maxSeconds(), false);
      }
      seconds = n.intValue();
    }
    if (context.dryRun()) {
      return nothing(profile); // a dry run does not switch the microphone on
    }
    return result(Recognizer.listen(settings, profile, seconds), profile);
  }

  static String text(Map<String, Object> args, String name) throws CapabilityException {
    Object v = args.get(name);
    if (v != null && !(v instanceof String)) {
      throw new CapabilityException("'" + name + "' must be a text", false);
    }
    return (String) v;
  }
}

// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code speech.transcribe}: the text of an audio file that a person chose. The file is named by
 * the token that the upload gave, never by a path, so a call cannot make the module read any other
 * file.
 */
public final class SpeechTranscribe extends Base {
  private static final Pattern TOKEN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  @Override
  public String name() {
    return "speech.transcribe";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    Settings settings = settings();
    Settings.Profile profile = settings.profile(SpeechListen.text(args, "profile"));
    String token = SpeechListen.text(args, "file");
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new CapabilityException("'file' must be the token of an uploaded file", false);
    }
    if (uploads() == null) {
      throw new CapabilityException(
          "the uploads directory is not there, which the daemon should have given", false);
    }
    Path file = uploads().resolve(token + ".part");
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new CapabilityException(
            "the file is not there (uploaded files are removed after a day)", false);
      }
      if (Files.size(file) > settings.maxFileBytes()) {
        throw new CapabilityException(
            "the file is more than the limit of " + (settings.maxFileBytes() >> 20) + " MiB",
            false);
      }
    } catch (IOException e) {
      throw new CapabilityException("cannot read the file", true, e);
    }
    if (context.dryRun()) {
      return nothing(profile);
    }
    return result(Recognizer.transcribe(settings, profile, file), profile);
  }
}

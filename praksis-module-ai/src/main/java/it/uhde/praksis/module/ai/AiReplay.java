// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ai.replay}: sends a logged {@code ai.complete} call again, byte for byte (the same system
 * message and the same user message with the same markers), to the profile it went to or to
 * another, and says whether the answer is the same. It shows how much an answer, and so a change
 * that a model made, depends on chance and on the model. It needs the text of the call in the log
 * ({@code log_content = true}), and it sends that text to the profile like any call does, so the
 * grant on {@code profile} and offline mode apply.
 */
public final class AiReplay extends Base {
  @Override
  public String name() {
    return "ai.replay";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    long id = Args.number(args, "id", 0, 0, Integer.MAX_VALUE);
    if (id < 1) {
      throw new CapabilityException("'id' is required (see ai.calls)", false);
    }
    Store.Stored was =
        store()
            .find(id)
            .orElseThrow(() -> new CapabilityException("there is no logged call " + id, false));
    if (!was.capability().equals("ai.complete") && !was.capability().equals("ai.replay")) {
      throw new CapabilityException(
          "call " + id + " is " + was.capability() + "; only ai.complete can be sent again", false);
    }
    if (was.prompt().startsWith("sha256:") || was.system().isEmpty()) {
      throw new CapabilityException(
          "the text of call "
              + id
              + " was not kept (log_content = false), so it cannot be sent again",
          false);
    }
    String user = was.prompt().substring(was.system().length() + 1);
    Runner runner = runner();
    Profile profile =
        runner.profile(
            Args.text(args, "profile", false) == null
                ? was.profile()
                : Args.text(args, "profile", false));
    Runner.Done done = runner.resend(context, profile, was.system(), user, 0);
    String again = done.reply().text().strip();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("again", again);
    result.put("original", was.response().strip());
    result.put("same", again.equals(was.response().strip()));
    result.put("original_model", was.model());
    return with(result, done);
  }
}

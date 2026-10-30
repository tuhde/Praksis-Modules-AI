// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code ai.embed}: a vector for a text, for searching by meaning. */
public final class AiEmbed extends Base {
  @Override
  public String name() {
    return "ai.embed";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String text = Args.text(args, "text", true);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));
    Runner.Embedding embedding = runner.embed(context, profile, text);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("vector", embedding.vector());
    result.put("dimensions", (long) embedding.vector().size());
    return with(result, embedding.done());
  }
}

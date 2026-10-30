// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ai.complete}: an instruction (written by whoever builds the rule) and, apart from it, text
 * that may come from outside; the answer is text.
 */
public final class AiComplete extends Base {
  @Override
  public String name() {
    return "ai.complete";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String prompt = Args.text(args, "prompt", true);
    String text = Args.text(args, "text", false);
    String system = Args.text(args, "system", false);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));
    int maxTokens = Args.number(args, "max_tokens", 0, 1, 32_768);
    Runner.Done done =
        runner.chat(
            context, name(), profile, system, prompt, text == null ? "" : text, null, maxTokens);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("text", done.reply().text().strip());
    return with(result, done);
  }
}

// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai.classify}: which of the given labels fits a text best. The server is told to answer
 * with one of the labels, and the answer is checked against the list anyway: what is not on it is
 * not a label, however the model got there, so a rule can branch on the result without trusting the
 * model.
 */
public final class AiClassify extends Base {
  @Override
  public String name() {
    return "ai.classify";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String text = Args.text(args, "text", true);
    List<String> labels = Args.names(args, "labels", 2, 50, 64, null);
    String instructions = Args.text(args, "instructions", false);
    String fallback = Args.text(args, "fallback", false);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));

    Classifier.Verdict verdict =
        Classifier.classify(runner, context, name(), profile, text, labels, instructions);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("label", verdict.matched() ? verdict.label() : fallback == null ? "" : fallback);
    result.put("matched", verdict.matched());
    return with(result, verdict.done());
  }
}

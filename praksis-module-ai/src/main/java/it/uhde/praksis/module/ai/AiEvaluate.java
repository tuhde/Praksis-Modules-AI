// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai.evaluate}: how well a profile does at telling the labels apart on examples whose answer
 * is known. A rule that moves mail because of a label is only as good as the model behind it, and
 * this says how good that is before the rule is switched on: the share of examples that got the
 * label they were given, the examples that did not, and what it cost. Each example is a call of
 * {@code ai.classify} (so it counts against the budget and is logged); when the budget of the day
 * runs out the examples done so far are scored and the answer says it is not complete.
 */
public final class AiEvaluate extends Base {
  private static final int MAX_EXAMPLES = 200;

  @Override
  public String name() {
    return "ai.evaluate";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    List<String> labels = Args.names(args, "labels", 2, 50, 64, null);
    String instructions = Args.text(args, "instructions", false);
    List<Map<String, String>> examples = examples(args, labels);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));

    int correct = 0;
    int off = 0;
    int done = 0;
    long in = 0;
    long out = 0;
    boolean complete = true;
    List<Map<String, Object>> misses = new ArrayList<>();
    Runner.Done last = null;
    for (Map<String, String> example : examples) {
      Classifier.Verdict verdict;
      try {
        verdict =
            Classifier.classify(
                runner, context, name(), profile, example.get("text"), labels, instructions);
      } catch (Runner.BudgetUsedUp e) {
        complete = false;
        break;
      }
      done++;
      last = verdict.done();
      in += verdict.done().reply().tokensIn();
      out += verdict.done().reply().tokensOut();
      if (context.dryRun()) {
        continue;
      }
      if (verdict.matched() && verdict.label().equals(example.get("label"))) {
        correct++;
      } else {
        if (!verdict.matched()) {
          off++;
        }
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("expected", example.get("label"));
        miss.put("got", verdict.matched() ? verdict.label() : "(none of the labels)");
        String text = example.get("text").replaceAll("\\s+", " ");
        miss.put("text", text.length() > 80 ? text.substring(0, 80) + "..." : text);
        misses.add(miss);
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("accuracy", done == 0 || context.dryRun() ? 0.0 : (double) correct / done);
    result.put("correct", (long) correct);
    result.put("total", (long) done);
    result.put("off_list", (long) off);
    result.put("complete", complete);
    result.put("misses", misses);
    result.put("profile", profile.name());
    result.put("model", profile.model());
    result.put("data", profile.data().id());
    result.put("tokens_in", in);
    result.put("tokens_out", out);
    return result;
  }

  /** The examples: texts with the label that is right for each, which must be one of the labels. */
  private static List<Map<String, String>> examples(Map<String, Object> args, List<String> labels)
      throws CapabilityException {
    if (!(args.get("examples") instanceof List<?> list) || list.isEmpty()) {
      throw new CapabilityException("'examples' is required (a list of {text, label})", false);
    }
    if (list.size() > MAX_EXAMPLES) {
      throw new CapabilityException(
          "'examples' has "
              + list.size()
              + " entries, at most "
              + MAX_EXAMPLES
              + " are evaluated at once",
          false);
    }
    List<Map<String, String>> out = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m)
          || !(m.get("text") instanceof String text)
          || text.isBlank()
          || !(m.get("label") instanceof String label)
          || !labels.contains(label)) {
        throw new CapabilityException(
            "every example needs a 'text' and a 'label' that is one of the labels " + labels,
            false);
      }
      out.add(Map.of("text", text, "label", label));
    }
    return out;
  }
}

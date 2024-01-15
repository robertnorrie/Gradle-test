package de.tum.in.pet.util;

import static de.tum.in.probmodels.util.Util.isEqual;

import de.tum.in.pet.analyser.PartialSystem;
import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.model.TransitionSystem;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;

public final class ModelHelper {
    private ModelHelper() {}

    public static void modelWithBoundsToDotFile(
            String filename,
            TransitionSystem model,
            Explorer<?> explorer,
            IntFunction<Bounds> values,
            IntPredicate stateFilter,
            @Nullable IntPredicate highlight) {
        modelWithBoundsToDotFile(
                filename, new PartialSystem(model, explorer.exploredStates()), values, stateFilter, highlight);
    }

    public static void modelWithBoundsToDotFile(
            String filename,
            PartialSystem annotatedModel,
            IntFunction<Bounds> values,
            IntPredicate stateFilter,
            @Nullable IntPredicate highlight) {
        TransitionSystem system = annotatedModel.system();
        IntSet exploredStates = annotatedModel.exploredStates();

        var iterator = system.statesStream()
                .filter(stateFilter)
                .mapToObj(state -> {
                    StringBuilder dotString = new StringBuilder(100);
                    dotString.append(state).append(" [style=filled fillcolor=\"");
                    boolean appendBounds;
                    if (highlight != null && highlight.test(state)) {
                        dotString.append("#22CC22");
                        appendBounds = true;
                    } else if (system.isInitialState(state)) {
                        dotString.append("#9999CC");
                        appendBounds = true;
                    } else if (!exploredStates.contains(state)) {
                        dotString.append("#CC2222");
                        appendBounds = false;
                    } else if (values.apply(state).difference() == 0.0d) {
                        dotString.append("#CD9D87");
                        appendBounds = true;
                    } else {
                        dotString.append("#DDDDDD");
                        appendBounds = true;
                    }
                    dotString.append("\",label=\"").append(state);
                    if (appendBounds) {
                        dotString.append(' ').append(values.apply(state));
                    }
                    dotString.append("\"];\n");

                    int actionIndex = 0;
                    for (Choice choice : system.choices(state)) {
                        Object label = choice.label();
                        if (choice.distribution().size() == 1) {
                            IntIterator stateIterator =
                                    choice.distribution().support().iterator();
                            int successor = stateIterator.nextInt();
                            assert !stateIterator.hasNext();

                            dotString.append(state).append(" -> ").append(successor);
                            if (label != null) {
                                dotString.append("[label=\"").append(label).append("\"]");
                            }
                            dotString.append(";\n");
                        } else {
                            String actionNode = "a%d_%d".formatted(state, actionIndex);
                            dotString
                                    .append(state)
                                    .append(" -> ")
                                    .append(actionNode)
                                    .append(" [arrowhead=none,label=\"")
                                    .append(actionIndex);
                            if (label != null) {
                                dotString.append(':').append(label);
                            }

                            dotString.append("\" ];\n");
                            dotString.append(actionNode).append(" [shape=point,height=0.1];\n");

                            choice.distribution().forEach((target, probability) -> {
                                dotString.append(actionNode).append(" -> ").append(target);
                                if (!isEqual(probability, 1.0d)) {
                                    dotString
                                            .append(" [label=\"")
                                            .append(String.format("%.3f", probability))
                                            .append("\"]");
                                }
                                dotString.append(";\n");
                            });
                        }
                        actionIndex += 1;
                    }
                    dotString.append("}\n");
                    return dotString.toString();
                })
                .iterator();

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(filename), StandardCharsets.UTF_8)) {
            writer.write("digraph Model {\n\tnode [shape=box];\n");
            while (iterator.hasNext()) {
                writer.write(iterator.next());
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}

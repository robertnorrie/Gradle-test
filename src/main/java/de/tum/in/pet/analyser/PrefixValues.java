package de.tum.in.pet.analyser;

import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.values.Bounds;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The central objective-specific interface for prefix dependent objectives (i.e. without collapsing). These object
 * store and update the values for selected states.
 */
public interface PrefixValues {
    /**
     * The currently stored bounds for the given state.
     */
    Bounds bounds(int state, int remaining);

    /**
     * The currently stored lower bound for the given state.
     */
    default double lowerBound(int state, int remaining) {
        return bounds(state, remaining).lowerBound();
    }

    /**
     * The currently stored upper bound for the given state.
     */
    default double upperBound(int state, int remaining) {
        return bounds(state, remaining).upperBound();
    }

    /**
     * The difference between the currently stored upper and lower bound for the given state.
     */
    default double difference(int state, int remaining) {
        return bounds(state, remaining).difference();
    }

    /**
     * The scores for each available choice, used to guide sampling.
     */
    ToDoubleFunction<Choice> score(int state, int remaining, List<Choice> choices);

    /**
     * Update the values for the given state and selected choice.
     */
    Bounds update(int state, int remaining, List<Choice> choices, Choice selected);
}

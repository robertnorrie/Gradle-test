package de.tum.in.pet.analyser;

import de.tum.in.pet.implementation.meanpayoff.MeanPayoffValues;
import de.tum.in.probmodels.graph.Component;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.values.Bounds;
import java.util.List;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;

/**
 * The central objective-specific interface for prefix independent objectives. These object store and update the values
 * for selected states.
 */
public interface CollapsingValues<C> {
    /**
     * The currently stored bounds for the given state.
     */
    Bounds bounds(int state);

    /**
     * Returns true iff no information is known about the state (used in pruning models).
     */
    boolean isUnknown(int state);

    /**
     * The currently stored lower bound for the given state.
     */
    default double lowerBound(int state) {
        return bounds(state).lowerBound();
    }

    /**
     * The currently stored upper bound for the given state.
     */
    default double upperBound(int state) {
        return bounds(state).upperBound();
    }

    /**
     * The difference between the currently stored upper and lower bound for the given state.
     */
    default double difference(int state) {
        return bounds(state).difference();
    }

    /**
     * The choices available in the given state. This usually is either exactly the list of distributions
     * (for component-independent objectives) or the given distributions together with a special "staying" action (see
     * {@link MeanPayoffValues#choices(int, List)} for an example).
     *
     * @param state
     *     The given state.
     * @param distributions
     *     The transitions in this state in the quotient model.
     */
    List<? extends C> choices(int state, List<Distribution> distributions);

    /**
     * Yield the available successors for the selected choice or {@code null} if this choice is terminal.
     */
    @Nullable
    Distribution successors(int state, List<Distribution> distributions, C choice);

    /**
     * The scores for each available choice, used to guide sampling. It is required that optimal actions repeatedly
     * are amongst the highest scoring ones.
     *
     * @param distributions
     *     The transitions in this state in the quotient model, provided for completeness but usually not needed.
     * @param choices
     *     The choices in this state as returned by {@link #choices(int, List)}, provided for completeness but usually
     *     not needed.
     */
    ToDoubleFunction<C> score(int state, List<Distribution> distributions, List<? extends C> choices);

    /**
     * Update the values for the given state and selected choice.
     *
     * @param distributions
     *     The transitions in this state in the quotient model, provided for completeness but usually not needed.
     * @param choices
     *     The choices in this state as returned by {@link #choices(int, List)}, provided for completeness but usually
     *     not needed.
     * @param selected
     *     The selected choice.
     */
    Bounds update(int state, List<Distribution> distributions, List<? extends C> choices, C selected);

    /**
     * Notify that the given component is found and collapsed with the given representative and transient distribution.
     */
    void collapse(int representative, List<Distribution> distributions, Component collapsed);
}

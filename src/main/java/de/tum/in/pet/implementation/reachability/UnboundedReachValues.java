package de.tum.in.pet.implementation.reachability;

import static de.tum.in.probmodels.util.Util.isOne;
import static de.tum.in.probmodels.util.Util.isZero;

import com.google.common.collect.Lists;
import de.tum.in.naturals.map.Nat2ObjectDenseArrayMap;
import de.tum.in.pet.analyser.CollapsingValues;
import de.tum.in.probmodels.graph.Component;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.problem.query.Optimization;
import de.tum.in.probmodels.util.Util;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;

public class UnboundedReachValues implements CollapsingValues<Distribution> {
    private final Int2ObjectMap<Bounds> bounds = new Nat2ObjectDenseArrayMap<>(1024);
    private final Optimization update;
    private final IntPredicate goal;

    public UnboundedReachValues(IntPredicate goal, Optimization update) {
        this.goal = goal;
        this.update = update;
    }

    @Override
    public Bounds bounds(int state) {
        return bounds.computeIfAbsent(
                state, (IntFunction<? extends Bounds>) (int s) -> goal.test(s) ? Bounds.one() : Bounds.unknownReach());
        // return goal.test(state) ? Bounds.reachOne() : bounds.getOrDefault(state, Bounds.reachUnknown());
    }

    @Override
    public boolean isUnknown(int state) {
        return isOne(bounds(state).difference());
    }

    @Override
    public List<? extends Distribution> choices(int state, List<Distribution> distributions) {
        return distributions;
    }

    @Override
    public ToDoubleFunction<Distribution> score(
            int state, List<Distribution> distributions, List<? extends Distribution> choices) {
        return update == Optimization.MIN_VALUE
                ? d -> -d.sumWeightedExceptJacobi(this::lowerBound, state)
                : d -> d.sumWeightedExceptJacobi(this::upperBound, state);
    }

    @Override
    public Bounds update(
            int state, List<Distribution> distributions, List<? extends Distribution> choices, Distribution selected) {
        return update(state, distributions);
    }

    public Bounds update(int state, List<? extends Distribution> distributions) {
        assert update != Optimization.UNIQUE_VALUE || distributions.size() == 1;

        Bounds stateBounds = bounds(state);
        if (isOne(stateBounds.lowerBound()) || isZero(stateBounds.upperBound())) {
            return stateBounds;
        }
        assert !goal.test(state);

        List<Bounds> availableBounds =
                Lists.transform(distributions, d -> d.sumWeightedExceptJacobiBounds(this::bounds, state)
                        .orElseGet(Bounds::zero)); // default Bounds::zero
        Bounds newBounds = update.select(availableBounds);
        Bounds oldBounds = bounds.put(state, newBounds);
        assert oldBounds == null || oldBounds.contains(newBounds, Util.WEAK_EPS);
        return newBounds;
    }

    @Nullable
    @Override
    public Distribution successors(int state, List<Distribution> distributions, Distribution distribution) {
        return distribution;
    }

    @Override
    public void collapse(int representative, List<Distribution> distributions, Component collapsed) {
        bounds.keySet().removeAll(collapsed.states());
        if (collapsed.stateStream().anyMatch(goal)) {
            bounds.put(representative, Bounds.one());
        } else {
            update(representative, distributions);
        }
    }
}

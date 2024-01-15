package de.tum.in.pet.implementation.core;

import static de.tum.in.probmodels.util.Util.isOne;
import static de.tum.in.probmodels.util.Util.lessOrEqual;

import de.tum.in.naturals.map.Nat2DoubleDenseArrayMap;
import de.tum.in.pet.analyser.CollapsingValues;
import de.tum.in.probmodels.graph.Component;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import java.util.List;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nullable;

class UnboundedCoreValues implements CollapsingValues<Distribution> {
    private final Int2DoubleMap map;
    private final boolean dense;

    UnboundedCoreValues() {
        this(true);
    }

    UnboundedCoreValues(boolean dense) {
        map = dense ? new Nat2DoubleDenseArrayMap(1024) : new Int2DoubleOpenHashMap(1024);
        map.defaultReturnValue(1.0d);
        this.dense = dense;
    }

    @Override
    public Bounds bounds(int state) {
        return Bounds.reach(0.0, upperBound(state));
    }

    @Override
    public double upperBound(int state) {
        return map.get(state);
    }

    @Override
    public double difference(int state) {
        return upperBound(state);
    }

    @Override
    public double lowerBound(int state) {
        return 0.0;
    }

    @Override
    public boolean isUnknown(int state) {
        return isOne(upperBound(state));
    }

    @Override
    public List<Distribution> choices(int state, List<Distribution> distributions) {
        return distributions;
    }

    @Override
    public ToDoubleFunction<Distribution> score(
            int state, List<Distribution> distributions, List<? extends Distribution> choices) {
        return d -> d.sumWeightedExceptJacobi(this::upperBound, state);
    }

    @Nullable
    @Override
    public Distribution successors(int state, List<Distribution> distributions, Distribution distribution) {
        return distribution;
    }

    @Override
    public Bounds update(
            int state, List<Distribution> distributions, List<? extends Distribution> choices, Distribution selected) {
        return update(state, distributions);
    }

    public Bounds update(int state, List<? extends Distribution> distributions) {
        if (distributions.size() == 1) {
            Distribution distribution = distributions.get(0);
            double value = distribution.sumWeightedExceptJacobi(map, state);
            double newValue = Double.isNaN(value) ? 0.0 : value;
            update(state, newValue);
            return Bounds.reach(0.0, newValue);
        }
        double maximalValue = 0.0d;
        for (Distribution distribution : distributions) {
            if (distribution.isOnlySuccessor(state)) {
                continue;
            }
            double expectedValue = distribution.sumWeighted(map); // , state);
            if (expectedValue > maximalValue) {
                maximalValue = expectedValue;
            }
            if (isOne(expectedValue)) {
                break;
            }
        }
        update(state, maximalValue);
        return Bounds.reach(0.0, maximalValue);
    }

    void update(int state, double value) {
        if (isOne(value)) {
            return;
        }
        double oldValue = map.put(state, value);
        assert lessOrEqual(value, oldValue);
    }

    @Override
    public void collapse(int representative, List<Distribution> distributions, Component collapsed) {
        if (!dense) {
            map.keySet().removeAll(collapsed.states());
        }
        update(representative, distributions);
    }

    @Override
    public String toString() {
        return "UnboundedValues";
    }
}

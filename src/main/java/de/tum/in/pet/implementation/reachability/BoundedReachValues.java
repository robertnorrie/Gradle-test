package de.tum.in.pet.implementation.reachability;

import static de.tum.in.probmodels.util.Util.isOne;
import static de.tum.in.probmodels.util.Util.isZero;

import com.google.common.collect.Lists;
import de.tum.in.pet.analyser.PrefixValues;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.problem.property.ReachType;
import de.tum.in.probmodels.problem.query.Optimization;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;

public class BoundedReachValues implements PrefixValues {
    private final Int2ObjectMap<Bounds[]> bounds = new Int2ObjectOpenHashMap<>();
    private final IntFunction<ReachType> target;
    private final Optimization update;
    private final boolean monotonicity;

    public BoundedReachValues(IntFunction<ReachType> target, Optimization update, boolean monotonicity) {
        this.target = target;
        this.update = update;
        this.monotonicity = monotonicity;
    }

    @Override
    public Bounds bounds(int state, int remaining) {
        assert 0 <= remaining;
        return switch (target.apply(state)) {
            case GOAL -> Bounds.one();
            case SINK -> Bounds.zero();
            case UNKNOWN -> {
                if (remaining == 0) {
                    yield Bounds.zero();
                }
                int index = remaining - 1;
                Bounds[] values = bounds.get(state);
                yield values == null || values.length <= index ? Bounds.unknownReach() : values[index];
            }
        };
    }

    @Override
    public ToDoubleFunction<Choice> score(int state, int remaining, List<Choice> choices) {
        return update == Optimization.MIN_VALUE
                ? choice -> -choice.distribution().sumWeighted(s -> lowerBound(s, remaining - 1))
                : choice -> choice.distribution().sumWeighted(s -> upperBound(s, remaining - 1));
    }

    @Override
    public Bounds update(int state, int remaining, List<Choice> choices, Choice selected) {
        assert remaining > 0;
        assert update != Optimization.UNIQUE_VALUE || choices.size() == 1;

        Bounds oldBounds = bounds(state, remaining);
        if (isOne(oldBounds.lowerBound()) || isZero(oldBounds.upperBound())) {
            return oldBounds;
        }
        assert target.apply(state) == ReachType.UNKNOWN;

        List<Bounds> availableBounds = Lists.transform(
                choices, choice -> choice.distribution().sumWeightedBounds(s -> bounds(s, remaining - 1)));
        Bounds bounds = update.select(availableBounds);
        update(state, remaining, bounds);
        return bounds;
    }

    public void update(int state, int remaining, Bounds bounds) {
        assert remaining > 0;
        if (target.apply(state) != ReachType.UNKNOWN) {
            return;
        }

        int index = remaining - 1;
        Bounds[] values = this.bounds.get(state);

        if (values == null) {
            Bounds[] newValues = new Bounds[index + 1];
            if (monotonicity) {
                Arrays.fill(newValues, 0, index, Bounds.of(0.0, bounds.upperBound()));
            } else {
                Arrays.fill(newValues, 0, index, Bounds.unknownReach());
            }

            newValues[index] = bounds;
            this.bounds.put(state, newValues);
        } else {
            if (values.length <= index) {
                int oldLength = values.length;
                int newLength = Math.max(oldLength * 2, index + 1);
                values = Arrays.copyOf(values, newLength);
                if (monotonicity) {
                    for (int i = oldLength - 1; i > 0; i--) {
                        Bounds b = values[i];
                        if (b.upperBound() > bounds.upperBound()) {
                            values[i] = b.withUpper(bounds.upperBound());
                        } else {
                            break;
                        }
                    }
                    double previousLowerBound = values[oldLength - 1].lowerBound();
                    Arrays.fill(values, oldLength, index, Bounds.of(previousLowerBound, bounds.upperBound()));
                    values[index] = bounds;
                    Arrays.fill(values, index + 1, newLength, Bounds.of(bounds.lowerBound(), 1.0d));
                } else {
                    Arrays.fill(values, oldLength, index, Bounds.unknownReach());
                    values[index] = bounds;
                    Arrays.fill(values, index + 1, newLength, Bounds.unknownReach());
                }
                this.bounds.put(state, values);
            } else {
                values[index] = bounds;
                if (monotonicity) {
                    for (int i = index - 1; i > 0; i--) {
                        Bounds b = values[i];
                        if (b.upperBound() > bounds.upperBound()) {
                            values[i] = b.withUpper(bounds.upperBound());
                        } else {
                            break;
                        }
                    }
                    for (int i = index + 1; i < values.length; i++) {
                        Bounds b = values[i];
                        if (b.lowerBound() < bounds.lowerBound()) {
                            values[i] = b.withLower(bounds.lowerBound());
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        assert Arrays.stream(this.bounds.get(state)).allMatch(Objects::nonNull);
        assert bounds(state, remaining).equals(bounds);
    }
}

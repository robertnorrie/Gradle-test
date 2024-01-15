package de.tum.in.pet.implementation.meanpayoff;

import com.google.common.collect.Lists;
import de.tum.in.pet.analyser.CollapsingValues;
import de.tum.in.probmodels.explorer.RewardExplorer;
import de.tum.in.probmodels.graph.Component;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.problem.query.Optimization;
import de.tum.in.probmodels.util.JoinList;
import de.tum.in.probmodels.util.Util;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class MeanPayoffValues implements CollapsingValues<Object> {
    private final Int2ObjectMap<Bounds> collapsedBounds = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<ComponentIterator> componentIterators = new Int2ObjectOpenHashMap<>();

    private final Optimization update;
    private final Bounds rewardBounds;
    private final RewardExplorer<?> rewards;

    public MeanPayoffValues(Optimization update, Bounds rewardBounds, RewardExplorer<?> rewards) {
        this.update = update;
        this.rewardBounds = rewardBounds;
        this.rewards = rewards;
    }

    @Override
    public Bounds bounds(int state) {
        return collapsedBounds.getOrDefault(state, rewardBounds);
    }

    @Override
    public boolean isUnknown(int state) {
        return bounds(state).equalsUpTo(rewardBounds);
    }

    @Override
    public List<?> choices(int state, List<Distribution> distributions) {
        ComponentIterator componentIterator = componentIterators.get(state);
        return componentIterator == null ? distributions : new JoinList<>(distributions, componentIterator);
    }

    @Nullable
    @Override
    public Distribution successors(int state, List<Distribution> distributions, Object choice) {
        return choice instanceof Distribution distribution ? distribution : null;
    }

    @Override
    public ToDoubleFunction<Object> score(int state, List<Distribution> distributions, List<?> choices) {
        if (update == Optimization.MAX_VALUE) {
            return object -> object instanceof Distribution distribution
                    ? distribution.sumWeightedExceptJacobi(this::upperBound, state)
                    : ((ComponentIterator) object).currentBounds().upperBound();
        }
        return object -> object instanceof Distribution distribution
                ? -distribution.sumWeightedExceptJacobi(this::lowerBound, state)
                : -((ComponentIterator) object).currentBounds().lowerBound();
    }

    @Override
    public Bounds update(int state, List<Distribution> distributions, List<?> choices, Object selected) {
        assert update != Optimization.UNIQUE_VALUE || choices.size() == 1;
        assert selected instanceof ComponentIterator || selected instanceof Distribution;

        @Nullable Bounds stayBounds;
        if (selected instanceof ComponentIterator iterator) {
            stayBounds = iterator.update();
        } else if (choices.get(choices.size() - 1) instanceof ComponentIterator iterator) {
            stayBounds = iterator.currentBounds();
        } else {
            assert choices.stream().allMatch(Distribution.class::isInstance) && !componentIterators.containsKey(state);
            stayBounds = null;
        }
        List<Bounds> availableBounds = Lists.transform(choices, obj -> {
            if (obj instanceof Distribution distribution) {
                return distribution
                        .sumWeightedExceptJacobiBounds(this::bounds, state)
                        .orElseGet(() -> stayBounds == null ? bounds(state) : stayBounds);
            }
            return ((ComponentIterator) obj).currentBounds();
        });
        Bounds newBounds = update.select(availableBounds);
        collapsedBounds.put(state, newBounds);
        // Cannot ensure monotonicity of the bounds since finding new components can weaken bounds
        return newBounds;
    }

    @Override
    public void collapse(int representative, List<Distribution> distributions, Component collapsed) {
        ComponentIterator componentIterator;
        if (collapsed.size() == 1) {
            componentIterator = new SingletonComponentIterator(collapsed, rewards, this.update);
        } else {
            Set<ComponentIterator> iterators = collapsed
                    .stateStream()
                    .mapToObj(componentIterators::remove)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            @Nullable Int2DoubleMap initialRewards;
            Bounds bounds;
            if (iterators.isEmpty()) {
                initialRewards = null;
                bounds = rewardBounds;
            } else {
                if (iterators.size() == 1) {
                    ComponentIterator iterator = iterators.iterator().next();
                    initialRewards = iterator instanceof NontrivialComponentIterator nontrivial
                            ? nontrivial.totalRewardIteration
                            : null;
                    bounds = this.update == Optimization.MAX_VALUE
                            ? rewardBounds.withLower(iterator.currentBounds().lowerBound())
                            : rewardBounds.withUpper(iterator.currentBounds().upperBound());
                } else {
                    initialRewards =
                            new Int2DoubleOpenHashMap(collapsed.states().size());
                    for (ComponentIterator iterator : iterators) {
                        if (iterator instanceof NontrivialComponentIterator nontrivial) {
                            double minimum = nontrivial
                                    .totalRewardIteration
                                    .values()
                                    .doubleStream()
                                    .min()
                                    .orElse(0.0);
                            for (Int2DoubleMap.Entry entry : nontrivial.totalRewardIteration.int2DoubleEntrySet()) {
                                initialRewards.put(entry.getIntKey(), entry.getDoubleValue() - minimum);
                            }
                        }
                    }
                    Stream<Bounds> availableBounds = iterators.stream().map(ComponentIterator::currentBounds);
                    bounds = this.update == Optimization.MAX_VALUE
                            ? rewardBounds.withLower(availableBounds
                                    .mapToDouble(Bounds::lowerBound)
                                    .max()
                                    .orElseThrow())
                            : rewardBounds.withUpper(availableBounds
                                    .mapToDouble(Bounds::upperBound)
                                    .min()
                                    .orElseThrow());
                }
            }
            componentIterator =
                    new NontrivialComponentIterator(collapsed, rewards, this.update, bounds, initialRewards);
        }
        collapsedBounds.keySet().removeAll(collapsed.states());
        update(
                representative,
                distributions,
                new JoinList<>(distributions, componentIterator),
                distributions.iterator().next());
        componentIterators.put(representative, componentIterator);
    }

    private sealed interface ComponentIterator
            permits MeanPayoffValues.SingletonComponentIterator, MeanPayoffValues.NontrivialComponentIterator {
        Bounds update();

        Bounds currentBounds();
    }

    private static final class SingletonComponentIterator implements ComponentIterator {
        private final Bounds bounds;

        SingletonComponentIterator(Component component, RewardExplorer<?> rewards, Optimization optimization) {
            assert component.size() == 1;
            int state = component.states().intStream().iterator().nextInt();

            double value;
            if (optimization == Optimization.MAX_VALUE) {
                double maximum = Double.NEGATIVE_INFINITY;
                for (Choice choice : component.choices(state)) {
                    double val = rewards.reward(state) + rewards.transitionReward(state, choice);
                    if (val > maximum) {
                        maximum = val;
                    }
                }
                value = maximum;
            } else {
                double minimum = Double.POSITIVE_INFINITY;
                for (Choice choice : component.choices(state)) {
                    double val = rewards.reward(state) + rewards.transitionReward(state, choice);
                    if (val < minimum) {
                        minimum = val;
                    }
                }
                value = minimum;
            }
            bounds = Bounds.of(value);
        }

        @Override
        public Bounds update() {
            return bounds;
        }

        @Override
        public Bounds currentBounds() {
            return bounds;
        }
    }

    private static final class NontrivialComponentIterator implements ComponentIterator {
        // TODO Adaptively choose
        private static final double alpha = 0.9;

        private final Component component;
        private final RewardExplorer<?> rewards;
        private final Optimization optimization;
        private final Int2DoubleMap totalRewardIteration;
        private final Int2DoubleMap totalRewardIterationNext;
        private Bounds currentBounds;
        private int iterationBound;
        private boolean boundsConvergedPastInitial = false;

        NontrivialComponentIterator(
                Component component,
                RewardExplorer<?> rewards,
                Optimization optimization,
                Bounds rewardBounds,
                @Nullable Int2DoubleMap initialRewards) {
            this.component = component;
            this.rewards = rewards;
            this.optimization = optimization;
            if (initialRewards == null || initialRewards.isEmpty()) {
                totalRewardIteration = new Int2DoubleOpenHashMap(component.size());
            } else {
                totalRewardIteration = initialRewards;
            }
            totalRewardIterationNext = new Int2DoubleOpenHashMap(component.size());
            iterationBound = (component.size() / 2 + 1);
            this.currentBounds = rewardBounds; // NOPMD
        }

        private Iteration update(int iterationBound, double targetPrecision) {
            Int2DoubleMap currentValues = totalRewardIteration;
            Int2DoubleMap nextValues = totalRewardIterationNext;

            int iterationCount = 0;
            Bounds currentBounds = this.currentBounds;
            //noinspection ObjectEquality
            while (currentValues != totalRewardIteration // NOPMD
                    || iterationCount < iterationBound && currentBounds.difference() >= targetPrecision) {
                IntIterator stateIterator = component.states().iterator();
                double minimalDifference = Double.POSITIVE_INFINITY;
                double maximalDifference = Double.NEGATIVE_INFINITY;
                while (stateIterator.hasNext()) {
                    int state = stateIterator.nextInt();
                    double currentValue = currentValues.get(state);
                    double stateValue = rewards.reward(state);

                    double optimum = optimization == Optimization.MAX_VALUE
                            ? Double.NEGATIVE_INFINITY
                            : Double.POSITIVE_INFINITY;
                    for (Choice choice : component.choices(state)) {
                        double val = choice.distribution().sumWeighted(currentValues)
                                + rewards.transitionReward(state, choice);
                        if (optimization == Optimization.MAX_VALUE) {
                            if (val > optimum) {
                                optimum = val;
                            }
                        } else {
                            if (val < optimum) {
                                optimum = val;
                            }
                        }
                    }
                    Util.KahanSum valueSum = new Util.KahanSum();
                    valueSum.add(stateValue);
                    valueSum.add(alpha * optimum);
                    valueSum.add((1 - alpha) * currentValue);

                    double nextValue = valueSum.get();
                    nextValues.put(state, nextValue);
                    valueSum.add(-currentValue);
                    double difference = valueSum.get();
                    if (difference < minimalDifference) {
                        minimalDifference = difference;
                    }
                    if (difference > maximalDifference) {
                        maximalDifference = difference;
                    }
                }
                Int2DoubleMap swap = nextValues;
                nextValues = currentValues;
                currentValues = swap;

                Bounds nextBounds = Bounds.of(minimalDifference, maximalDifference);
                if (boundsConvergedPastInitial) {
                    assert currentBounds.contains(nextBounds, Util.WEAK_EPS)
                            : "%s does not contain %s".formatted(currentBounds, nextBounds);
                    currentBounds = nextBounds;
                } else {
                    if (currentBounds.contains(nextBounds)) {
                        currentBounds = nextBounds;
                        boundsConvergedPastInitial = true;
                    } else {
                        currentBounds = nextBounds.shrink(currentBounds);
                    }
                }
                iterationCount += 1;
            }
            return new Iteration(currentBounds, iterationCount, targetPrecision);
        }

        @Override
        public Bounds update() {
            double targetPrecision = currentBounds.difference() / 2.0;
            Iteration iteration = update(this.iterationBound, targetPrecision);
            Bounds resultBounds = iteration.result();
            assert this.currentBounds.contains(resultBounds, Util.WEAK_EPS);
            this.currentBounds = resultBounds;
            if (iteration.iterations == iterationBound) {
                iterationBound += component.size();
            }
            return resultBounds;
        }

        @Override
        public Bounds currentBounds() {
            return currentBounds;
        }

        private record Iteration(Bounds result, int iterations, double precision) {}
    }
}

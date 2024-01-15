package de.tum.in.pet.implementation.core;

import static de.tum.in.probmodels.util.Util.isEqual;
import static de.tum.in.probmodels.util.Util.isOne;
import static de.tum.in.probmodels.util.Util.lessOrEqual;

import de.tum.in.pet.analyser.PrefixValues;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

abstract class BoundedCoreValues implements PrefixValues {
    @Override
    public abstract double upperBound(int state, int remaining);

    @Override
    public Bounds bounds(int state, int remaining) {
        return Bounds.reach(0.0d, upperBound(state, remaining));
    }

    @Override
    public double lowerBound(int state, int remaining) {
        return 0.0;
    }

    @Override
    public double difference(int state, int remaining) {
        return upperBound(state, remaining);
    }

    @Override
    public ToDoubleFunction<Choice> score(int state, int remaining, List<Choice> choices) {
        return choice -> choice.distribution().sumWeighted(s -> upperBound(s, remaining - 1));
    }

    @Override
    public Bounds update(int state, int remaining, List<Choice> choices, Choice selected) {
        double max = 0.0;
        for (Choice choice : choices) {
            if (choice.distribution().isOnlySuccessor(state)) {
                continue;
            }

            double v = choice.distribution().sumWeighted(s -> upperBound(s, remaining - 1));
            if (v > max) {
                max = v;
            }
        }
        update(state, remaining, max);
        return Bounds.reach(0.0, max);
    }

    abstract void update(int state, int remaining, double bound);

    public static class Simple extends BoundedCoreValues {
        private static final int ONE_STEP_THRESHOLD = 6;

        private final Int2ObjectMap<double[]> stateBounds;
        private final int approximationWidth;

        Simple(int approximationWidth) {
            this.stateBounds = new Int2ObjectOpenHashMap<>();
            this.approximationWidth = approximationWidth;
        }

        @Override
        public double upperBound(int state, int remaining) {
            if (remaining == 0) {
                return 0.0d;
            }
            double[] values = stateBounds.get(state);
            if (values == null) {
                return 1.0d;
            }
            int offset = offset(remaining);
            return offset < values.length ? values[offset] : 1.0d;
        }

        @Override
        public void update(int state, int remaining, double value) {
            if (!isApproximationPoint(remaining)) {
                return;
            }
            int offset = offset(remaining);

            double[] values = stateBounds.get(state);
            if (values == null) {
                double[] newValues = new double[offset + 1];
                Arrays.fill(newValues, value);
                stateBounds.put(state, newValues);
            } else if (values.length <= offset) {
                int oldLength = values.length;
                int newLength = Math.max(oldLength * 2, offset + 1);
                double[] newValues = Arrays.copyOf(values, newLength);

                for (int i = 0; i < oldLength; i++) {
                    if (newValues[i] > value) {
                        newValues[i] = value;
                    }
                }
                Arrays.fill(newValues, oldLength, offset + 1, value);
                Arrays.fill(newValues, offset + 1, newLength, 1.0d);
                stateBounds.put(state, newValues);
            } else {
                double oldValue = values[offset];

                if (oldValue <= value) {
                    return;
                }

                // Maintain monotonicity
                for (int i = 0; i < offset; i++) {
                    if (values[i] > value) {
                        values[i] = value;
                    }
                }
                values[offset] = value;
            }
        }

        private int offset(int remaining) {
            assert remaining > 0;
            if (remaining < ONE_STEP_THRESHOLD) {
                return remaining - 1;
            }
            int steps = remaining - ONE_STEP_THRESHOLD;
            return steps / approximationWidth + ONE_STEP_THRESHOLD - 1;
        }

        private boolean isApproximationPoint(int remaining) {
            boolean value =
                    remaining < ONE_STEP_THRESHOLD || ((remaining - ONE_STEP_THRESHOLD + 1) % approximationWidth == 0);
            assert value == (offset(remaining) + 1 == offset(remaining + 1));
            return value;
        }

        @Override
        public String toString() {
            return String.format("SimpleValues(%d)", approximationWidth);
        }
    }

    public static class Dense extends BoundedCoreValues {
        private final Int2ObjectMap<double[]> stateBounds = new Int2ObjectOpenHashMap<>();

        @Override
        public double upperBound(int state, int remaining) {
            if (remaining == 0) {
                return 0.0d;
            }
            int index = remaining - 1;
            double[] values = stateBounds.get(state);
            return values == null || values.length <= index ? 1.0d : values[index];
        }

        @Override
        void update(int state, int remaining, double value) {
            if (isOne(value)) {
                assert isOne(upperBound(state, remaining));
                return;
            }

            int index = remaining - 1;
            int monotonicityUpdate;
            double[] values = stateBounds.get(state);
            if (values == null) {
                values = new double[index + 1];
                Arrays.fill(values, value);
                monotonicityUpdate = 0;

                stateBounds.put(state, values);
            } else if (values.length <= index) {
                int oldLength = values.length;
                int newLength = Math.max(oldLength * 2, index + 1);
                values = Arrays.copyOf(values, newLength);
                Arrays.fill(values, oldLength, index + 1, value);
                Arrays.fill(values, index + 1, newLength, 1.0d);
                monotonicityUpdate = oldLength;

                stateBounds.put(state, values);
            } else {
                double oldValue = values[index];
                // Check monotonicity of added value
                assert lessOrEqual(value, oldValue) : "Updating %f to %f".formatted(oldValue, value);
                if (isEqual(value, oldValue)) {
                    return;
                }
                values[index] = value;
                monotonicityUpdate = index;
            }

            for (int i = monotonicityUpdate - 1; i >= 0; i--) {
                double v = values[i];
                if (v > value) {
                    values[i] = value;
                } else {
                    break;
                }
            }
        }

        @Override
        public String toString() {
            return "DenseValues";
        }
    }
}

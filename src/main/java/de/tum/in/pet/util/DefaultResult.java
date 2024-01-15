package de.tum.in.pet.util;

import java.util.Map;

public record DefaultResult<S>(String name, Object statistics, Map<S, ?> values) {}

package net.octyl.beatdropper.droppers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

public class BeatDropperFactories {

    private static final ImmutableMap<String, BeatDropperFactory> byId;

    static {
        byId = Streams.stream(ServiceLoader.load(BeatDropperFactory.class))
                .sorted(Comparator.comparing(BeatDropperFactory::getId))
                .collect(toImmutableMap(BeatDropperFactory::getId, Function.identity()));
    }

    public static BeatDropperFactory getById(String id) {
        BeatDropperFactory factory = byId.get(id);
        checkArgument(factory != null, "No factory by the ID '%s'", id);
        return factory;
    }

    public static String formatAvailableForCli() {
        return byId.keySet().stream()
                .collect(Collectors.joining("\n\t", "\t", ""));
    }

}

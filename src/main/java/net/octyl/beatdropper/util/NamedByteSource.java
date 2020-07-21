package net.octyl.beatdropper.util;

import com.google.auto.value.AutoValue;
import com.google.common.io.ByteSource;

@AutoValue
public abstract class NamedByteSource {

    public static NamedByteSource of(String name, ByteSource source) {
        return new AutoValue_NamedByteSource(name, source);
    }

    NamedByteSource() {
    }

    public abstract String getName();

    public abstract ByteSource getSource();

}

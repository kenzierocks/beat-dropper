package net.octyl.beatdropper.util;

import java.io.OutputStream;

import com.google.common.io.ByteSink;
import com.google.common.io.MoreFiles;
import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class ByteSinkConverter implements ValueConverter<ByteSink> {

    private final ByteSink standardOutSource = new ByteSink() {
        @Override
        public OutputStream openStream() {
            return System.out;
        }
    };
    private final PathConverter delegate = new PathConverter();

    @Override
    public ByteSink convert(String value) {
        if (value.equals("-")) {
            return standardOutSource;
        }
        return MoreFiles.asByteSink(delegate.convert(value));
    }

    @Override
    public Class<? extends ByteSink> valueType() {
        return ByteSink.class;
    }

    @Override
    public String valuePattern() {
        return "A path or `-` for stdout";
    }
}

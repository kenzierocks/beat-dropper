package net.octyl.beatdropper.util;

import java.io.InputStream;
import java.nio.file.Path;

import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import joptsimple.ValueConverter;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;

public class ByteSourceConverter implements ValueConverter<NamedByteSource> {

    private final NamedByteSource standardInSource = NamedByteSource.of("stream:stdin", new ByteSource() {
        @Override
        public InputStream openStream() {
            return System.in;
        }
    });
    private final PathConverter delegate = new PathConverter(PathProperties.READABLE);

    @Override
    public NamedByteSource convert(String value) {
        if (value.equals("-")) {
            return standardInSource;
        }
        Path path = delegate.convert(value);
        return NamedByteSource.of("file:" + path.toAbsolutePath().toString(), MoreFiles.asByteSource(path));
    }

    @Override
    public Class<? extends NamedByteSource> valueType() {
        return NamedByteSource.class;
    }

    @Override
    public String valuePattern() {
        return "A path or `-` for stdin";
    }
}

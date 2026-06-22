package org.lolobored.plexdownloader.transcode;

public interface MediaProbe {
    MediaInfo probe(String sourcePath);
}

package org.lolobored.plexdownloader.transcode;

import java.util.List;
import java.util.function.Consumer;

public interface ProcessRunner {
    RunningTranscode start(List<String> command,
                           Consumer<String> stdoutLineSink,
                           Consumer<String> stderrLineSink);
}

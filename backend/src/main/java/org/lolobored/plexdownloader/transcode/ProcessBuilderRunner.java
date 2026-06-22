package org.lolobored.plexdownloader.transcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
public class ProcessBuilderRunner implements ProcessRunner {

    @Override
    public RunningTranscode start(List<String> command,
                                  Consumer<String> stdoutLineSink,
                                  Consumer<String> stderrLineSink) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start: " + String.join(" ", command), e);
        }
        Thread outThread = pump(process.getInputStream(), stdoutLineSink, "ffmpeg-out");
        Thread errThread = pump(process.getErrorStream(), stderrLineSink, "ffmpeg-err");

        return new RunningTranscode() {
            @Override public int waitForExit() throws InterruptedException {
                int code = process.waitFor();
                outThread.join();
                errThread.join();
                return code;
            }
            @Override public void cancel() { process.destroyForcibly(); }
        };
    }

    private Thread pump(InputStream in, Consumer<String> sink, String threadName) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sink.accept(line);
            } catch (IOException e) {
                log.debug("{} reader closed: {}", threadName, e.getMessage());
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }
}

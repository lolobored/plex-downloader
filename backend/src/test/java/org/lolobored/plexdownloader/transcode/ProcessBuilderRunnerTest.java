package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBuilderRunnerTest {

    private final ProcessBuilderRunner runner = new ProcessBuilderRunner();

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void capturesStdoutAndExitCode() throws InterruptedException {
        List<String> out = new CopyOnWriteArrayList<>();
        RunningTranscode rt = runner.start(
            List.of("sh", "-c", "echo hello; echo world; exit 0"),
            out::add, line -> {});
        int code = rt.waitForExit();
        assertThat(code).isZero();
        assertThat(out).contains("hello", "world");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonZeroExitPropagates() throws InterruptedException {
        RunningTranscode rt = runner.start(List.of("sh", "-c", "exit 3"), l -> {}, l -> {});
        assertThat(rt.waitForExit()).isEqualTo(3);
    }
}

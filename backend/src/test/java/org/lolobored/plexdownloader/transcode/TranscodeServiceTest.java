package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscodeServiceTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock MediaProbe mediaProbe;
    @Mock ProcessRunner processRunner;
    @Spy FfmpegCommandBuilder commandBuilder = new FfmpegCommandBuilder();
    @Spy ProgressParser progressParser = new ProgressParser();
    @InjectMocks TranscodeService service;

    private DownloadQueueItem item(Long id, String dest) {
        QualityProfile p = new QualityProfile();
        p.setCodec(QualityProfile.Codec.HEVC_QSV);
        p.setContainer(QualityProfile.Container.MKV);
        p.setResolutionCap(QualityProfile.ResolutionCap.KEEP);
        p.setAudioMode(QualityProfile.AudioMode.COPY);
        DownloadQueueItem i = new DownloadQueueItem();
        i.setId(id); i.setSourceFilePath("/movies/x.avi"); i.setDestFilePath(dest);
        i.setQualityProfile(p);
        i.setStatus(DownloadQueueItem.Status.QUEUED);
        return i;
    }

    @Test
    void success_setsDoneAndFullProgress(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("x.mkv");
        DownloadQueueItem it = item(1L, dest.toString());
        when(queueRepo.findById(1L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe("/movies/x.avi")).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=30000000"); // 50%
            out.accept("progress=end");
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service.transcode(1L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getProgressPercent()).isEqualTo(100);
        assertThat(it.getCompletedAt()).isNotNull();
    }

    @Test
    void failure_setsErrorAndDeletesPartialOutput(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("y.mkv");
        Files.writeString(dest, "partial");
        DownloadQueueItem it = item(2L, dest.toString());
        when(queueRepo.findById(2L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> err = inv.getArgument(2);
            err.accept("Error: bad frame");
            return new RunningTranscode() {
                public int waitForExit() { return 1; }
                public void cancel() {}
            };
        });

        service.transcode(2L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("bad frame");
        assertThat(dest).doesNotExist();
    }

    @Test
    void cancel_unknownItem_returnsFalse() {
        assertThat(service.cancel(999L)).isFalse();
    }

    @Test
    void cancel_registeredItem_returnsTrue(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("z.mkv");
        DownloadQueueItem it = item(3L, dest.toString());
        when(queueRepo.findById(3L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));

        CountDownLatch blockWait = new CountDownLatch(1);
        AtomicBoolean cancelCalled = new AtomicBoolean(false);

        when(processRunner.start(anyList(), any(), any())).thenReturn(new RunningTranscode() {
            public int waitForExit() throws InterruptedException {
                blockWait.await();
                return 0;
            }
            public void cancel() {
                cancelCalled.set(true);
                blockWait.countDown();
            }
        });

        Thread transcodeThread = new Thread(() -> service.transcode(3L));
        transcodeThread.start();

        // Poll until the item is registered (max 2 s)
        long deadline = System.currentTimeMillis() + 2_000;
        while (service.cancel(3L) == false) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Transcode not registered within 2 s");
            }
            Thread.sleep(10);
        }

        assertThat(cancelCalled.get()).isTrue();
        transcodeThread.join(2_000);
        assertThat(transcodeThread.isAlive()).isFalse();
    }
}

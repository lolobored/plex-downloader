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
import java.util.ArrayList;
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

    // We create service manually to inject TranscodeConfig with custom tempDir
    private TranscodeService serviceWithTempDir(String tempDir) {
        TranscodeConfig cfg = new TranscodeConfig("ffmpeg", "ffprobe", "/dev/dri/renderD128", tempDir);
        return new TranscodeService(queueRepo, mediaProbe,
            new FfmpegCommandBuilder(cfg), new ProgressParser(), processRunner, cfg);
    }

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
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("x.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(1L, dest.toString());
        when(queueRepo.findByIdWithProfile(1L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(1L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe("/movies/x.avi")).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=30000000"); // 50%
            out.accept("progress=end");
            // ffmpeg writes to the temp file — simulate it
            Path tempFile = tempBase.resolve("plex-downloader/1/x.mkv");
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[100]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(1L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getProgressPercent()).isEqualTo(100);
        assertThat(it.getCompletedAt()).isNotNull();
        // Final dest file exists
        assertThat(dest).exists();
        // Temp dir cleaned up
        assertThat(tempBase.resolve("plex-downloader/1")).doesNotExist();
    }

    @Test
    void success_passesThroughCopyingStatusBeforeDone(@TempDir Path tmp) throws Exception {
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("x.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(2L, dest.toString());
        when(queueRepo.findByIdWithProfile(2L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(2L)).thenReturn(Optional.of(it));

        List<DownloadQueueItem.Status> savedStatuses = new ArrayList<>();
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem saved = inv.getArgument(0);
            savedStatuses.add(saved.getStatus());
            return saved;
        });
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Path tempFile = tempBase.resolve("plex-downloader/2/x.mkv");
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[100]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(2L);

        // Status sequence must include COPYING before DONE
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.COPYING);
        int copyingIdx = savedStatuses.lastIndexOf(DownloadQueueItem.Status.COPYING);
        int doneIdx    = savedStatuses.lastIndexOf(DownloadQueueItem.Status.DONE);
        assertThat(copyingIdx).isLessThan(doneIdx);
    }

    @Test
    void success_computesCompressionRatioFromFileSizes(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src.avi");
        Files.write(src, new byte[1000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("out.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(5L, dest.toString());
        it.setSourceFilePath(src.toString());
        when(queueRepo.findByIdWithProfile(5L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(5L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Path tempFile = tempBase.resolve("plex-downloader/5/out.mkv");
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[400]); // 60% smaller than source
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(5L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getCompressionRatio()).isEqualTo(60.0);
        assertThat(dest).exists();
    }

    @Test
    void failure_setsErrorAndDeletesTempFile(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("dest/y.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(3L, dest.toString());
        when(queueRepo.findByIdWithProfile(3L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(3L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> err = inv.getArgument(2);
            err.accept("Error: bad frame");
            // Write partial temp file
            Path tempFile = tempBase.resolve("plex-downloader/3/y.mkv");
            Files.createDirectories(tempFile.getParent());
            Files.writeString(tempFile, "partial");
            return new RunningTranscode() {
                public int waitForExit() { return 1; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(3L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("bad frame");
        // Dest was never created
        assertThat(dest).doesNotExist();
        // Temp dir cleaned up
        assertThat(tempBase.resolve("plex-downloader/3")).doesNotExist();
    }

    @Test
    void cancel_unknownItem_returnsFalse() {
        TranscodeConfig cfg = new TranscodeConfig("ffmpeg", "ffprobe", "/dev/dri/renderD128", "/tmp");
        TranscodeService service = new TranscodeService(queueRepo, mediaProbe,
            new FfmpegCommandBuilder(cfg), new ProgressParser(), processRunner, cfg);
        assertThat(service.cancel(999L)).isFalse();
    }

    @Test
    void cancel_registeredItem_returnsTrue(@TempDir Path tmp) throws Exception {
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("z.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(4L, dest.toString());
        when(queueRepo.findByIdWithProfile(4L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(4L)).thenReturn(Optional.of(it));
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

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        Thread transcodeThread = new Thread(() -> service.transcode(4L));
        transcodeThread.start();

        // Poll until the item is registered (max 2 s)
        long deadline = System.currentTimeMillis() + 2_000;
        while (service.cancel(4L) == false) {
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

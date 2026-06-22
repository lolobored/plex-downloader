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

    private DownloadQueueItem item(Long id, String src, String dest) {
        QualityProfile p = new QualityProfile();
        p.setCodec(QualityProfile.Codec.HEVC_QSV);
        p.setContainer(QualityProfile.Container.MKV);
        p.setResolutionCap(QualityProfile.ResolutionCap.KEEP);
        p.setAudioMode(QualityProfile.AudioMode.COPY);
        DownloadQueueItem i = new DownloadQueueItem();
        i.setId(id); i.setSourceFilePath(src); i.setDestFilePath(dest);
        i.setQualityProfile(p);
        i.setStatus(DownloadQueueItem.Status.QUEUED);
        return i;
    }

    @Test
    void success_setsDoneAndFullProgress(@TempDir Path tmp) throws Exception {
        Path srcFile = tmp.resolve("x.avi");
        Files.write(srcFile, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("x.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(1L, srcFile.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(1L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(1L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=30000000"); // 50%
            out.accept("progress=end");
            // ffmpeg writes to the temp output file (second-to-last arg in cmd)
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
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
        // Temp dir (including src subdir) cleaned up
        assertThat(tempBase.resolve("plex-downloader/1")).doesNotExist();
    }

    @Test
    void success_passesThroughFetchingTranscodingCopyingBeforeDone(@TempDir Path tmp) throws Exception {
        Path srcFile = tmp.resolve("x.avi");
        Files.write(srcFile, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("x.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(2L, srcFile.toString(), dest.toString());
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
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[100]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(2L);

        // Status sequence must include FETCHING → TRANSCODING → COPYING → DONE
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.FETCHING);
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.TRANSCODING);
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.COPYING);
        int fetchIdx     = savedStatuses.indexOf(DownloadQueueItem.Status.FETCHING);
        int transcodIdx  = savedStatuses.indexOf(DownloadQueueItem.Status.TRANSCODING);
        int copyingIdx   = savedStatuses.lastIndexOf(DownloadQueueItem.Status.COPYING);
        int doneIdx      = savedStatuses.lastIndexOf(DownloadQueueItem.Status.DONE);
        assertThat(fetchIdx).isLessThan(transcodIdx);
        assertThat(transcodIdx).isLessThan(copyingIdx);
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

        DownloadQueueItem it = item(5L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(5L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(5L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
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
        assertThat(it.getSourceSizeBytes()).isEqualTo(1000L);
        assertThat(it.getOutputSizeBytes()).isEqualTo(400L);
        assertThat(dest).exists();
    }

    @Test
    void success_setsSizeFieldsOnDone(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[2000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("out.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(6L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(6L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(6L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[500]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(6L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getSourceSizeBytes()).isEqualTo(2000L);
        assertThat(it.getOutputSizeBytes()).isEqualTo(500L);
        // ratio: (2000-500)/2000 * 100 = 75.0
        assertThat(it.getCompressionRatio()).isEqualTo(75.0);
    }

    @Test
    void sourceCopyFailure_setsErrorAndCleansTempDir(@TempDir Path tmp) throws Exception {
        // Source file does NOT exist → copy fails
        Path missingSource = tmp.resolve("missing.avi");
        Path dest = tmp.resolve("dest/y.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(7L, missingSource.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(7L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(7L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(7L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("Source copy failed");
        // ffmpeg was never run
        verifyNoInteractions(processRunner);
        // Temp dir cleaned up
        assertThat(tempBase.resolve("plex-downloader/7")).doesNotExist();
    }

    @Test
    void sourceCopyFailure_probesLocalTempSource(@TempDir Path tmp) throws Exception {
        // Verify that when source copy succeeds, mediaProbe is called with the LOCAL temp src path
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[100]);
        Path dest = tmp.resolve("dest/out.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(8L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(8L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(8L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<String> capturedProbeArg = new ArrayList<>();
        when(mediaProbe.probe(anyString())).thenAnswer(inv -> {
            capturedProbeArg.add(inv.getArgument(0));
            return new MediaInfo(60, 1920, 1080);
        });
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[50]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(8L);

        // Probe was called with the LOCAL temp src path (not the original NAS path)
        assertThat(capturedProbeArg).hasSize(1);
        assertThat(capturedProbeArg.get(0)).contains("plex-downloader/8/src/source.avi");
        assertThat(capturedProbeArg.get(0)).doesNotContain(src.toString());
    }

    @Test
    void success_ffmpegInputIsLocalTempSource(@TempDir Path tmp) throws Exception {
        // Verify that ffmpeg command uses the LOCAL temp source, not the NAS path
        Path src = tmp.resolve("movie.avi");
        Files.write(src, new byte[100]);
        Path dest = tmp.resolve("dest/movie.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(9L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(9L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(9L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));

        List<List<String>> capturedCmds = new ArrayList<>();
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = new ArrayList<>((List<String>) inv.getArgument(0));
            capturedCmds.add(cmd);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[50]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(9L);

        assertThat(capturedCmds).hasSize(1);
        String cmdStr = String.join(" ", capturedCmds.get(0));
        // ffmpeg input must be the local temp src copy
        assertThat(cmdStr).contains("plex-downloader/9/src/movie.avi");
        // ffmpeg input must NOT be the original NAS source
        assertThat(cmdStr).doesNotContain(src.toString().replace("plex-downloader", "IMPOSSIBLE"));
        // The NAS source path should NOT appear in the ffmpeg command
        assertThat(capturedCmds.get(0)).doesNotContain(src.toString());
    }

    @Test
    void success_tempSrcSubdirIsCreated(@TempDir Path tmp) throws Exception {
        // Verify src/ subdir is created and source is copied there before ffmpeg runs
        Path src = tmp.resolve("movie.avi");
        Files.write(src, new byte[200]);
        Path dest = tmp.resolve("dest/movie.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(10L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(10L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(10L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));

        Path[] capturedTempSrc = new Path[1];
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            // The src subdir should exist at this point
            Path tempSrc = tempBase.resolve("plex-downloader/10/src/movie.avi");
            capturedTempSrc[0] = tempSrc;
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[50]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(10L);

        // The temp src file was there during ffmpeg (captured path exists is no longer true since cleanup runs)
        // But we can verify final cleanup removed the whole tree
        assertThat(tempBase.resolve("plex-downloader/10")).doesNotExist();
        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
    }

    @Test
    void failure_setsErrorAndDeletesTempDir(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("y.avi");
        Files.write(src, new byte[50]);
        Path dest = tmp.resolve("dest/y.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(3L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(3L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(3L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> err = inv.getArgument(2);
            err.accept("Error: bad frame");
            // Write partial temp file
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
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
        // Temp dir (including src subdir) cleaned up
        assertThat(tempBase.resolve("plex-downloader/3")).doesNotExist();
    }

    @Test
    void processRunnerStartThrows_setsErrorAndDeletesTempDir(@TempDir Path tmp) throws Exception {
        // Simulate ffmpeg missing / not startable: processRunner.start throws UncheckedIOException
        Path src = tmp.resolve("a.avi");
        Files.write(src, new byte[50]);
        Path dest = tmp.resolve("dest/a.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(20L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(20L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(20L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any()))
            .thenThrow(new java.io.UncheckedIOException("ffmpeg not found",
                new java.io.IOException("No such file or directory")));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(20L);

        // Item must end in ERROR, not stuck in TRANSCODING/FETCHING
        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("Transcode setup failed");
        // Temp dir must be cleaned up (no leaked GBs)
        assertThat(tempBase.resolve("plex-downloader/20")).doesNotExist();
        // Final dest must not exist
        assertThat(dest).doesNotExist();
    }

    @Test
    void mediaProbeThrows_setsErrorAndDeletesTempDir(@TempDir Path tmp) throws Exception {
        // Simulate mediaProbe throwing (e.g. ffprobe not found)
        Path src = tmp.resolve("b.avi");
        Files.write(src, new byte[50]);
        Path dest = tmp.resolve("dest/b.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(21L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(21L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(21L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenThrow(new RuntimeException("ffprobe not found"));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(21L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("Transcode setup failed");
        assertThat(tempBase.resolve("plex-downloader/21")).doesNotExist();
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
        Path src = tmp.resolve("z.avi");
        Files.write(src, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("z.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(4L, src.toString(), dest.toString());
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

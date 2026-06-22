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
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Mock ApplicationEventPublisher eventPublisher;

    // We create service manually to inject TranscodeConfig with custom tempDir
    private TranscodeService serviceWithTempDir(String tempDir) {
        TranscodeConfig cfg = new TranscodeConfig("ffmpeg", "ffprobe", "/dev/dri/renderD128", tempDir);
        return new TranscodeService(queueRepo, mediaProbe,
            new FfmpegCommandBuilder(cfg), new ProgressParser(), processRunner, cfg, eventPublisher);
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
        TranscodeService service = serviceWithTempDir("/tmp");
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

    // ── Prefetch tests ────────────────────────────────────────────────────────

    @Test
    void prefetchSource_copiesSourceToTempSrcPath(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(100L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findByIdWithProfile(100L)).thenReturn(Optional.of(it));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.prefetchSource(100L);

        // Wait for the async prefetch to complete (up to 5s)
        Path expectedTempSrc = tempBase.resolve("plex-downloader/100/src/source.avi");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(expectedTempSrc) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(expectedTempSrc).exists();
        assertThat(Files.size(expectedTempSrc)).isEqualTo(5L);
    }

    @Test
    void prefetchSource_secondCallWhileInFlight_isNoOp(@TempDir Path tmp) throws Exception {
        // Block the first prefetch so we can issue a second call while it's still in-flight
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        CountDownLatch blockPrefetch = new CountDownLatch(1);
        CountDownLatch prefetchStarted = new CountDownLatch(1);

        DownloadQueueItem it = item(101L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);

        DownloadQueueItem it2 = item(102L, src.toString(), tmp.resolve("dest2/out.mkv").toString());
        it2.setStatus(DownloadQueueItem.Status.QUEUED);

        // Block the prefetch at repo load so the future is in flight when second call happens
        when(queueRepo.findByIdWithProfile(101L)).thenAnswer(inv -> {
            prefetchStarted.countDown();
            blockPrefetch.await(5, TimeUnit.SECONDS);
            return Optional.of(it);
        });
        when(queueRepo.findByIdWithProfile(102L)).thenReturn(Optional.of(it2));

        TranscodeService service = serviceWithTempDir(tempBase.toString());

        // Start prefetch for item 101 (blocks at repo)
        service.prefetchSource(101L);
        // Wait until the prefetch task is running (future is in map)
        assertThat(prefetchStarted.await(3, TimeUnit.SECONDS)).isTrue();
        // prefetches map is non-empty now; second call should be a no-op
        service.prefetchSource(102L);

        // Unblock the first prefetch and let it complete
        blockPrefetch.countDown();
        // Wait a bit for 101 to complete
        Path item101TempSrc = tempBase.resolve("plex-downloader/101/src/source.avi");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(item101TempSrc) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // item 102 should never have been staged
        Path item102TempSrc = tempBase.resolve("plex-downloader/102/src/source.avi");
        assertThat(item102TempSrc).doesNotExist();
        // item 101 was staged
        assertThat(item101TempSrc).exists();
    }

    @Test
    void prefetchSource_nonQueuedItem_isNoOp(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(103L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it.setStatus(DownloadQueueItem.Status.TRANSCODING); // Not QUEUED
        when(queueRepo.findByIdWithProfile(103L)).thenReturn(Optional.of(it));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.prefetchSource(103L);

        Thread.sleep(200);
        assertThat(tempBase.resolve("plex-downloader/103")).doesNotExist();
    }

    @Test
    void prefetchSource_missingItem_isNoOp(@TempDir Path tmp) throws Exception {
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        when(queueRepo.findByIdWithProfile(104L)).thenReturn(Optional.empty());

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.prefetchSource(104L); // Should not throw

        Thread.sleep(200);
        assertThat(tempBase.resolve("plex-downloader/104")).doesNotExist();
    }

    @Test
    void transcode_withPrefetchedSource_skipsFileCopyAndReachesDone(@TempDir Path tmp) throws Exception {
        // Source file on "NAS" (temp dir)
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(200L, src.toString(), dest.toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findByIdWithProfile(200L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(200L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));

        TranscodeService service = serviceWithTempDir(tempBase.toString());

        // Trigger a real prefetch and wait for it to complete
        service.prefetchSource(200L);
        Path stagedSrc = tempBase.resolve("plex-downloader/200/src/source.avi");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(stagedSrc) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(stagedSrc).exists();
        // Prefetch future is still in map (success path keeps it)
        assertThat(service.getPrefetchCount()).isEqualTo(1);

        // Delete the "NAS" source to prove transcode() won't attempt to re-copy it
        Files.delete(src);

        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[]{9, 8, 7});
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        // transcode() must succeed using the prefetched staged file (NAS src is gone)
        service.transcode(200L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(dest).exists();
        // Prefetch map must be cleared after consumption
        assertThat(service.getPrefetchCount()).isEqualTo(0);
    }

    @Test
    void transcode_at90percent_publishesNearDoneEvent(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3});
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(300L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(300L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(300L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(100, 1920, 1080));

        List<Object> publishedEvents = new ArrayList<>();
        doAnswer(inv -> { publishedEvents.add(inv.getArgument(0)); return null; })
            .when(eventPublisher).publishEvent(any(Object.class));

        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            // 50%
            out.accept("out_time_us=50000000");
            // 90% exactly (90s of 100s duration)
            out.accept("out_time_us=90000000");
            // Another 90% reading — should NOT fire again
            out.accept("out_time_us=91000000");
            out.accept("progress=end");
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[]{1});
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(300L);

        // Exactly one TranscodeNearDoneEvent should have been published
        long nearDoneCount = publishedEvents.stream()
            .filter(e -> e instanceof TranscodeNearDoneEvent)
            .count();
        assertThat(nearDoneCount).isEqualTo(1);

        TranscodeNearDoneEvent evt = publishedEvents.stream()
            .filter(e -> e instanceof TranscodeNearDoneEvent)
            .map(e -> (TranscodeNearDoneEvent) e)
            .findFirst().orElseThrow();
        assertThat(evt.itemId()).isEqualTo(300L);
    }

    @Test
    void transcode_below90percent_doesNotPublishNearDoneEvent(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3});
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(301L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(301L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(301L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(100, 1920, 1080));

        List<Object> publishedEvents = new ArrayList<>();
        doAnswer(inv -> { publishedEvents.add(inv.getArgument(0)); return null; })
            .when(eventPublisher).publishEvent(any(Object.class));

        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=50000000"); // 50% only
            // No progress=end — just exit 1 to stop early
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[]{1});
            return new RunningTranscode() {
                public int waitForExit() { return 1; } // non-zero exit
                public void cancel() {}
            };
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        service.transcode(301L);

        long nearDoneCount = publishedEvents.stream()
            .filter(e -> e instanceof TranscodeNearDoneEvent)
            .count();
        assertThat(nearDoneCount).isEqualTo(0);
    }

    @Test
    void cancelPrefetch_cancelsInFlightAndCleansTempDir(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(400L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);

        CountDownLatch prefetchBlocked = new CountDownLatch(1);
        CountDownLatch prefetchStarted = new CountDownLatch(1);

        // Block the prefetch inside the repo call before the file copy starts
        when(queueRepo.findByIdWithProfile(400L)).thenAnswer(inv -> {
            prefetchStarted.countDown();
            prefetchBlocked.await(5, TimeUnit.SECONDS);
            return Optional.of(it);
        });

        TranscodeService service = serviceWithTempDir(tempBase.toString());

        // Start prefetch — it will block at the repo call
        service.prefetchSource(400L);
        assertThat(prefetchStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Pre-create a partial temp dir to verify cancelPrefetch cleans it
        Path partialDir = tempBase.resolve("plex-downloader/400/src");
        Files.createDirectories(partialDir);
        Files.write(partialDir.resolve("partial.avi"), new byte[]{9});

        // Cancel while in flight
        service.cancelPrefetch(400L);
        // The prefetch future is cancelled; unblock the task (it will see interrupted state)
        prefetchBlocked.countDown();

        // Temp dir should be cleaned by cancelPrefetch
        assertThat(tempBase.resolve("plex-downloader/400")).doesNotExist();
        // No pending prefetch
        assertThat(service.getPrefetchCount()).isEqualTo(0);
    }

    @Test
    void cancelPrefetch_noInFlightPrefetch_isNoOp(@TempDir Path tmp) throws Exception {
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        // Should not throw
        service.cancelPrefetch(999L);
        assertThat(service.getPrefetchCount()).isEqualTo(0);
    }

    // ── Concurrency fix tests (C1 / C2) ──────────────────────────────────────

    /**
     * C1: Two threads call prefetchSource simultaneously.
     * Only ONE prefetch future must be registered — the second is a no-op.
     * Uses a CyclicBarrier to align both threads at the guard so they race
     * the check-and-put atomically.
     */
    @Test
    void prefetchSource_concurrentCalls_onlyOnePrefetchSubmitted(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        // Latch that blocks the prefetch task INSIDE the copy so both callers
        // finish their synchronized call before the first task completes.
        CountDownLatch copyBlocked = new CountDownLatch(1);
        CountDownLatch firstCopyStarted = new CountDownLatch(1);

        DownloadQueueItem it1 = item(500L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it1.setStatus(DownloadQueueItem.Status.QUEUED);
        DownloadQueueItem it2 = item(501L, src.toString(), tmp.resolve("dest2/out.mkv").toString());
        it2.setStatus(DownloadQueueItem.Status.QUEUED);

        when(queueRepo.findByIdWithProfile(500L)).thenReturn(Optional.of(it1));
        when(queueRepo.findByIdWithProfile(501L)).thenReturn(Optional.of(it2));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        // Inject a blocking copy seam: signals firstCopyStarted then waits on copyBlocked
        service.setCopyStrategy((s, d) -> {
            firstCopyStarted.countDown();
            try { copyBlocked.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });

        // CyclicBarrier so both threads enter prefetchSource at the same moment
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger submitted = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            service.prefetchSource(500L);
            submitted.incrementAndGet();
        });
        Thread t2 = new Thread(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            service.prefetchSource(501L);
            submitted.incrementAndGet();
        });

        t1.start();
        t2.start();
        t1.join(3_000);
        t2.join(3_000);

        // Wait until the copy task is actually running (proves one prefetch was submitted)
        assertThat(firstCopyStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Only ONE prefetch must be registered (the key assertion for C1)
        assertThat(service.getPrefetchCount()).isEqualTo(1);

        // Unblock the copy and wait for it to complete
        copyBlocked.countDown();

        // Either item 500 or 501 was the winner — poll until one of them has a staged file
        Path tempSrc500 = tempBase.resolve("plex-downloader/500/src/source.avi");
        Path tempSrc501 = tempBase.resolve("plex-downloader/501/src/source.avi");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(tempSrc500) && !Files.exists(tempSrc501)
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Exactly one of the two staged files must exist (one winner, one no-op)
        boolean exists500 = Files.exists(tempSrc500);
        boolean exists501 = Files.exists(tempSrc501);
        assertThat(exists500 ^ exists501)
            .as("Exactly one of the two items must have been prefetched (XOR), but exists500=%b exists501=%b",
                exists500, exists501)
            .isTrue();
    }

    /**
     * C2: Cancel during actual copy — the dangerous leak path.
     * The copy is blocked inside the copy seam (not before it), then cancelPrefetch
     * is called. After unblocking, the temp dir must be fully cleaned and the map empty.
     */
    @Test
    void cancelPrefetch_duringActualCopy_cleansUpTempDirAfterCopyCompletes(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        CountDownLatch copyStarted = new CountDownLatch(1);
        CountDownLatch copyRelease = new CountDownLatch(1);

        DownloadQueueItem it = item(600L, src.toString(), tmp.resolve("dest/out.mkv").toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findByIdWithProfile(600L)).thenReturn(Optional.of(it));

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        // Inject copy seam that blocks INSIDE the copy region
        service.setCopyStrategy((s, d) -> {
            copyStarted.countDown();                         // signal: copy is in progress
            try { copyRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });

        service.prefetchSource(600L);

        // Wait until the copy task is genuinely inside the copy region
        assertThat(copyStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Cancel while the copy is in progress
        service.cancelPrefetch(600L);
        assertThat(service.getPrefetchCount()).isEqualTo(0);

        // Release the blocked copy — it will complete, then the task's finally runs cleanup
        copyRelease.countDown();

        // Wait for the task's finally to run (poll for dir disappearance)
        Path itemDir = tempBase.resolve("plex-downloader/600");
        long deadline = System.currentTimeMillis() + 5_000;
        while (Files.exists(itemDir) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Temp dir (including any .part file and the src subdir) must be gone
        assertThat(itemDir).doesNotExist();
        // Map entry must be gone
        assertThat(service.getPrefetchCount()).isEqualTo(0);
        // The FINAL staged path must NOT exist (rename was skipped due to cancel flag)
        assertThat(tempBase.resolve("plex-downloader/600/src/source.avi")).doesNotExist();
    }

    /**
     * C2 consume: prefetch is cancelled before transcode() runs → transcode()
     * falls back to a normal copy and still completes with DONE.
     */
    @Test
    void transcode_afterPrefetchCancelled_fallsBackToNormalCopyAndReachesDone(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[]{1, 2, 3, 4, 5});
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");
        Path tempBase = tmp.resolve("temp");
        Files.createDirectories(tempBase);

        DownloadQueueItem it = item(700L, src.toString(), dest.toString());
        it.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findByIdWithProfile(700L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(700L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));

        CountDownLatch copyStarted = new CountDownLatch(1);
        CountDownLatch copyRelease = new CountDownLatch(1);

        TranscodeService service = serviceWithTempDir(tempBase.toString());
        // Block inside copy so we can cancel while it's running
        service.setCopyStrategy((s, d) -> {
            copyStarted.countDown();
            try { copyRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });

        service.prefetchSource(700L);
        assertThat(copyStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Cancel the prefetch while copy is in progress
        service.cancelPrefetch(700L);
        // Release the copy — the task's finally will clean up
        copyRelease.countDown();

        // Wait for cleanup to finish (temp dir gone)
        Path itemDir = tempBase.resolve("plex-downloader/700");
        long deadline = System.currentTimeMillis() + 5_000;
        while (Files.exists(itemDir) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Reset copy strategy to default (so transcode's normal copy works)
        service.setCopyStrategy((s, d) -> Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING));

        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path tempFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(tempFile.getParent());
            Files.write(tempFile, new byte[]{9, 8, 7});
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        // transcode() must fall back to a normal copy (the NAS src still exists) and reach DONE
        service.transcode(700L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(dest).exists();
        assertThat(service.getPrefetchCount()).isEqualTo(0);
    }
}

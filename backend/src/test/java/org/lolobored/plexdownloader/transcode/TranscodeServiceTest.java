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
import org.lolobored.plexdownloader.transcode.SubtitleProbe;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscodeServiceTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock MediaProbe mediaProbe;
    @Mock ProcessRunner processRunner;
    @Mock SubtitleProbe subtitleProbe;
    @Mock org.lolobored.plexdownloader.repository.MovieRepository movieRepo;
    @Mock org.lolobored.plexdownloader.repository.EpisodeRepository episodeRepo;

    private TranscodeService service() {
        TranscodeConfig cfg = new TranscodeConfig("ffmpeg", "ffprobe", "/dev/dri/renderD128");
        when(subtitleProbe.probe(anyString()))
            .thenReturn(new SubtitleProbe.ProbeResult(true, List.of()));
        return new TranscodeService(queueRepo, mediaProbe,
            new FfmpegCommandBuilder(cfg), new ProgressParser(), processRunner, subtitleProbe,
            movieRepo, episodeRepo);
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
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[1000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");

        DownloadQueueItem it = item(1L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(1L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(1L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=30000000");
            out.accept("progress=end");
            // ffmpeg writes to the dest path (last arg)
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[400]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(1L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getProgressPercent()).isEqualTo(100);
        assertThat(it.getCompletedAt()).isNotNull();
        assertThat(dest).exists();
    }

    @Test
    void staleSourcePath_refreshedFromMovieBeforeTranscode(@TempDir Path tmp) throws Exception {
        // Disk file lives at the NEW path; the queue snapshot still holds the OLD (renamed) path.
        Path stale = tmp.resolve("movie old-2160p.m4v");
        Path current = tmp.resolve("movie remux-1080p.m4v");
        Files.write(current, new byte[1000]);
        Path dest = tmp.resolve("dest/movie.mkv");

        DownloadQueueItem it = item(7L, stale.toString(), dest.toString());
        it.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        it.setMediaId(42L);

        org.lolobored.plexdownloader.model.Movie movie = new org.lolobored.plexdownloader.model.Movie();
        movie.setFilePath(current.toString());
        when(movieRepo.findById(42L)).thenReturn(Optional.of(movie));

        when(queueRepo.findByIdWithProfile(7L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(7L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[400]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(7L);

        // Snapshot is corrected and ffmpeg is fed the CURRENT path, not the stale one.
        assertThat(it.getSourceFilePath()).isEqualTo(current.toString());
        verify(mediaProbe).probe(current.toString());
        verify(mediaProbe, never()).probe(stale.toString());
        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
    }

    @Test
    void success_setsTRANSCODINGThenDone(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("x.avi");
        Files.write(src, new byte[500]);
        Path dest = tmp.resolve("dest/x.mkv");

        DownloadQueueItem it = item(2L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(2L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(2L)).thenReturn(Optional.of(it));

        List<DownloadQueueItem.Status> savedStatuses = new ArrayList<>();
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem saved = inv.getArgument(0);
            savedStatuses.add(saved.getStatus());
            return saved;
        });
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[200]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(2L);

        // Must go TRANSCODING → DONE
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.TRANSCODING);
        assertThat(savedStatuses).contains(DownloadQueueItem.Status.DONE);
        int transIdx = savedStatuses.indexOf(DownloadQueueItem.Status.TRANSCODING);
        int doneIdx  = savedStatuses.lastIndexOf(DownloadQueueItem.Status.DONE);
        assertThat(transIdx).isLessThan(doneIdx);
    }

    @Test
    void success_computesCompressionRatioFromNasPaths(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src.avi");
        Files.write(src, new byte[1000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("out.mkv");

        DownloadQueueItem it = item(5L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(5L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(5L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[400]); // 60% smaller
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(5L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getCompressionRatio()).isEqualTo(60.0);
        assertThat(it.getSourceSizeBytes()).isEqualTo(1000L);
        assertThat(it.getOutputSizeBytes()).isEqualTo(400L);
        assertThat(dest).exists();
    }

    @Test
    void success_setsAllSizeFieldsOnDone(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[2000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("out.mkv");

        DownloadQueueItem it = item(6L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(6L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(6L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[500]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(6L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getSourceSizeBytes()).isEqualTo(2000L);
        assertThat(it.getOutputSizeBytes()).isEqualTo(500L);
        // (2000-500)/2000 * 100 = 75.0
        assertThat(it.getCompressionRatio()).isEqualTo(75.0);
    }

    @Test
    void success_probesNasSource(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("movie.avi");
        Files.write(src, new byte[100]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("movie.mkv");

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
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[50]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(8L);

        // Probe must be called with the NAS source path directly
        assertThat(capturedProbeArg).hasSize(1);
        assertThat(capturedProbeArg.get(0)).isEqualTo(src.toString());
    }

    @Test
    void success_ffmpegInputIsNasSourceAndOutputIsNasDest(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("movie.avi");
        Files.write(src, new byte[100]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("movie.mkv");

        DownloadQueueItem it = item(9L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(9L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(9L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));

        List<List<String>> capturedCmds = new ArrayList<>();
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = new ArrayList<>((List<String>) inv.getArgument(0));
            capturedCmds.add(cmd);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[50]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(9L);

        assertThat(capturedCmds).hasSize(1);
        String cmdStr = String.join(" ", capturedCmds.get(0));
        // ffmpeg input is the NAS source
        assertThat(cmdStr).contains(src.toString());
        // ffmpeg output is the NAS dest
        assertThat(cmdStr).contains(dest.toString());
    }

    @Test
    void failure_setsErrorAndDeletesPartialDest(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("y.avi");
        Files.write(src, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("y.mkv");

        DownloadQueueItem it = item(3L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(3L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(3L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> err = inv.getArgument(2);
            err.accept("Error: bad frame");
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, "partial");
            return new RunningTranscode() {
                public int waitForExit() { return 1; }
                public void cancel() {}
            };
        });

        service().transcode(3L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("bad frame");
        // Partial dest deleted
        assertThat(dest).doesNotExist();
    }

    @Test
    void processRunnerStartThrows_setsError(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("a.avi");
        Files.write(src, new byte[50]);
        Path dest = tmp.resolve("dest/a.mkv");

        DownloadQueueItem it = item(20L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(20L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(20L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any()))
            .thenThrow(new java.io.UncheckedIOException("ffmpeg not found",
                new java.io.IOException("No such file or directory")));

        service().transcode(20L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("Transcode setup failed");
        assertThat(dest).doesNotExist();
    }

    @Test
    void mediaProbeThrows_setsError(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("b.avi");
        Files.write(src, new byte[50]);
        Path dest = tmp.resolve("dest/b.mkv");

        DownloadQueueItem it = item(21L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(21L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(21L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenThrow(new RuntimeException("ffprobe not found"));

        service().transcode(21L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("Transcode setup failed");
    }

    @Test
    void missingMediaRecord_failsFastWithoutRunningFfmpeg(@TempDir Path tmp) {
        Path src = tmp.resolve("obsession webdl-2160p.m4v"); // deliberately not created
        Path dest = tmp.resolve("dest/obsession.mkv");

        DownloadQueueItem it = item(99L, src.toString(), dest.toString());
        it.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        it.setMediaId(2166L);

        when(queueRepo.findByIdWithProfile(99L)).thenReturn(Optional.of(it));
        when(movieRepo.findById(2166L)).thenReturn(Optional.empty()); // pruned row
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().transcode(99L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError())
            .contains("Source media no longer in Plex");
        verify(mediaProbe, never()).probe(anyString());
        verify(processRunner, never()).start(anyList(), any(), any());
    }

    @Test
    void cancel_unknownItem_returnsFalse() {
        assertThat(service().cancel(999L)).isFalse();
    }

    @Test
    void cancel_registeredItem_returnsTrue(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("z.avi");
        Files.write(src, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("z.mkv");

        DownloadQueueItem it = item(4L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(4L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(4L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));

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

        TranscodeService svc = service();
        Thread transcodeThread = new Thread(() -> svc.transcode(4L));
        transcodeThread.start();

        long deadline = System.currentTimeMillis() + 2_000;
        while (!svc.cancel(4L)) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Not registered within 2s");
            Thread.sleep(10);
        }

        assertThat(cancelCalled.get()).isTrue();
        transcodeThread.join(2_000);
        assertThat(transcodeThread.isAlive()).isFalse();
    }

    @Test
    void cancelUnknownItemGone_deletesPartialDest(@TempDir Path tmp) throws Exception {
        // If item is gone from DB after ffmpeg exits (cancelled+deleted), partial dest is deleted
        Path src = tmp.resolve("gone.avi");
        Files.write(src, new byte[50]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("gone.mkv");

        DownloadQueueItem it = item(30L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(30L)).thenReturn(Optional.of(it));
        // After exit, item is gone (cancelled)
        when(queueRepo.findById(30L)).thenReturn(Optional.empty());
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[10]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service().transcode(30L);

        // Partial dest must be cleaned up
        assertThat(dest).doesNotExist();
    }

    @Test
    void done_capturesOutputSubtitleLangs(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("source.avi");
        Files.write(src, new byte[1000]);
        Path destDir = tmp.resolve("dest");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("source.mkv");

        DownloadQueueItem it = item(50L, src.toString(), dest.toString());
        when(queueRepo.findByIdWithProfile(50L)).thenReturn(Optional.of(it));
        when(queueRepo.findById(50L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(src.toString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            Path outFile = Path.of(cmd.get(cmd.size() - 1));
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, new byte[400]);
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        // Build the service first (which registers the default anyString() stub),
        // then override with the specific path stub so LIFO picks the specific one.
        TranscodeService svc = service();
        when(subtitleProbe.probe(dest.toString()))
            .thenReturn(new SubtitleProbe.ProbeResult(true, List.of("eng", "fra")));

        svc.transcode(50L);

        assertThat(it.getOutputSubtitleLangs()).isEqualTo(",eng,fra,");
        assertThat(it.getOutputSubtitlesScannedAt()).isNotNull();
    }
}

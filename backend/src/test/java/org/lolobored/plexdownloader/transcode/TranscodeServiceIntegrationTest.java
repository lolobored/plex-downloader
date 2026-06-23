package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.model.User;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.repository.QualityProfileRepository;
import org.lolobored.plexdownloader.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression test for the lazy-profile bug. {@code DownloadQueueItem.qualityProfile} is a
 * LAZY {@code @ManyToOne} and {@link TranscodeService#transcode(Long)} runs on a background
 * worker thread (no open Hibernate session). Loading the item with a plain {@code findById}
 * and then dereferencing the detached profile throws {@code LazyInitializationException} at
 * command-build time. We persist a real profile + queue item via the real repositories and
 * run {@code transcode} on a SEPARATE thread (so no test-thread session keeps the proxy
 * initialisable). The fetch-join load must let it reach DONE without exploding.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class TranscodeServiceIntegrationTest {

    @Autowired TranscodeService transcodeService;
    @Autowired DownloadQueueRepository queueRepo;
    @Autowired QualityProfileRepository profileRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean MediaProbe mediaProbe;
    @MockitoBean ProcessRunner processRunner;

    @Test
    void transcode_loadsLazyProfileOnWorkerThread_reachesDone() throws Exception {
        User user = new User();
        user.setPlexAccountId("acct-it-1");
        user.setUsername("ituser");
        user = userRepo.save(user);

        QualityProfile profile = new QualityProfile();
        profile.setName("IntegrationProfile");
        profile.setCodec(QualityProfile.Codec.HEVC_QSV);
        profile.setContainer(QualityProfile.Container.MKV);
        profile.setResolutionCap(QualityProfile.ResolutionCap.KEEP);
        profile.setAudioMode(QualityProfile.AudioMode.COPY);
        profile = profileRepo.save(profile);

        java.nio.file.Path tempBase = java.nio.file.Files.createTempDirectory("transcode-it");
        java.nio.file.Path src = tempBase.resolve("source.avi");
        java.nio.file.Files.write(src, new byte[]{0x00, 0x01, 0x02, 0x03});
        java.nio.file.Path dest = tempBase.resolve("out.mkv");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        item.setMediaId(1L);
        item.setTitle("It Movie");
        item.setSourceFilePath(src.toString());
        item.setDestFilePath(dest.toString());
        item.setQualityProfile(profile);
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        item = queueRepo.save(item);
        Long itemId = item.getId();

        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=60000000");
            out.accept("progress=end");
            // ffmpeg writes the output directly to the dest path (last arg of cmd)
            java.util.List<String> cmd = inv.getArgument(0);
            java.nio.file.Path outFile = java.nio.file.Path.of(cmd.get(cmd.size() - 1));
            java.nio.file.Files.createDirectories(outFile.getParent());
            java.nio.file.Files.write(outFile, new byte[]{1, 2, 3});
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                transcodeService.transcode(itemId);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join(10_000);
        assertThat(worker.isAlive()).as("worker should have finished").isFalse();
        assertThat(failure.get())
            .as("transcode must not throw LazyInitializationException")
            .isNull();

        DownloadQueueItem reloaded = queueRepo.findById(itemId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(reloaded.getProgressPercent()).isEqualTo(100);
    }
}

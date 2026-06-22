package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the empty-queue 500. {@code DownloadQueueItem.qualityProfile} is a
 * LAZY {@code @ManyToOne}; {@link DownloadService#getQueue(Long)} is not transactional and
 * {@code DownloadQueueItemResponse.from} reads {@code qualityProfile.getName()}. With
 * {@code open-in-view: false} and a plain finder the detached proxy throws
 * {@code LazyInitializationException} during DTO mapping. The fetch-join finder must let
 * getQueue map the profile name without a session. The test method is intentionally NOT
 * {@code @Transactional} so no session keeps the proxy initialisable (reproduces production).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class GetQueueIntegrationTest {

    @Autowired DownloadService downloadService;
    @Autowired DownloadQueueRepository queueRepo;
    @Autowired QualityProfileRepository profileRepo;
    @Autowired UserRepository userRepo;

    @Test
    void getQueue_mapsLazyProfileNameWithoutOpenSession() {
        User user = new User();
        user.setPlexAccountId("acct-getqueue-1");
        user.setUsername("getqueueuser");
        user = userRepo.save(user);

        QualityProfile profile = new QualityProfile();
        profile.setName("QueueProfile");
        profile.setCodec(QualityProfile.Codec.HEVC_QSV);
        profile.setContainer(QualityProfile.Container.MKV);
        profile.setResolutionCap(QualityProfile.ResolutionCap.KEEP);
        profile.setAudioMode(QualityProfile.AudioMode.COPY);
        profile = profileRepo.save(profile);

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        item.setMediaId(42L);
        item.setTitle("Queued Movie");
        item.setSourceFilePath("/movies/queued.avi");
        item.setDestFilePath("/plex-conversion/libraries/movies/queued.mkv");
        item.setQualityProfile(profile);
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        item.setQueuePosition(1);
        queueRepo.save(item);

        // Would throw LazyInitializationException with a non-fetch-join finder.
        List<DownloadQueueItemResponse> queue = downloadService.getQueue(user.getId());

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).qualityProfileName()).isEqualTo("QueueProfile");
        assertThat(queue.get(0).status()).isEqualTo(DownloadQueueItem.Status.QUEUED);
    }
}

package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscodeQueueRunnerTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock TranscodeService transcodeService;
    @Mock SettingsService settings;

    private TranscodeQueueRunner runner() {
        when(settings.get("transcode.max.concurrent")).thenReturn(Optional.of("2"));
        return new TranscodeQueueRunner(queueRepo, transcodeService, settings);
    }

    @Test
    void submit_invokesTranscode() {
        TranscodeQueueRunner r = runner();
        r.submit(5L);
        verify(transcodeService, timeout(2000)).transcode(5L);
    }

    @Test
    void setMaxConcurrentUpdatesGetterAndPersists() {
        TranscodeQueueRunner r = runner();
        r.setMaxConcurrent(4);
        assertThat(r.getMaxConcurrent()).isEqualTo(4);
        verify(settings).set("transcode.max.concurrent", "4");
    }

    @Test
    void setMaxConcurrentClampsToOneAndPersists() {
        TranscodeQueueRunner r = runner();
        r.setMaxConcurrent(0);
        assertThat(r.getMaxConcurrent()).isEqualTo(1);
        verify(settings).set("transcode.max.concurrent", "1");
    }

    @Test
    void getMaxConcurrentReflectsInitialSetting() {
        TranscodeQueueRunner r = runner();
        assertThat(r.getMaxConcurrent()).isEqualTo(2);
    }

    @Test
    void recover_resetsTranscodingAndResubmitsQueued() {
        DownloadQueueItem stuck = new DownloadQueueItem();
        stuck.setId(1L); stuck.setStatus(DownloadQueueItem.Status.TRANSCODING);
        DownloadQueueItem queued = new DownloadQueueItem();
        queued.setId(2L); queued.setStatus(DownloadQueueItem.Status.QUEUED);

        when(queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)).thenReturn(List.of(stuck));
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED))
            .thenReturn(List.of(stuck, queued));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TranscodeQueueRunner r = runner();
        r.recover();

        assertThat(stuck.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        verify(transcodeService, timeout(2000)).transcode(1L);
        verify(transcodeService, timeout(2000)).transcode(2L);
    }
}

package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.SubtitleProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubtitleScanServiceTest {
    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock SubtitleProbe subtitleProbe;
    @InjectMocks SubtitleScanService service;

    @Test void scan_unknowns_setsSourceLangsAndScannedAt() {
        Movie m = new Movie(); m.setId(1L); m.setFilePath("/nas/m.mkv");
        when(movieRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of(m));
        when(episodeRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of());
        when(queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNullAndDestFilePathIsNotNull(DownloadQueueItem.Status.DONE)).thenReturn(List.of());
        when(subtitleProbe.probe("/nas/m.mkv")).thenReturn(new SubtitleProbe.ProbeResult(true, List.of("eng")));

        service.scan(false);

        verify(movieRepo).save(argThat(x -> ",eng,".equals(x.getSubtitleLangs()) && x.getSubtitlesScannedAt() != null));
        assertThat(service.status().scanned()).isEqualTo(1);
    }

    @Test void scan_failedProbe_leavesUnscanned_countsFailed() {
        Movie m = new Movie(); m.setId(1L); m.setFilePath("/nas/bad.mkv");
        when(movieRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of(m));
        when(episodeRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of());
        when(queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNullAndDestFilePathIsNotNull(any())).thenReturn(List.of());
        when(subtitleProbe.probe("/nas/bad.mkv")).thenReturn(new SubtitleProbe.ProbeResult(false, List.of()));

        service.scan(false);

        verify(movieRepo, never()).save(any());
        assertThat(service.status().failed()).isEqualTo(1);
    }

    @Test void scan_nullPath_movie_isSkippedAndNotCountedAsRemaining() {
        Movie nullPathMovie = new Movie(); nullPathMovie.setId(2L); // filePath is null
        // The ...AndFilePathIsNotNull finder excludes it → scan() never sees it
        when(movieRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of());
        when(episodeRepo.findBySubtitlesScannedAtIsNullAndFilePathIsNotNull()).thenReturn(List.of());
        when(queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNullAndDestFilePathIsNotNull(any())).thenReturn(List.of());

        service.scan(false);

        verify(subtitleProbe, never()).probe(any());
        assertThat(service.status().remainingUnknown()).isEqualTo(0);
        assertThat(service.status().scanned()).isEqualTo(0);
        assertThat(service.status().failed()).isEqualTo(0);
    }
}

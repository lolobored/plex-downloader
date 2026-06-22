package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "quality_profile")
public class QualityProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Codec codec = Codec.HEVC_QSV;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Container container = Container.MKV;

    @Column(name = "quality_level", nullable = false)
    private int qualityLevel = 23;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_cap", nullable = false)
    private ResolutionCap resolutionCap = ResolutionCap.KEEP;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_mode", nullable = false)
    private AudioMode audioMode = AudioMode.COPY;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public enum Codec {
        HEVC_QSV("hevc_qsv"), H264_QSV("h264_qsv");
        private final String ffmpegName;
        Codec(String n) { this.ffmpegName = n; }
        public String ffmpegName() { return ffmpegName; }
    }

    public enum Container {
        MKV(".mkv"), MP4(".mp4");
        private final String ext;
        Container(String e) { this.ext = e; }
        public String extension() { return ext; }
    }

    public enum ResolutionCap {
        KEEP(0), UHD_4K(2160), P1080(1080), P720(720);
        private final int maxHeight;
        ResolutionCap(int h) { this.maxHeight = h; }
        public int maxHeight() { return maxHeight; }
    }

    public enum AudioMode { COPY, AAC }
}

package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FfmpegCommandBuilder {

    public List<String> build(QualityProfile profile, String sourcePath, String destPath, MediaInfo source) {
        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-nostdin");
        args.add("-y");
        args.add("-hwaccel"); args.add("qsv");
        args.add("-hwaccel_output_format"); args.add("qsv");
        args.add("-i"); args.add(sourcePath);

        int cap = profile.getResolutionCap().maxHeight();
        if (cap > 0 && source.height() > cap) {
            args.add("-vf"); args.add("scale_qsv=-1:" + cap);
        }

        args.add("-c:v"); args.add(profile.getCodec().ffmpegName());
        args.add("-global_quality"); args.add(Integer.toString(profile.getQualityLevel()));

        args.add("-c:a");
        args.add(profile.getAudioMode() == QualityProfile.AudioMode.AAC ? "aac" : "copy");

        args.add("-progress"); args.add("pipe:1");
        args.add("-nostats");

        args.add(destPath);
        return args;
    }
}

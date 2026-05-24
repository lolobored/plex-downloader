package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.Setting;
import org.lolobored.plexdownloader.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingRepository repo;

    public Optional<String> get(String key) {
        return repo.findById(key).map(Setting::getValue);
    }

    public String getRequired(String key) {
        return get(key).orElseThrow(() ->
            new IllegalStateException("Required setting missing: " + key));
    }

    public void set(String key, String value) {
        Setting s = new Setting();
        s.setKey(key);
        s.setValue(value);
        repo.save(s);
    }
}
